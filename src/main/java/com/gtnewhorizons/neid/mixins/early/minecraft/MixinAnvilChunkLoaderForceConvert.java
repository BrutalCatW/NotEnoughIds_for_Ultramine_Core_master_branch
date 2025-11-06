package com.gtnewhorizons.neid.mixins.early.minecraft;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.chunk.storage.AnvilChunkLoader", priority = 1500, remap = false)
public class MixinAnvilChunkLoaderForceConvert {

    private static final Logger LOGGER = LogManager.getLogger("NEID-Ultramine-Load");

    /**
     * LOG loadChunk__Async to understand why chunks are not loading from disk.
     */
    @Inject(method = "loadChunk__Async", at = @At("HEAD"), require = 0, remap = false)
    private void neid$logLoadStart(World world, int x, int z, CallbackInfoReturnable<Object[]> cir) {
        LOGGER.info("=== loadChunk__Async called for chunk ({},{}) ===", x, z);
    }

    @Inject(method = "loadChunk__Async", at = @At("RETURN"), require = 0, remap = false)
    private void neid$logLoadEnd(World world, int x, int z, CallbackInfoReturnable<Object[]> cir) {
        Object[] result = cir.getReturnValue();
        if (result == null) {
            LOGGER.info("=== loadChunk__Async returned NULL for chunk ({},{}) - chunk does not exist! ===", x, z);
        } else {
            LOGGER.info("=== loadChunk__Async returned chunk ({},{}) successfully ===", x, z);
        }
    }

    /**
     * LOG what ultramine reads from disk to understand loading issue.
     */
    @Inject(method = "readChunkFromNBT", at = @At("HEAD"), require = 0, remap = false)
    private void neid$logLoadedData(World world, NBTTagCompound chunkNbt, CallbackInfoReturnable<Chunk> cir) {
        try {
            int x = chunkNbt.getCompoundTag("Level").getInteger("xPos");
            int z = chunkNbt.getCompoundTag("Level").getInteger("zPos");
            NBTTagList sections = chunkNbt.getCompoundTag("Level").getTagList("Sections", 10);

            LOGGER.info(
                    "=== readChunkFromNBT called for chunk ({},{}) with {} sections ===",
                    x,
                    z,
                    sections.tagCount());

            for (int i = 0; i < sections.tagCount(); i++) {
                NBTTagCompound sectionNbt = sections.getCompoundTagAt(i);
                byte yLevel = sectionNbt.getByte("Y");
                boolean isEbsFake = sectionNbt.getClass().getSimpleName().equals("EbsSaveFakeNbt");

                byte[] blocks = sectionNbt.getByteArray("Blocks");
                byte[] blocks16 = sectionNbt.getByteArray("Blocks16");
                byte[] add = sectionNbt.getByteArray("Add");

                StringBuilder blocksPreview = new StringBuilder();
                if (blocks.length > 0) {
                    for (int j = 0; j < Math.min(8, blocks.length); j++) {
                        blocksPreview.append(String.format("%02X ", blocks[j] & 0xFF));
                    }
                } else {
                    blocksPreview.append("EMPTY");
                }

                LOGGER.info(
                        "  Section Y={}, isEbsFake={}, Blocks[{}] (first 8: {}), Blocks16[{}], Add[{}]",
                        yLevel,
                        isEbsFake,
                        blocks.length,
                        blocksPreview.toString().trim(),
                        blocks16.length,
                        add.length);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to log loaded NBT data", e);
        }
    }
}
