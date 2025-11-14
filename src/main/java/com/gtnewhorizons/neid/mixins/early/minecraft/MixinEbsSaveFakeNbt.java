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

        // DEBUG: Uncomment for debugging
        // LOGGER.info("OVERWRITE convertToNbt: Writing NEID 16-bit format");

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

        // CRITICAL FIX: When EbsSaveFakeNbt is created from copy(), the MemSlot is RELEASED!
        // But syncMemSlotToNeidArrays() has already populated NEID arrays before release.
        // So we MUST read from NEID arrays, NOT from released MemSlot!

        try {
            // CRITICAL FIX: Read from NEID arrays, NOT from released MemSlot!
            // When copy() is called, MemSlot is released but NEID arrays are populated by syncMemSlotToNeidArrays()
            com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin ebsMixin = (com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin) ebs;
            short[] block16BArray = ebsMixin.getBlock16BArray();
            short[] block16BMetaArray = ebsMixin.getBlock16BMetaArray();

            if (block16BArray == null || block16BMetaArray == null) {
                LOGGER.error("NEID arrays are null, cannot write NBT!");
                return;
            }

            // Convert NEID 16-bit arrays to vanilla 8-bit format for ultramine
            byte[] lsbData = new byte[4096];
            byte[] msbData = new byte[2048];
            byte[] vanillaMetaData = new byte[2048];

            // Convert from NEID 16-bit coordinate arrays to vanilla 8-bit coordinate arrays
            int nonZeroBlocks = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        // Read from NEID arrays (coordinate order: y<<8|z<<4|x)
                        int coordIndex = y << 8 | z << 4 | x;
                        int blockId = block16BArray[coordIndex] & 0xFFFF;
                        int meta = block16BMetaArray[coordIndex] & 0xFFFF;

                        if (blockId != 0) nonZeroBlocks++;

                        // Write to vanilla arrays (same coordinate order)
                        lsbData[coordIndex] = (byte) (blockId & 0xFF);
                        set4bitsCoordinate(msbData, x, y, z, (blockId >> 8) & 0xF);
                        set4bitsCoordinate(vanillaMetaData, x, y, z, meta & 0xF);
                    }
                }
            }

            // Write vanilla format (REQUIRED for ultramine to load)
            ((net.minecraft.nbt.NBTTagCompound) (Object) this).setByteArray("Blocks", lsbData);
            ((net.minecraft.nbt.NBTTagCompound) (Object) this).setByteArray("Add", msbData);
            ((net.minecraft.nbt.NBTTagCompound) (Object) this).setByteArray("Data", vanillaMetaData);

            // DEBUG: Uncomment for debugging
            /*
             * LOGGER.info(
             * "Wrote vanilla format from NEID arrays: Blocks={} (first 4: {} {} {} {}), Add={}, Data={}, nonZero={}",
             * lsbData.length, lsbData[0] & 0xFF, lsbData[1] & 0xFF, lsbData[2] & 0xFF, lsbData[3] & 0xFF,
             * msbData.length, vanillaMetaData.length, nonZeroBlocks);
             */

            // Also write NEID 16-bit format (uses linear order for ByteBuffer compatibility)
            byte[] blocks16 = new byte[4096 * 2];
            byte[] data16 = new byte[4096 * 2];

            int linearIndex = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int coordIndex = y << 8 | z << 4 | x;
                        int blockId = block16BArray[coordIndex] & 0xFFFF;
                        int meta = block16BMetaArray[coordIndex] & 0xFFFF;

                        // Write as 16-bit (BIG-ENDIAN) for ByteBuffer.wrap().asShortBuffer()
                        // ByteBuffer.wrap() uses BIG_ENDIAN by default, so we write MSB first
                        blocks16[linearIndex * 2] = (byte) ((blockId >> 8) & 0xFF);
                        blocks16[linearIndex * 2 + 1] = (byte) (blockId & 0xFF);
                        data16[linearIndex * 2] = (byte) ((meta >> 8) & 0xFF);
                        data16[linearIndex * 2 + 1] = (byte) (meta & 0xFF);
                        linearIndex++;
                    }
                }
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
        // DEBUG: Uncomment for debugging
        // LOGGER.info("Successfully wrote NEID 16-bit format in convertToNbt");
    }

    /**
     * CRITICAL INJECT: Force conversion to NBT immediately after construction!
     */
    @Inject(method = "<init>", at = @At("RETURN"), require = 0, remap = false)
    private void neid$forceConvertAfterInit(ExtendedBlockStorage ebs, boolean hasNoSky, CallbackInfo ci) {
        // DEBUG: Uncomment for debugging
        /*
         * LOGGER.info(
         * "INJECT <init>: Force converting to NEID NBT format immediately. EBS={}, hasNoSky={}, Thread={}", ebs != null
         * ? "present" : "null", hasNoSky, Thread.currentThread().getName());
         */
        convertToNbt();
        // DEBUG: Uncomment for debugging
        // LOGGER.info("INJECT <init>: Completed convertToNbt(), isNbt={}", isNbt);
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
     * Writes a 4-bit nibble to coordinate-ordered nibble array. Ultramine nibble arrays are packed in coordinate order:
     * y << 8 | z << 4 | x
     */
    private static void set4bitsCoordinate(byte[] arr, int x, int y, int z, int value) {
        int ind = y << 8 | z << 4 | x;
        int nibbleIndex = ind >> 1;
        if ((ind & 1) == 0) {
            arr[nibbleIndex] = (byte) ((arr[nibbleIndex] & 0xF0) | (value & 0xF));
        } else {
            arr[nibbleIndex] = (byte) ((arr[nibbleIndex] & 0x0F) | ((value & 0xF) << 4));
        }
    }
}
