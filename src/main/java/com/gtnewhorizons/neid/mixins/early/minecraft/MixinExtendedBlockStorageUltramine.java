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
    @Shadow
    private int blockRefCount;

    @Shadow
    private int tickRefCount;

    /**
     * DIAGNOSTIC: Log ORIGINAL MemSlot state before copy() to verify it has data.
     */
    @Inject(method = "copy", at = @At("HEAD"), remap = false, require = 0)
    private void neid$syncBeforeCopy(CallbackInfoReturnable<ExtendedBlockStorage> cir) {
        try {
            java.lang.reflect.Field slotField = ExtendedBlockStorage.class.getDeclaredField("slot");
            slotField.setAccessible(true);
            Object slot = slotField.get(this);

            if (slot != null) {
                Class<?> slotClass = slot.getClass();
                java.lang.reflect.Method getBlockIdMethod = slotClass
                        .getMethod("getBlockId", int.class, int.class, int.class);
                int origTest = (int) getBlockIdMethod.invoke(slot, 0, 0, 0);

                LOGGER.info(
                        "[COPY-BEFORE] ORIGINAL MemSlot: slot={}, block(0,0,0)={}",
                        slotClass.getSimpleName(),
                        origTest);
            } else {
                LOGGER.warn("[COPY-BEFORE] ORIGINAL MemSlot is NULL!");
            }
        } catch (Exception e) {
            LOGGER.error("[COPY-BEFORE] Failed to check original MemSlot", e);
        }
    }

    /**
     * CRITICAL: After copy() returns, read from COPY's MemSlot and write to COPY's NEID arrays! ultramine's
     * ExtendedBlockStorage writes directly to MemSlot, NOT to NEID arrays. So ORIGINAL NEID arrays are EMPTY! We must
     * read from MemSlot instead.
     */
    @Inject(method = "copy", at = @At("RETURN"), remap = false, require = 0)
    private void neid$syncFromMemSlotAfterCopy(CallbackInfoReturnable<ExtendedBlockStorage> cir) {
        ExtendedBlockStorage copy = cir.getReturnValue();

        // DIAGNOSTIC: Check blockRefCount and isEmpty()
        int origBlockRefCount = this.blockRefCount;
        boolean origIsEmpty = ((ExtendedBlockStorage) (Object) this).isEmpty();
        int copyBlockRefCount = -1;
        boolean copyIsEmpty = true;

        if (copy != null) {
            // Get blockRefCount from copy via reflection (safer than casting)
            try {
                java.lang.reflect.Field blockRefCountField = ExtendedBlockStorage.class
                        .getDeclaredField("blockRefCount");
                blockRefCountField.setAccessible(true);
                copyBlockRefCount = blockRefCountField.getInt(copy);
            } catch (Exception e) {
                LOGGER.debug("[COPY] Could not read copy blockRefCount: {}", e.getMessage());
            }
            copyIsEmpty = copy.isEmpty();
        }

        LOGGER.info(
                "[COPY] orig: blockRefCount={}, isEmpty={}; copy: blockRefCount={}, isEmpty={}",
                origBlockRefCount,
                origIsEmpty,
                copyBlockRefCount,
                copyIsEmpty);

        if (copy != null && copy != (Object) this) {
            LOGGER.info("[COPY] Calling syncMemSlotToNeidArrays for copy");
            syncMemSlotToNeidArrays(copy);
        } else {
            LOGGER.warn("[COPY] Skipped sync: copy={}, same={}", copy != null, copy == (Object) this);
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
     * CRITICAL: Before removeInvalidBlocks() reads from NEID arrays, sync FROM MemSlot! After loading from NBT,
     * ultramine calls setData() which populates MemSlot, then calls removeInvalidBlocks(). But base NEID's @Overwrite
     * removeInvalidBlocks() reads from block16BArray (NOT MemSlot)! So we must sync MemSlot→NEID arrays BEFORE
     * removeInvalidBlocks() runs!
     */
    @Inject(method = "removeInvalidBlocks", at = @At("HEAD"), require = 0)
    private void neid$syncFromMemSlotBeforeRemoveInvalidBlocks(CallbackInfo ci) {
        syncMemSlotToNeidArrays((ExtendedBlockStorage) (Object) this);
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
        IExtendedBlockStorageMixin thisMixin = (IExtendedBlockStorageMixin) this;
        short[] block16BArray = thisMixin.getBlock16BArray();
        short[] block16BMetaArray = thisMixin.getBlock16BMetaArray();

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

            // DIAGNOSTIC: Check if MemSlot actually has data at multiple positions
            int test000 = (int) getBlockIdMethod.invoke(slot, 0, 0, 0);
            int test555 = (int) getBlockIdMethod.invoke(slot, 5, 5, 5);
            int test151515 = (int) getBlockIdMethod.invoke(slot, 15, 15, 15);

            // Get pointer and isReleased for ultramine MemSlot
            long pointer = -1;
            boolean isReleased = true;
            try {
                java.lang.reflect.Method getPointerMethod = slotClass.getDeclaredMethod("getPointer");
                getPointerMethod.setAccessible(true);
                pointer = (long) getPointerMethod.invoke(slot);

                java.lang.reflect.Field isReleasedField = slotClass.getSuperclass().getDeclaredField("isReleased");
                isReleasedField.setAccessible(true);
                isReleased = (boolean) isReleasedField.get(slot);
            } catch (Exception e) {
                // Ignore
            }

            LOGGER.info(
                    "[SYNC] MemSlot: slot={}, ptr=0x{}, released={}, (0,0,0)={}, (5,5,5)={}, (15,15,15)={}",
                    slotClass.getSimpleName(),
                    Long.toHexString(pointer),
                    isReleased,
                    test000,
                    test555,
                    test151515);
            java.lang.reflect.Method getMetaMethod = slotClass.getMethod("getMeta", int.class, int.class, int.class);

            // Sample for logging
            int nonAirBlocks = 0;
            int sampleBlockId = -1;
            int sampleX = -1, sampleY = -1, sampleZ = -1;

            // CRITICAL FIX: NEID arrays use COORDINATE indexing (y<<8|z<<4|x), NOT sequential!
            // Base NEID's getBlockId/setBlockId/removeInvalidBlocks all use coordinate indexing!
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int blockId = (int) getBlockIdMethod.invoke(slot, x, y, z);
                        int meta = (int) getMetaMethod.invoke(slot, x, y, z);

                        int coordIndex = y << 8 | z << 4 | x;
                        targetBlockArray[coordIndex] = (short) (blockId & 0xFFFF);
                        targetMetaArray[coordIndex] = (short) (meta & 0xFFFF);

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
