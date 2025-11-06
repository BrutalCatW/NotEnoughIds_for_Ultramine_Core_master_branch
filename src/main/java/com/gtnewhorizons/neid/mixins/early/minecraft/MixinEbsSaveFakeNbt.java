package com.gtnewhorizons.neid.mixins.early.minecraft;

import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ultramine-specific compatibility mixin for EbsSaveFakeNbt. This mixin intercepts ultramine's optimized chunk saving
 * to ensure MemSlot data is synced to NEID arrays before writing, allowing vanilla NEID redirects to work.
 *
 * Priority 1500 ensures it applies after other mixins.
 */
@Mixin(targets = "net.minecraft.nbt.EbsSaveFakeNbt", priority = 1500, remap = false)
public class MixinEbsSaveFakeNbt {

    private static final Logger LOGGER = LogManager.getLogger("NEID-Ultramine");

    @Shadow
    @Final
    private ExtendedBlockStorage ebs;

    @Shadow
    @Final
    private boolean hasNoSky;

    @Shadow
    private volatile boolean isNbt;

    /**
     * CRITICAL OVERWRITE: Replace ultramine's convertToNbt() to write NEID 16-bit format instead of vanilla 8-bit!
     *
     * Ultramine's convertToNbt() calls setByteArray("Blocks", slot.copyLSB()) which writes vanilla 8-bit format. NEID
     * redirects only work in writeChunkToNBT method, NOT inside EbsSaveFakeNbt methods!
     *
     * So we must manually write NEID 16-bit format here.
     *
     * @author NEID-Ultramine
     * @reason Write NEID 16-bit format instead of vanilla 8-bit
     */
    @Overwrite(remap = false)
    public void convertToNbt() {
        if (isNbt) return;

        LOGGER.info("OVERWRITE convertToNbt: Writing NEID 16-bit format");

        try {
            // Create the map for NBT tags
            java.lang.reflect.Method createMapMethod = this.getClass().getSuperclass()
                    .getDeclaredMethod("createMap", int.class);
            createMapMethod.setAccessible(true);
            createMapMethod.invoke(this, 0);
        } catch (Exception e) {
            LOGGER.error("Failed to create NBT map", e);
            return;
        }

        // Write Y position
        ((net.minecraft.nbt.NBTTagCompound) (Object) this).setByte("Y", (byte) (ebs.getYLocation() >> 4 & 255));

        // CRITICAL: Ultramine's ExtendedBlockStorage.setBlockId() writes DIRECTLY to MemSlot, NOT to NEID arrays!
        // NEID arrays are ALWAYS EMPTY with ultramine. Must read DIRECTLY from MemSlot, like original write() does.

        try {
            // Get MemSlot
            java.lang.reflect.Field slotField = ExtendedBlockStorage.class.getDeclaredField("slot");
            slotField.setAccessible(true);
            Object slot = slotField.get(ebs);

            if (slot == null) {
                LOGGER.error("MemSlot is null, cannot write NBT!");
                return;
            }

            Class<?> slotClass = slot.getClass();

            // Read vanilla format arrays from MemSlot (like original EbsSaveFakeNbt.write())
            java.lang.reflect.Method copyLSBMethod = slotClass.getMethod("copyLSB");
            java.lang.reflect.Method copyMSBMethod = slotClass.getMethod("copyMSB");
            java.lang.reflect.Method copyMetaMethod = slotClass.getMethod("copyBlockMetadata");

            byte[] lsbData = (byte[]) copyLSBMethod.invoke(slot);
            byte[] msbData = (byte[]) copyMSBMethod.invoke(slot);
            byte[] vanillaMetaData = (byte[]) copyMetaMethod.invoke(slot);

            // Count non-zero blocks to verify MemSlot has data
            int nonZeroBlocks = 0;
            for (int i = 0; i < lsbData.length; i++) {
                if (lsbData[i] != 0) nonZeroBlocks++;
            }

            // Write vanilla format (REQUIRED for ultramine to load)
            ((net.minecraft.nbt.NBTTagCompound) (Object) this).setByteArray("Blocks", lsbData);
            ((net.minecraft.nbt.NBTTagCompound) (Object) this).setByteArray("Add", msbData);
            ((net.minecraft.nbt.NBTTagCompound) (Object) this).setByteArray("Data", vanillaMetaData);

            LOGGER.info(
                    "Wrote vanilla format from MemSlot: Blocks={} (first 4: {} {} {} {}), Add={}, Data={}, nonZero={}",
                    lsbData.length,
                    lsbData[0] & 0xFF,
                    lsbData[1] & 0xFF,
                    lsbData[2] & 0xFF,
                    lsbData[3] & 0xFF,
                    msbData.length,
                    vanillaMetaData.length,
                    nonZeroBlocks);

            // Also convert to NEID 16-bit format for compatibility
            byte[] blocks16 = new byte[4096 * 2];
            byte[] data16 = new byte[4096 * 2];

            for (int i = 0; i < 4096; i++) {
                int y = (i >> 8) & 0xF;
                int z = (i >> 4) & 0xF;
                int x = i & 0xF;

                // Get block ID from LSB + MSB
                int lsb = lsbData[i] & 0xFF;
                int msb = get4bitsCoordinate(msbData, x, y, z);
                int blockId = (msb << 8) | lsb;

                // Get metadata
                int meta = get4bitsCoordinate(vanillaMetaData, x, y, z);

                // Write as 16-bit (little-endian)
                blocks16[i * 2] = (byte) (blockId & 0xFF);
                blocks16[i * 2 + 1] = (byte) ((blockId >> 8) & 0xFF);
                data16[i * 2] = (byte) (meta & 0xFF);
                data16[i * 2 + 1] = 0;
            }

            ((net.minecraft.nbt.NBTTagCompound) (Object) this).setByteArray("Blocks16", blocks16);
            ((net.minecraft.nbt.NBTTagCompound) (Object) this).setByteArray("Data16", data16);

        } catch (Exception e) {
            LOGGER.error("Failed to read/write block data from MemSlot", e);
            return;
        }

        // Write lighting data from MemSlot (these are fine as-is)
        try {
            java.lang.reflect.Field slotField = ExtendedBlockStorage.class.getDeclaredField("slot");
            slotField.setAccessible(true);
            Object slot = slotField.get(ebs);
            Class<?> slotClass = slot.getClass();

            java.lang.reflect.Method copyBlocklightMethod = slotClass.getMethod("copyBlocklight");
            byte[] blockLight = (byte[]) copyBlocklightMethod.invoke(slot);
            ((net.minecraft.nbt.NBTTagCompound) (Object) this).setByteArray("BlockLight", blockLight);

            if (hasNoSky) {
                ((net.minecraft.nbt.NBTTagCompound) (Object) this).setByteArray("SkyLight", new byte[2048]);
            } else {
                java.lang.reflect.Method copySkylightMethod = slotClass.getMethod("copySkylight");
                byte[] skyLight = (byte[]) copySkylightMethod.invoke(slot);
                ((net.minecraft.nbt.NBTTagCompound) (Object) this).setByteArray("SkyLight", skyLight);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write lighting data", e);
        }

        isNbt = true;
        LOGGER.info("Successfully wrote NEID 16-bit format in convertToNbt");
    }

    /**
     * CRITICAL INJECT: Force conversion to NBT immediately after construction!
     */
    @Inject(method = "<init>", at = @At("RETURN"), require = 0, remap = false)
    private void neid$forceConvertAfterInit(ExtendedBlockStorage ebs, boolean hasNoSky, CallbackInfo ci) {
        LOGGER.info(
                "INJECT <init>: Force converting to NEID NBT format immediately. EBS={}, hasNoSky={}, Thread={}",
                ebs != null ? "present" : "null",
                hasNoSky,
                Thread.currentThread().getName());
        convertToNbt();
        LOGGER.info("INJECT <init>: Completed convertToNbt(), isNbt={}", isNbt);
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
}
