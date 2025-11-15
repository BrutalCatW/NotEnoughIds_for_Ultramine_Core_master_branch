package com.gtnewhorizons.neid.mixins.early.minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.zip.Deflater;

import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnewhorizons.neid.Constants;

/**
 * Ultramine-specific compatibility mixin for S21PacketChunkData.
 *
 * Intercepts Ultramine's ChunkSnapshot-based packet deflation to convert
 * from Ultramine format (8-bit LSB + 4-bit MSB) to NEID format (16-bit blocks).
 *
 * Priority 1500 ensures it applies after base NEID mixins.
 */
@Mixin(value = S21PacketChunkData.class, priority = 1500)
public abstract class MixinS21PacketChunkDataUltramine {

    private static final Logger LOGGER = LogManager.getLogger("NEID-Ultramine");

    @Shadow private byte[] field_149281_e; // deflated data
    @Shadow private int field_149285_h; // data length
    @Shadow private int field_149280_d; // mask
    @Shadow private int field_149283_c; // mask2

    /**
     * CRITICAL: Intercept deflate() when Ultramine uses ChunkSnapshot!
     *
     * Ultramine's ChunkSnapshot path uses DIFFERENT data format than vanilla:
     * - Ultramine: [LSB all][Meta all][Light all][Sky all if !hasNoSky][MSB all][Biome]
     * - NEID: [Blocks16 all][Meta16 all][Light all][Sky all if !hasNoSky][Biome]
     *
     * This causes "Bad compressed data format" in The End/Space Station because:
     * 1. The End has hasNoSky=true → no SkyLight written
     * 2. MSB written immediately after BlockLight
     * 3. Client expects NEID format → decompression fails!
     */
    @Inject(method = "deflate", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void neid$deflateChunkSnapshotToNeidFormat(CallbackInfo ci) {
        try {
            // Check if this packet uses ChunkSnapshot (Ultramine path)
            Field chunkSnapshotField = S21PacketChunkData.class.getDeclaredField("chunkSnapshot");
            chunkSnapshotField.setAccessible(true);
            Object chunkSnapshot = chunkSnapshotField.get(this);

            if (chunkSnapshot == null) {
                return; // Vanilla path - let original code handle it
            }

            LOGGER.info("==== NEID deflate() INTERCEPT: Converting ChunkSnapshot to NEID format");

            // Extract data from ChunkSnapshot using reflection
            Class<?> snapshotClass = chunkSnapshot.getClass();
            Method getEbsArrMethod = snapshotClass.getMethod("getEbsArr");
            Method isWorldHasNoSkyMethod = snapshotClass.getMethod("isWorldHasNoSky");
            Method getBiomeArrayMethod = snapshotClass.getMethod("getBiomeArray");
            Method getXMethod = snapshotClass.getMethod("getX");
            Method getZMethod = snapshotClass.getMethod("getZ");

            ExtendedBlockStorage[] ebsArray = (ExtendedBlockStorage[]) getEbsArrMethod.invoke(chunkSnapshot);
            boolean hasNoSky = (boolean) isWorldHasNoSkyMethod.invoke(chunkSnapshot);
            byte[] biomeArray = (byte[]) getBiomeArrayMethod.invoke(chunkSnapshot);
            int chunkX = (int) getXMethod.invoke(chunkSnapshot);
            int chunkZ = (int) getZMethod.invoke(chunkSnapshot);

            // Calculate mask
            int ebsMask = 0;
            for (int i = 0; i < ebsArray.length; i++) {
                if (ebsArray[i] != null && !isEbsEmpty(ebsArray[i])) {
                    ebsMask |= 1 << i;
                }
            }

            LOGGER.info("ChunkSnapshot ({},{}) ebsMask=0x{}, hasNoSky={}",
                chunkX, chunkZ, Integer.toHexString(ebsMask), hasNoSky);

            // Create NEID format data
            byte[] neidData = createNeidFormatDataFromSnapshot(ebsArray, ebsMask, hasNoSky, biomeArray);

            // Compress the NEID data
            Deflater deflater = new Deflater(7);
            try {
                deflater.setInput(neidData, 0, neidData.length);
                deflater.finish();

                byte[] deflated = new byte[4096];
                int dataLen = 0;
                while (!deflater.finished()) {
                    if (dataLen == deflated.length) {
                        deflated = java.util.Arrays.copyOf(deflated, deflated.length * 2);
                    }
                    dataLen += deflater.deflate(deflated, dataLen, deflated.length - dataLen);
                }

                // Set fields
                this.field_149281_e = deflated;
                this.field_149285_h = dataLen;
                this.field_149280_d = ebsMask;
                this.field_149283_c = ebsMask;

                LOGGER.info("NEID deflate complete: uncompressed={}, compressed={}", neidData.length, dataLen);

            } finally {
                deflater.end();
            }

            // Release ChunkSnapshot
            Method releaseMethod = snapshotClass.getMethod("release");
            releaseMethod.invoke(chunkSnapshot);
            chunkSnapshotField.set(this, null);

            // Cancel original deflate() code
            ci.cancel();

        } catch (NoSuchFieldException e) {
            // ChunkSnapshot field doesn't exist - not Ultramine or vanilla path
            LOGGER.debug("No chunkSnapshot field - using vanilla path");
        } catch (Exception e) {
            LOGGER.error("Failed to deflate ChunkSnapshot to NEID format", e);
        }
    }

    private static boolean isEbsEmpty(ExtendedBlockStorage ebs) {
        try {
            return (boolean) ebs.getClass().getMethod("isEmpty").invoke(ebs);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Creates NEID format data from ChunkSnapshot's ExtendedBlockStorage array.
     * Format: [Blocks16 all EBS][Meta16 all EBS][BlockLight all][SkyLight all if !hasNoSky][Biome]
     */
    private static byte[] createNeidFormatDataFromSnapshot(ExtendedBlockStorage[] ebsArray, int ebsMask,
            boolean hasNoSky, byte[] biomeArray) {
        try {
            int ebsCount = Integer.bitCount(ebsMask);
            int totalSize = ebsCount * Constants.BYTES_PER_EBS + 256; // +256 for biome

            byte[] data = new byte[totalSize];
            int offset = 0;

            LOGGER.info("Creating NEID format: ebsCount={}, totalSize={}, hasNoSky={}", ebsCount, totalSize, hasNoSky);

            // PHASE 1: Write all Blocks 16-bit (8192 bytes per EBS)
            for (int sectionIndex = 0; sectionIndex < 16; sectionIndex++) {
                if ((ebsMask & (1 << sectionIndex)) == 0) continue;

                ExtendedBlockStorage ebs = ebsArray[sectionIndex];
                Object slot = getSlot(ebs);

                // Read LSB and MSB from MemSlot
                byte[] lsb = new byte[4096];
                byte[] msb = new byte[2048];
                copyFromSlot(slot, "copyLSB", lsb);
                copyFromSlot(slot, "copyMSB", msb);

                // Convert to 16-bit format (big-endian)
                int linearIndex = 0;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int lsbVal = lsb[linearIndex] & 0xFF;
                            int msbVal = get4bits(msb, linearIndex);
                            int blockId = (msbVal << 8) | lsbVal;

                            // Write as big-endian 16-bit
                            data[offset++] = (byte) ((blockId >> 8) & 0xFF);
                            data[offset++] = (byte) (blockId & 0xFF);
                            linearIndex++;
                        }
                    }
                }
            }

            // PHASE 2: Write all Metadata 16-bit (8192 bytes per EBS)
            for (int sectionIndex = 0; sectionIndex < 16; sectionIndex++) {
                if ((ebsMask & (1 << sectionIndex)) == 0) continue;

                ExtendedBlockStorage ebs = ebsArray[sectionIndex];
                Object slot = getSlot(ebs);

                // Read metadata from MemSlot
                byte[] meta = new byte[2048];
                copyFromSlot(slot, "copyBlockMetadata", meta);

                // Convert to 16-bit format (big-endian)
                int linearIndex = 0;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int metaVal = get4bits(meta, linearIndex);

                            // Write as big-endian 16-bit
                            data[offset++] = 0; // MSB always 0
                            data[offset++] = (byte) (metaVal & 0xFF);
                            linearIndex++;
                        }
                    }
                }
            }

            // PHASE 3: Write all BlockLight (2048 bytes per EBS)
            for (int sectionIndex = 0; sectionIndex < 16; sectionIndex++) {
                if ((ebsMask & (1 << sectionIndex)) == 0) continue;

                ExtendedBlockStorage ebs = ebsArray[sectionIndex];
                Object slot = getSlot(ebs);

                copyFromSlot(slot, "copyBlocklight", data, offset);
                offset += 2048;
            }

            // PHASE 4: Write all SkyLight (2048 bytes per EBS) - ONLY if !hasNoSky
            if (!hasNoSky) {
                for (int sectionIndex = 0; sectionIndex < 16; sectionIndex++) {
                    if ((ebsMask & (1 << sectionIndex)) == 0) continue;

                    ExtendedBlockStorage ebs = ebsArray[sectionIndex];
                    Object slot = getSlot(ebs);

                    copyFromSlot(slot, "copySkylight", data, offset);
                    offset += 2048;
                }
            }

            // PHASE 5: Write biome (256 bytes)
            System.arraycopy(biomeArray, 0, data, offset, 256);
            offset += 256;

            LOGGER.info("NEID format created: final offset={}", offset);
            return data;

        } catch (Exception e) {
            LOGGER.error("Failed to create NEID format data from snapshot", e);
            return new byte[0];
        }
    }

    private static Object getSlot(ExtendedBlockStorage ebs) throws Exception {
        java.lang.reflect.Field slotField = ExtendedBlockStorage.class.getDeclaredField("slot");
        slotField.setAccessible(true);
        return slotField.get(ebs);
    }

    private static void copyFromSlot(Object slot, String methodName, byte[] dest) throws Exception {
        Method method = slot.getClass().getMethod(methodName);
        byte[] src = (byte[]) method.invoke(slot);
        System.arraycopy(src, 0, dest, 0, dest.length);
    }

    private static void copyFromSlot(Object slot, String methodName, byte[] dest, int offset) throws Exception {
        Method method = slot.getClass().getMethod(methodName, byte[].class, int.class);
        method.invoke(slot, dest, offset);
    }

    private static int get4bits(byte[] arr, int index) {
        int byteIndex = index >> 1;
        return (index & 1) == 0 ? (arr[byteIndex] & 0xF) : ((arr[byteIndex] >> 4) & 0xF);
    }
}
