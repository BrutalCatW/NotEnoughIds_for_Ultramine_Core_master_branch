package com.gtnewhorizons.neid.mixins.early.minecraft;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.nbt.NBTTagList;

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
     * CRITICAL: Before saving chunk to NBT, sync MemSlot → NEID arrays! ultramine's ExtendedBlockStorage writes
     * directly to MemSlot, NOT to NEID arrays. So NEID arrays may be EMPTY! We must read from MemSlot and populate NEID
     * arrays before vanilla NEID mixi reads them for saving.
     */
    @Inject(method = "writeChunkToNBT", at = @At("HEAD"), require = 0)
    private void neid$syncFromMemSlotBeforeSave(Chunk chunk, World world, NBTTagCompound nbt, CallbackInfo ci) {
        LOGGER.info("Syncing MemSlot → NEID arrays before save for chunk ({}, {})", chunk.xPosition, chunk.zPosition);

        try {
            // Get all ExtendedBlockStorage sections
            java.lang.reflect.Method getBlockStorageArray = chunk.getClass().getMethod("func_76587_i"); // getBlockStorageArray()
            ExtendedBlockStorage[] ebsArray = (ExtendedBlockStorage[]) getBlockStorageArray.invoke(chunk);

            int syncedSections = 0;

            for (int i = 0; i < ebsArray.length; i++) {
                ExtendedBlockStorage ebs = ebsArray[i];
                if (ebs == null) continue;

                // Sync MemSlot → NEID arrays for this section
                syncMemSlotToNeidArrays(ebs);
                syncedSections++;
            }

            LOGGER.info("Synced {} sections from MemSlot to NEID arrays", syncedSections);

        } catch (Exception e) {
            LOGGER.error("Failed to sync MemSlot before save", e);
        }
    }

    /**
     * DO NOT sync after load! Ultramine already loads vanilla "Blocks"/"Add"/"Data" tags directly into MemSlot via
     * slot.setData(). Vanilla NEID redirects DON'T run (no instanceof EbsSaveFakeNbt after disk load), so NEID arrays
     * are EMPTY. Syncing would OVERWRITE correct MemSlot data with empty NEID arrays!
     */

    /**
     * Synchronizes MemSlot data TO NEID arrays for saving. Reads from MemSlot using reflection and writes to NEID
     * arrays.
     */
    private void syncMemSlotToNeidArrays(ExtendedBlockStorage ebs) {
        try {
            IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
            short[] targetBlockArray = ebsMixin.getBlock16BArray();
            short[] targetMetaArray = ebsMixin.getBlock16BMetaArray();

            if (targetBlockArray == null || targetMetaArray == null) {
                LOGGER.warn("NEID arrays are null, cannot sync from MemSlot");
                return;
            }

            // Get MemSlot from the EBS
            java.lang.reflect.Field slotField = ExtendedBlockStorage.class.getDeclaredField("slot");
            slotField.setAccessible(true);
            Object slot = slotField.get(ebs);

            if (slot == null) {
                LOGGER.warn("EBS has null MemSlot, cannot sync");
                return;
            }

            // Get methods from MemSlot
            Class<?> slotClass = slot.getClass();
            java.lang.reflect.Method getBlockIdMethod = slotClass
                    .getMethod("getBlockId", int.class, int.class, int.class);
            java.lang.reflect.Method getMetaMethod = slotClass.getMethod("getMeta", int.class, int.class, int.class);

            int nonAirBlocks = 0;

            // Sync all blocks: MemSlot → NEID arrays (y→z→x order to match index calculation)
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int index = y << 8 | z << 4 | x;
                        int blockId = (int) getBlockIdMethod.invoke(slot, x, y, z);
                        int meta = (int) getMetaMethod.invoke(slot, x, y, z);
                        targetBlockArray[index] = (short) (blockId & 0xFFFF);
                        targetMetaArray[index] = (short) (meta & 0xFFFF);

                        if (blockId != 0) {
                            nonAirBlocks++;
                        }
                    }
                }
            }

            LOGGER.debug("Synced MemSlot→NEID: {} non-air blocks", nonAirBlocks);
        } catch (NoSuchFieldException e) {
            LOGGER.error("MemSlot field not found", e);
        } catch (NoSuchMethodException e) {
            LOGGER.error("Failed to find MemSlot methods", e);
        } catch (Exception e) {
            LOGGER.error("Failed to sync MemSlot to NEID arrays", e);
        }
    }

}
