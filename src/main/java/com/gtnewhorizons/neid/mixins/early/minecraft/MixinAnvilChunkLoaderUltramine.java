package com.gtnewhorizons.neid.mixins.early.minecraft;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;

/**
 * Ultramine-specific compatibility mixin for AnvilChunkLoader. This mixin handles synchronization between NEID's 16-bit
 * block arrays and ultramine_core's off-heap MemSlot storage during world save/load operations.
 *
 * Priority 1500 ensures it applies after the base NEID mixin (default 1000).
 */
@Mixin(value = AnvilChunkLoader.class, priority = 1500)
public class MixinAnvilChunkLoaderUltramine {

    private static final Logger LOGGER = LogManager.getLogger("NEID-Ultramine");

    /**
     * DO NOT sync MemSlot → NEID arrays before save!
     *
     * Reason: MemSlot only stores 4-bit metadata, but NEID arrays store full 16-bit metadata. Syncing would OVERWRITE
     * 16-bit metadata with 4-bit values, losing extended metadata!
     *
     * NEID arrays are already populated: 1. Worldgen chunks: removeInvalidBlocks() syncs MemSlot→NEID at load time 2.
     * Modified chunks: setBlockId/setExtBlockMetadata write directly to NEID arrays
     *
     * So we can safely read from NEID arrays without syncing from MemSlot!
     */

    /**
     * CRITICAL: After loading chunk from NBT, load extended data from "Blocks16" and "Data16"!
     *
     * Problem: Ultramine loads vanilla "Blocks"/"Add"/"Data" (12-bit block IDs, 4-bit metadata) into MemSlot,
     * bypassing NEID's @Redirect. The base NEID @Inject in removeInvalidBlocks() syncs MemSlot→NEID arrays,
     * but this TRUNCATES block IDs > 4095 and metadata > 15!
     *
     * Solution: After vanilla load, we override NEID arrays with "Blocks16" (full 16-bit block IDs) and
     * "Data16" (full 16-bit metadata) from NBT, restoring extended values.
     *
     * This fixes the issue where blocks with ID > 4095 (e.g., Et Futurum flowers) turn into wrong blocks
     * (e.g., plutonium) after world reload.
     */
    @Inject(method = "readChunkFromNBT", at = @At("RETURN"), require = 0)
    private void neid$loadExtendedMetadataAfterLoad(net.minecraft.world.World world,
            net.minecraft.nbt.NBTTagCompound nbt,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = cir.getReturnValue();
        if (chunk == null) return;

        // DEBUG: Uncomment for debugging
        // LOGGER.info("Loading extended metadata from Data16 for chunk ({}, {})", chunk.xPosition, chunk.zPosition);

        try {
            net.minecraft.nbt.NBTTagList sectionList = nbt.getTagList("Sections", 10);
            ExtendedBlockStorage[] ebsArray = chunk.getBlockStorageArray();
            // DEBUG: Uncomment for counting loaded sections
            // int loadedSections = 0;

            for (int i = 0; i < sectionList.tagCount(); i++) {
                net.minecraft.nbt.NBTTagCompound sectionNbt = sectionList.getCompoundTagAt(i);
                byte yLevel = sectionNbt.getByte("Y");

                if (yLevel >= 0 && yLevel < ebsArray.length && ebsArray[yLevel] != null) {
                    ExtendedBlockStorage ebs = ebsArray[yLevel];
                    IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;

                    // CRITICAL: Load "Blocks16" FIRST (16-bit extended block IDs)
                    // Without this, blocks with ID > 4095 will be truncated!
                    if (sectionNbt.hasKey("Blocks16")) {
                        byte[] blocks16 = sectionNbt.getByteArray("Blocks16");
                        ebsMixin.setBlockData(blocks16, 0);
                        // DEBUG: Uncomment for debugging
                        // LOGGER.debug("Loaded Blocks16 for section Y={}, length={}", yLevel, blocks16.length);
                    }

                    // Load "Data16" if present (16-bit extended metadata)
                    if (sectionNbt.hasKey("Data16")) {
                        byte[] data16 = sectionNbt.getByteArray("Data16");
                        ebsMixin.setBlockMeta(data16, 0);
                        // DEBUG: Uncomment for counting
                        // loadedSections++;
                        // DEBUG: Uncomment for debugging
                        // LOGGER.debug("Loaded Data16 for section Y={}, length={}", yLevel, data16.length);
                    }
                }
            }

            // DEBUG: Uncomment for debugging
            // LOGGER.info("Loaded extended metadata for {} sections", loadedSections);

        } catch (Exception e) {
            // LOGGER.error("Failed to load extended metadata", e);
        }
    }

}
