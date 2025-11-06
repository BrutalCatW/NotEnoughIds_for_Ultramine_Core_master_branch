package com.gtnewhorizons.neid.mixins.early.minecraft;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(targets = "net.minecraft.world.chunk.storage.AnvilChunkLoader", priority = 1500, remap = false)
public class MixinAnvilChunkLoaderAsyncDebug {

    private static final Logger LOGGER = LogManager.getLogger("NEID-Ultramine-AsyncDebug");

    @Inject(
            method = "loadChunk__Async",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/chunk/storage/AnvilChunkLoader;checkedReadChunkFromNBT(Lnet/minecraft/world/World;IILnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraft/world/chunk/Chunk;",
                    remap = false),
            locals = LocalCapture.CAPTURE_FAILHARD,
            require = 0,
            remap = false)
    private void neid$logBeforeRead(World world, int x, int z, CallbackInfoReturnable<Object[]> cir,
            NBTTagCompound nbttagcompound) {
        if (nbttagcompound.getClass().getSimpleName().contains("EbsSaveFakeNbt")) {
            LOGGER.info("loadChunk__Async for ({},{}) got NBT from pendingSaves (EbsSaveFakeNbt in memory)", x, z);
        } else {
            LOGGER.info("loadChunk__Async for ({},{}) got NBT from DISK (regular NBTTagCompound)", x, z);
        }
    }
}
