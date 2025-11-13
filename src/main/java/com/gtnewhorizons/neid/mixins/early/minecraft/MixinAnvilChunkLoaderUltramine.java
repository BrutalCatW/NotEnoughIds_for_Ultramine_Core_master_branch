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
     * CRITICAL: After loading chunk from NBT, load extended metadata from "Data16"! Ultramine loads vanilla
     * "Blocks"/"Add"/"Data" (4-bit metadata) into MemSlot, bypassing NEID's @Redirect. The base NEID @Inject in
     * removeInvalidBlocks() will sync MemSlot→NEID arrays (4-bit metadata). Then we must load "Data16" to restore
     * extended (16-bit) metadata into NEID arrays!
     */
    @Inject(method = "readChunkFromNBT", at = @At("RETURN"), require = 0)
    private void neid$loadExtendedMetadataAfterLoad(net.minecraft.world.World world,
            net.minecraft.nbt.NBTTagCompound nbt,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = cir.getReturnValue();
        if (chunk == null) return;

        LOGGER.info("Loading extended metadata from Data16 for chunk ({}, {})", chunk.xPosition, chunk.zPosition);

        try {
            net.minecraft.nbt.NBTTagList sectionList = nbt.getTagList("Sections", 10);
            ExtendedBlockStorage[] ebsArray = chunk.getBlockStorageArray();
            int loadedSections = 0;

            for (int i = 0; i < sectionList.tagCount(); i++) {
                net.minecraft.nbt.NBTTagCompound sectionNbt = sectionList.getCompoundTagAt(i);
                byte yLevel = sectionNbt.getByte("Y");

                if (yLevel >= 0 && yLevel < ebsArray.length && ebsArray[yLevel] != null) {
                    ExtendedBlockStorage ebs = ebsArray[yLevel];

                    // Load "Data16" if present (16-bit extended metadata)
                    if (sectionNbt.hasKey("Data16")) {
                        IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
                        byte[] data16 = sectionNbt.getByteArray("Data16");
                        ebsMixin.setBlockMeta(data16, 0);
                        loadedSections++;
                        LOGGER.debug("Loaded Data16 for section Y={}, length={}", yLevel, data16.length);
                    }
                }
            }

            LOGGER.info("Loaded extended metadata for {} sections", loadedSections);

        } catch (Exception e) {
            LOGGER.error("Failed to load extended metadata", e);
        }
    }

}
