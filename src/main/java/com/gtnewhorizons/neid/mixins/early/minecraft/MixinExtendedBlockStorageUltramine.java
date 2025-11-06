package com.gtnewhorizons.neid.mixins.early.minecraft;

import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;

/**
 * Ultramine-specific compatibility mixin for ExtendedBlockStorage. This mixin handles synchronization between NEID's
 * 16-bit block arrays and ultramine_core's off-heap MemSlot storage.
 *
 * Priority 1500 ensures it applies after the base NEID mixin (default 1000).
 */
@Mixin(value = ExtendedBlockStorage.class, priority = 1500)
public abstract class MixinExtendedBlockStorageUltramine {

    private static final Logger LOGGER = LogManager.getLogger("NEID-Ultramine");
    private static final boolean DEBUG = Boolean.getBoolean("neid.ultramine.debug");

    // Shadow fields from base NEID mixin
    // These fields are added by MixinExtendedBlockStorage
    @Shadow(remap = false)
    private short[] block16BArray;

    @Shadow(remap = false)
    private short[] block16BMetaArray;

    /**
     * No-op: Ultramine writes DIRECTLY to MemSlot (not NEID arrays), so no sync needed before copy().
     */
    @Inject(method = "copy", at = @At("HEAD"), remap = false, require = 0)
    private void neid$syncBeforeCopy(CallbackInfoReturnable<ExtendedBlockStorage> cir) {
        // No-op: MemSlot already has correct data from ultramine's setBlockId()
    }

    /**
     * CRITICAL: After copy() returns, read from COPY's MemSlot and write to COPY's NEID arrays! ultramine's
     * ExtendedBlockStorage writes directly to MemSlot, NOT to NEID arrays. So ORIGINAL NEID arrays are EMPTY! We must
     * read from MemSlot instead.
     */
    @Inject(method = "copy", at = @At("RETURN"), remap = false, require = 0)
    private void neid$syncFromMemSlotAfterCopy(CallbackInfoReturnable<ExtendedBlockStorage> cir) {
        ExtendedBlockStorage copy = cir.getReturnValue();
        if (copy != null && copy != (Object) this) {
            syncMemSlotToNeidArrays(copy);
        }
    }

    /**
     * Reads a 4-bit nibble from coordinate-ordered nibble array. Ultramine nibble arrays are packed in coordinate
     * order: y << 8 | z << 4 | x
     */
    private static int get4bitsCoordinate(byte[] arr, int x, int y, int z) {
        int ind = y << 8 | z << 4 | x;
        byte b = arr[ind >> 1];
        return (ind & 1) == 0 ? (b & 0xF) : ((b >> 4) & 0xF);
    }

    /**
     * CRITICAL: Intercept NEID's setBlockId to sync TO ultramine MemSlot! Base NEID @Overwrite's func_150818_a and only
     * writes to block16BArray. We must sync every block change to MemSlot.
     */
    @Inject(method = "setBlockId", at = @At("RETURN"), remap = false, require = 0)
    private void neid$syncToMemSlotAfterSetBlock(int x, int y, int z, int id, CallbackInfo ci) {
        try {
            Object slot = getSlotViaReflection();
            if (slot != null) {
                Class<?> slotClass = slot.getClass();
                java.lang.reflect.Method setBlockId = slotClass
                        .getMethod("setBlockId", int.class, int.class, int.class, int.class);
                setBlockId.invoke(slot, x, y, z, id);
            }
        } catch (Exception e) {
            // Silently ignore - setBlockId is called very frequently
        }
    }

    /**
     * DO NOT sync before getSlot()! This would overwrite MemSlot (which has correct data from worldgen/ultramine) with
     * NEID arrays (which are empty because ultramine writes directly to MemSlot). Syncing only needed: 1) After loading
     * from NBT (MixinAnvilChunkLoaderUltramine) 2) After setBlockId (already handled above)
     */

