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
     * CRITICAL: Load NEID "Blocks16" format after Ultramine loads vanilla format!
     * Ultramine loads vanilla "Blocks"/"Add"/"Data" which only supports 12-bit (4096 blocks).
     * For blocks > 4095 we MUST load "Blocks16" format and write to MemSlot!
     */
    @Inject(method = "readChunkFromNBT", at = @At(value = "RETURN"), require = 0)
    private void neid$loadNeidFormatAfterVanilla(World world, NBTTagCompound nbt, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = cir.getReturnValue();
        if (chunk == null) return;

        LOGGER.info("Loading NEID Blocks16 format for chunk ({}, {})", chunk.xPosition, chunk.zPosition);

        try {
            // Get all ExtendedBlockStorage sections
            java.lang.reflect.Method getBlockStorageArray = chunk.getClass().getMethod("func_76587_i");
            ExtendedBlockStorage[] ebsArray = (ExtendedBlockStorage[]) getBlockStorageArray.invoke(chunk);

            // Get Sections list from NBT
            NBTTagList sectionsList = nbt.getTagList("Sections", 10); // 10 = NBTTagCompound

            for (int i = 0; i < sectionsList.tagCount(); i++) {
                NBTTagCompound sectionNbt = sectionsList.getCompoundTagAt(i);
                int yIndex = sectionNbt.getByte("Y") & 0xFF;

                if (yIndex < 0 || yIndex >= ebsArray.length) continue;
                ExtendedBlockStorage ebs = ebsArray[yIndex];
                if (ebs == null) continue;

                // Check if this section has NEID format
                if (!sectionNbt.hasKey("Blocks16", 7)) continue; // 7 = byte array

                byte[] blocks16 = sectionNbt.getByteArray("Blocks16");
                byte[] data16 = sectionNbt.getByteArray("Data16");

                if (blocks16.length != 8192 || data16.length != 8192) {
                    LOGGER.warn("Invalid Blocks16/Data16 size for section {}", yIndex);
                    continue;
                }

                // Load NEID format into MemSlot
                loadNeidFormatToMemSlot(ebs, blocks16, data16);
                LOGGER.info("Loaded NEID Blocks16 format for section {}", yIndex);
            }

        } catch (Exception e) {
            LOGGER.error("Failed to load NEID format", e);
        }
    }

    /**
     * Loads NEID 16-bit format from NBT arrays into MemSlot.
     */
    private void loadNeidFormatToMemSlot(ExtendedBlockStorage ebs, byte[] blocks16, byte[] data16) {
        try {
            // Get MemSlot
            java.lang.reflect.Field slotField = ExtendedBlockStorage.class.getDeclaredField("slot");
            slotField.setAccessible(true);
            Object slot = slotField.get(ebs);

            if (slot == null) {
                LOGGER.warn("MemSlot is null, cannot load NEID format");
                return;
            }

            Class<?> slotClass = slot.getClass();
            java.lang.reflect.Method setBlockIdMethod = slotClass.getMethod("setBlockId", int.class, int.class, int.class, int.class);
            java.lang.reflect.Method setMetaMethod = slotClass.getMethod("setMeta", int.class, int.class, int.class, int.class);

            // Read NEID 16-bit format and write to MemSlot
            int linearIndex = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        // Read 16-bit block ID (little-endian in NBT)
                        int blockId = (blocks16[linearIndex * 2] & 0xFF) | ((blocks16[linearIndex * 2 + 1] & 0xFF) << 8);

                        // Read 16-bit metadata (little-endian in NBT)
                        int meta = (data16[linearIndex * 2] & 0xFF) | ((data16[linearIndex * 2 + 1] & 0xFF) << 8);

                        // Write to MemSlot
                        setBlockIdMethod.invoke(slot, x, y, z, blockId);
                        setMetaMethod.invoke(slot, x, y, z, meta);

                        linearIndex++;
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("Failed to load NEID format to MemSlot", e);
        }
    }

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
