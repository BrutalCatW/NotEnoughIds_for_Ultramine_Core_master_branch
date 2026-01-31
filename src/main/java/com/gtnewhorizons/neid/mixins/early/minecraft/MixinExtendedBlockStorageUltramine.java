package com.gtnewhorizons.neid.mixins.early.minecraft;

import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ultramine.server.chunk.alloc.MemSlot;

import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;

// spotless:off
/**
 * Ultramine-specific compatibility mixin for ExtendedBlockStorage. This mixin handles synchronization between NEID's
 * 16-bit block arrays and ultramine_core's off-heap MemSlot storage.
 *
 * Priority 1500 ensures it applies after the base NEID mixin (default 1000).
 */
@Mixin(value = ExtendedBlockStorage.class, priority = 1500)
public abstract class MixinExtendedBlockStorageUltramine {
    private static final boolean DEBUG = Boolean.getBoolean("neid.ultramine.debug");

    /**
     * CRITICAL: After copy() returns, copy NEID arrays from ORIGINAL to COPY! Ultramine copy() creates new EBS with
     * MemSlot copy, but DOES NOT copy NEID arrays (block16BArray/block16BMetaArray). MemSlot only stores 4-bit
     * metadata, but NEID arrays store full 16-bit metadata! We must copy NEID arrays directly to preserve extended
     * (16-bit) metadata!
     */
    @Inject(method = "copy", at = @At("RETURN"), remap = false, require = 0)
    private void neid$copyNeidArraysAfterCopy(CallbackInfoReturnable<ExtendedBlockStorage> cir) {
        ExtendedBlockStorage copy = cir.getReturnValue();

        if (copy != null && copy != (Object) this) {
            try {
                IExtendedBlockStorageMixin origMixin = (IExtendedBlockStorageMixin) this;
                IExtendedBlockStorageMixin copyMixin = (IExtendedBlockStorageMixin) copy;

                short[] origBlockArray = origMixin.getBlock16BArray();
                short[] origMetaArray = origMixin.getBlock16BMetaArray();

                if (origBlockArray != null && origMetaArray != null) {
                    short[] copyBlockArray = copyMixin.getBlock16BArray();
                    short[] copyMetaArray = copyMixin.getBlock16BMetaArray();

                    if (copyBlockArray != null && copyMetaArray != null) {
                        // Copy NEID arrays directly to preserve 16-bit metadata
                        System.arraycopy(origBlockArray, 0, copyBlockArray, 0, 4096);
                        System.arraycopy(origMetaArray, 0, copyMetaArray, 0, 4096);

                        // DEBUG: Uncomment for debugging
                        // LOGGER.debug("[COPY] Copied NEID arrays (16-bit metadata preserved)");
                    } else {
                        // LOGGER.warn("[COPY] Copy NEID arrays are null, cannot copy");
                    }
                } else {
                    // LOGGER.warn("[COPY] Original NEID arrays are null, cannot copy");
                }
            } catch (Exception e) {
                // LOGGER.error("[COPY] Failed to copy NEID arrays", e);
            }
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
     * writes to block16BArray. We must sync every block change to MemSlot. PERFORMANCE: Uses cached reflection methods
     * to avoid repeated lookups.
     */
    @Inject(method = "setBlockId", at = @At("RETURN"), remap = false, require = 0)
    private void neid$syncToMemSlotAfterSetBlock(int x, int y, int z, int id, CallbackInfo ci) {
        try {
            MemSlot slot = ((ExtendedBlockStorage)(Object) this).getSlot();
            if (slot != null) {
                slot.setBlockId(x, y, z, id);
            }
        } catch (Exception e) {
            // Silently ignore - setBlockId is called very frequently
        }
    }

    /**
     * CRITICAL: Intercept NEID's setExtBlockMetadata to sync TO ultramine MemSlot! Base NEID @Overwrite only writes to
     * block16BMetaArray. We must sync metadata changes to MemSlot so that ChunkSnapshot.copy() sees updated values.
     * PERFORMANCE: Uses cached reflection methods to avoid repeated lookups. NOTE: MemSlot only stores 4-bit metadata,
     * so values > 15 are truncated. Full 16-bit values are preserved in block16BMetaArray for saving/transmission.
     */
    @Inject(method = "setExtBlockMetadata", at = @At("RETURN"), require = 0)
    private void neid$syncMetaToMemSlotAfterSetMetadata(int x, int y, int z, int meta, CallbackInfo ci) {
        try {
            MemSlot slot = ((ExtendedBlockStorage)(Object) this).getSlot();
            if (slot != null) {
                // MemSlot only supports 4-bit metadata, truncate to avoid errors
                slot.setMeta(x, y, z, meta & 0xF);
            }
        } catch (Exception e) {
            // Silently ignore - setExtBlockMetadata is called very frequently
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
                // LOGGER.warn("NEID arrays are null, skipping sync");
            }
            return;
        }

        MemSlot slot = ((ExtendedBlockStorage) (Object) this).getSlot();

        try {
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

                        slot.setBlockIdAndMeta(x, y, z, truncatedBlockId, metaValue);
                    }
                }
            }

            if (DEBUG && (truncatedBlocks > 0 || truncatedMetaCount > 0)) {
                // LOGGER.info(
                // "Sync complete. Truncated {} extended block IDs and {} extended metadata values",
                // truncatedBlocks,
                // truncatedMetaCount);
            }

        } catch (Exception e) {
            // LOGGER.error("Failed to sync NEID arrays to MemSlot", e);
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
                // LOGGER.warn("Target NEID arrays are null, cannot sync from MemSlot");
                return;
            }

            MemSlot slot = ebs.getSlot();

            if (slot == null) {
                // LOGGER.warn("Target EBS has null MemSlot, cannot sync");
                return;
            }

            byte[] lsb = new byte[4096];
            byte[] msb = new byte[2048];
            byte[] meta = new byte[2048];

            slot.copyLSB(lsb);
            slot.copyMSB(msb);
            slot.copyBlockMetadata(meta);

            // Convert from coordinate-ordered vanilla arrays to NEID coordinate-indexed arrays
            // OPTIMIZATION: Process in coordinate order for both LSB and nibbles
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int coordIndex = y << 8 | z << 4 | x;

                        // LSB is already in coordinate order - direct read
                        int lsbVal = lsb[coordIndex] & 0xFF;

                        // MSB and metadata are nibble arrays - extract 4-bit values
                        int msbVal = get4bitsCoordinate(msb, x, y, z);
                        int metaVal = get4bitsCoordinate(meta, x, y, z);

                        // Combine LSB + MSB into 16-bit block ID
                        targetBlockArray[coordIndex] = (short) ((msbVal << 8) | lsbVal);
                        targetMetaArray[coordIndex] = (short) metaVal;
                    }
                }
            }
        } catch (Exception e) {
            // LOGGER.error("Failed to sync MemSlot to NEID arrays", e);
        }
    }
}
// spotless:on