    /**
     * Synchronizes all 4096 blocks from NEID's 16-bit arrays to ultramine's MemSlot.
     *
     * Block IDs > 4095 are truncated to 12 bits (0-4095) because Unsafe7MemSlot only supports vanilla's 12-bit block ID
     * space (8-bit LSB + 4-bit MSB).
     *
     * This limitation is acceptable because: 1. Extended block IDs (4096-32767) are preserved in NEID arrays for world
     * saving 2. Network packets will use NEID's custom packet handler (MixinS21PacketChunkDataUltramine) 3. Client-side
     * still receives full 16-bit block IDs
     */
    private void syncNeidArraysToMemSlot() {
        if (block16BArray == null || block16BMetaArray == null) {
            if (DEBUG) {
                LOGGER.warn("NEID arrays are null, skipping sync");
            }
            return;
        }

        // Get slot field through reflection (it's added by ultramine)
        Object slot = getSlotViaReflection();
        if (slot == null) {
            if (DEBUG) {
                LOGGER.warn("MemSlot is null, skipping sync");
            }
            return;
        }

        try {
            // Use reflection to access MemSlot methods
            // This avoids compile-time dependency on ultramine classes
            Class<?> memSlotClass = slot.getClass();

            // Get methods: setBlockId(int x, int y, int z, int id)
            // setMeta(int x, int y, int z, int meta)
            java.lang.reflect.Method setBlockIdMethod = memSlotClass
                    .getMethod("setBlockId", int.class, int.class, int.class, int.class);
            java.lang.reflect.Method setMetaMethod = memSlotClass
                    .getMethod("setMeta", int.class, int.class, int.class, int.class);

            int truncatedBlocks = 0;
            int truncatedMetaCount = 0;

            // Iterate all 4096 blocks (16x16x16) in y→z→x order to match index calculation
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int index = y << 8 | z << 4 | x;
                        int blockId = block16BArray[index] & 0xFFFF;
                        int meta = block16BMetaArray[index] & 0xFFFF;

                        // Truncate block ID to 12 bits (0-4095) for Unsafe7MemSlot
                        int truncatedBlockId = blockId & 0xFFF;
                        if (blockId != truncatedBlockId) {
                            truncatedBlocks++;
                        }

                        // Truncate metadata to 4 bits (0-15) for vanilla compatibility
                        // ultramine uses full 16-bit internally, but we limit for safety
                        int metaValue = meta & 0xF;
                        if (meta != metaValue) {
                            truncatedMetaCount++;
                        }

                        // Invoke setBlockId and setMeta on MemSlot
                        setBlockIdMethod.invoke(slot, x, y, z, truncatedBlockId);
                        setMetaMethod.invoke(slot, x, y, z, metaValue);
                    }
                }
            }

            if (DEBUG && (truncatedBlocks > 0 || truncatedMetaCount > 0)) {
                LOGGER.info(
                        "Sync complete. Truncated {} extended block IDs and {} extended metadata values",
                        truncatedBlocks,
                        truncatedMetaCount);
            }

        } catch (NoSuchMethodException e) {
            LOGGER.error("Failed to find MemSlot methods. ultramine API may have changed.", e);
        } catch (Exception e) {
            LOGGER.error("Failed to sync NEID arrays to MemSlot", e);
        }
    }

    /**
     * Gets the MemSlot field from this ExtendedBlockStorage instance using reflection. The slot field is added by
     * ultramine, so we can't use @Shadow.
     */
    private Object getSlotViaReflection() {
        try {
            java.lang.reflect.Field slotField = ExtendedBlockStorage.class.getDeclaredField("slot");
            slotField.setAccessible(true);
            return slotField.get(this);
        } catch (NoSuchFieldException e) {
            // Field doesn't exist - not ultramine or different version
            if (DEBUG) {
                LOGGER.error("slot field not found in ExtendedBlockStorage", e);
            }
            return null;
        } catch (Exception e) {
            LOGGER.error("Failed to access slot field", e);
            return null;
        }
    }

    /**
     * Synchronizes MemSlot data TO NEID arrays for a given ExtendedBlockStorage (typically a copy). This is used after
     * copy() to populate the copy's NEID arrays from its MemSlot.
     */
    private void syncMemSlotToNeidArrays(ExtendedBlockStorage ebs) {
        try {
            IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
            short[] targetBlockArray = ebsMixin.getBlock16BArray();
            short[] targetMetaArray = ebsMixin.getBlock16BMetaArray();

            if (targetBlockArray == null || targetMetaArray == null) {
                LOGGER.warn("Target NEID arrays are null, cannot sync from MemSlot");
                return;
            }

            // Get MemSlot from the target EBS
            java.lang.reflect.Field slotField = ExtendedBlockStorage.class.getDeclaredField("slot");
            slotField.setAccessible(true);
            Object slot = slotField.get(ebs);

            if (slot == null) {
                LOGGER.warn("Target EBS has null MemSlot, cannot sync");
                return;
            }

            // Get methods from MemSlot
            Class<?> slotClass = slot.getClass();
            java.lang.reflect.Method getBlockIdMethod = slotClass
                    .getMethod("getBlockId", int.class, int.class, int.class);
            java.lang.reflect.Method getMetaMethod = slotClass.getMethod("getMeta", int.class, int.class, int.class);

            // Sample for logging
            int nonAirBlocks = 0;
            int sampleBlockId = -1;
            int sampleX = -1, sampleY = -1, sampleZ = -1;

            // Sync all blocks: MemSlot → NEID arrays (y→z→x order to match index calculation)
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int index = y << 8 | z << 4 | x;
                        int blockId = (int) getBlockIdMethod.invoke(slot, x, y, z);
                        int meta = (int) getMetaMethod.invoke(slot, x, y, z);
                        targetBlockArray[index] = (short) (blockId & 0xFFFF);
                        targetMetaArray[index] = (short) (meta & 0xFFFF);

                        // Sample for debug
                        if (blockId != 0) {
                            nonAirBlocks++;
                            if (sampleBlockId == -1) {
                                sampleBlockId = blockId;
                                sampleX = x;
                                sampleY = y;
                                sampleZ = z;
                            }
                        }
                    }
                }
            }

            LOGGER.info(
                    "Synced MemSlot→NEID: {} non-air blocks. Sample: block {} at ({},{},{})",
                    nonAirBlocks,
                    sampleBlockId,
                    sampleX,
                    sampleY,
                    sampleZ);
        } catch (NoSuchFieldException e) {
            LOGGER.error("MemSlot field not found", e);
        } catch (NoSuchMethodException e) {
            LOGGER.error("Failed to find MemSlot methods", e);
        } catch (Exception e) {
            LOGGER.error("Failed to sync MemSlot to NEID arrays", e);
        }
    }
}
