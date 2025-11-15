package com.gtnewhorizons.neid.mixins.early.minecraft;

import java.lang.reflect.Method;

import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import com.gtnewhorizons.neid.Constants;
import com.gtnewhorizons.neid.mixins.interfaces.IExtendedBlockStorageMixin;

/**
 * Ultramine-specific compatibility mixin for S21PacketChunkData.
 *
 * TWO PATHS TO HANDLE: 1. VANILLA PATH: func_149269_a() - @Overwrite converts MemSlot to 16-bit NEID 2. ULTRAMINE PATH:
 * deflate() → UMHooks.extractAndDeflateChunkPacketData() - @Redirect converts to 16-bit NEID
 *
 * Priority 1500 ensures it applies after base NEID mixins.
 */
@Mixin(value = S21PacketChunkData.class, priority = 1500)
public abstract class MixinS21PacketChunkDataUltramine {

    private static final Logger LOGGER = LogManager.getLogger("NEID-Ultramine");

    @Shadow
    private byte[] field_149281_e; // deflated data

    @Shadow
    private int field_149285_h; // deflated length

    @Shadow
    private int field_149280_d; // section mask

    @Shadow
    private int field_149283_c; // section mask 2

    /**
     * OVERWRITE ultramine's func_149269_a() to send vanilla NEID format (16-bit blocks) instead of ultramine format
     * (8-bit LSB + 4-bit MSB).
     *
     * @author NEID-Ultramine
     * @reason Convert ultramine MemSlot format to vanilla NEID 16-bit format
     */
    @Overwrite
    public static S21PacketChunkData.Extracted func_149269_a(net.minecraft.world.chunk.Chunk chunk, boolean fullChunk,
            int sectionMask) {
        try {
            // DEBUG: Uncomment for debugging
            /*
             * LOGGER.info( "==== NEID func_149269_a() OVERWRITE called! chunk ({},{}), fullChunk={}, mask=0x{}",
             * chunk.xPosition, chunk.zPosition, fullChunk, Integer.toHexString(sectionMask));
             */

            // Use obfuscated names for runtime compatibility
            ExtendedBlockStorage[] ebsArray = (ExtendedBlockStorage[]) chunk.getClass().getMethod("func_76587_i")
                    .invoke(chunk); // getBlockStorageArray()
            S21PacketChunkData.Extracted extracted = new S21PacketChunkData.Extracted();

            if (fullChunk) {
                chunk.getClass().getField("field_76642_o").setBoolean(chunk, true); // sendUpdates
            }

            // Calculate ebsMask
            int ebsMask = 0;
            for (int i = 0; i < ebsArray.length; i++) {
                if (ebsArray[i] != null && (!fullChunk || !isEbsEmpty(ebsArray[i])) && (sectionMask & (1 << i)) != 0) {
                    ebsMask |= 1 << i;
                }
            }

            extracted.field_150280_b = ebsMask; // sectionMask
            extracted.field_150281_c = 0; // NO MSB mask for vanilla NEID

            // Create NEID vanilla format data
            byte[] neidData = createNeidFormatData(ebsArray, ebsMask, fullChunk, chunk);
            extracted.field_150282_a = neidData;

            // DEBUG: Uncomment for debugging
            /*
             * LOGGER.info( "func_149269_a() complete: ebsMask=0x{}, dataSize={}", Integer.toHexString(ebsMask),
             * neidData.length);
             */
            return extracted;

        } catch (Exception e) {
            LOGGER.error("Failed in func_149269_a()", e);
            throw new RuntimeException(e);
        }
    }

    private static boolean isEbsEmpty(ExtendedBlockStorage ebs) throws Exception {
        return (boolean) ebs.getClass().getMethod("func_76663_a").invoke(ebs); // isEmpty()
    }

    private static boolean isWorldHasNoSky(net.minecraft.world.chunk.Chunk chunk) throws Exception {
        Object worldObj = chunk.getClass().getField("field_76637_e").get(chunk); // worldObj
        Object provider = worldObj.getClass().getField("field_73011_w").get(worldObj); // provider
        return (boolean) provider.getClass().getField("field_76576_e").get(provider); // hasNoSky
    }

    /**
     * Creates vanilla NEID format data from MemSlot. Format: [all Blocks 16-bit][all Metadata 16-bit][all
     * BlockLight][all SkyLight][biome]
     */
    private static byte[] createNeidFormatData(ExtendedBlockStorage[] ebsArray, int ebsMask, boolean fullChunk,
            net.minecraft.world.chunk.Chunk chunk) {
        try {
            int ebsCount = Integer.bitCount(ebsMask);
            int totalSize = ebsCount * Constants.BYTES_PER_EBS;

            if (fullChunk) {
                totalSize += 256; // Biome array
            }

            byte[] data = new byte[totalSize];
            int offset = 0;

            // DEBUG: Uncomment for debugging
            // LOGGER.info("Creating NEID format: ebsCount={}, totalSize={}", ebsCount, totalSize);

            // PHASE 1: Write all blocks (16-bit, 8192 bytes per EBS) - GROUPED
            for (int sectionIndex = 0; sectionIndex < 16; sectionIndex++) {
                if ((ebsMask & (1 << sectionIndex)) == 0) continue;

                ExtendedBlockStorage ebs = ebsArray[sectionIndex];
                Object slot = getSlot(ebs);

                // Read LSB (4096 bytes) and MSB (2048 bytes) from MemSlot
                byte[] lsb = new byte[4096];
                byte[] msb = new byte[2048];
                copyFromSlot(slot, "copyLSB", lsb);
                copyFromSlot(slot, "copyMSB", msb);

                // DEBUG: Uncomment for debugging LSB bytes
                /*
                 * StringBuilder lsbDebug = new StringBuilder(); for (int i = 0; i < Math.min(32, lsb.length); i++) {
                 * lsbDebug.append(String.format("%02X ", lsb[i] & 0xFF)); } LOGGER.info(
                 * "EBS section={}, slot={}, first 32 LSB bytes: {}", sectionIndex, slot.getClass().getSimpleName(),
                 * lsbDebug.toString());
                 */

                // DEBUG: Uncomment for block counting
                // int nonZeroBlocks = 0;
                // int blocksWithMSB = 0;

                // CRITICAL: Write in LINEAR order (matching client's ShortBuffer.put())
                // Index calculation y << 8 | z << 4 | x creates sequential indices 0,1,2,3...
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int index = y << 8 | z << 4 | x;
                            int lsbVal = lsb[index] & 0xFF;
                            int msbVal = get4bitsCoordinate(msb, x, y, z);
                            int blockId = (msbVal << 8) | lsbVal;

                            // DEBUG: Uncomment for block counting
                            /*
                             * if (blockId != 0) { nonZeroBlocks++; if (msbVal != 0) blocksWithMSB++; }
                             */

                            // Write as big-endian 16-bit
                            data[offset++] = (byte) ((blockId >> 8) & 0xFF);
                            data[offset++] = (byte) (blockId & 0xFF);
                        }
                    }
                }

                // DEBUG: Uncomment for logging
                // LOGGER.info("EBS section={}: nonZero={}, withMSB={}", sectionIndex, nonZeroBlocks, blocksWithMSB);
            }

            // PHASE 2: Write all metadata (16-bit, 8192 bytes per EBS) - GROUPED
            for (int sectionIndex = 0; sectionIndex < 16; sectionIndex++) {
                if ((ebsMask & (1 << sectionIndex)) == 0) continue;

                ExtendedBlockStorage ebs = ebsArray[sectionIndex];
                Object slot = getSlot(ebs);

                // Read metadata (2048 bytes 4-bit nibbles) from MemSlot
                byte[] meta = new byte[2048];
                copyFromSlot(slot, "copyBlockMetadata", meta);

                // CRITICAL: Convert 4-bit nibbles to 16-bit using coordinate-based reading!
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int metaVal = get4bitsCoordinate(meta, x, y, z);

                            // Write as big-endian 16-bit
                            data[offset++] = 0; // MSB always 0
                            data[offset++] = (byte) (metaVal & 0xFF);
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

            // PHASE 4: Write all SkyLight (2048 bytes per EBS)
            if (!isWorldHasNoSky(chunk)) {
                for (int sectionIndex = 0; sectionIndex < 16; sectionIndex++) {
                    if ((ebsMask & (1 << sectionIndex)) == 0) continue;

                    ExtendedBlockStorage ebs = ebsArray[sectionIndex];
                    Object slot = getSlot(ebs);

                    copyFromSlot(slot, "copySkylight", data, offset);
                    offset += 2048;
                }
            }

            // PHASE 5: Write biome (256 bytes if full chunk)
            if (fullChunk) {
                byte[] biomeArray = (byte[]) chunk.getClass().getMethod("func_76605_m").invoke(chunk); // getBiomeArray()
                System.arraycopy(biomeArray, 0, data, offset, 256);
                offset += 256;
            }

            // DEBUG: Uncomment for debugging
            // LOGGER.info("First 32 bytes: {}", bytesToHex(data, 0, 32));
            return data;

        } catch (Exception e) {
            LOGGER.error("Failed to create NEID format data", e);
            return new byte[0];
        }
    }

    private static Object getSlot(ExtendedBlockStorage ebs) throws Exception {
        Method getSlotMethod = ebs.getClass().getMethod("getSlot");
        return getSlotMethod.invoke(ebs);
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

    /**
     * Reads 4-bit nibble from coordinate-ordered nibble array. ultramine nibbles are packed in coordinate order: y << 8
     * | z << 4 | x
     */
    private static int get4bitsCoordinate(byte[] arr, int x, int y, int z) {
        int index = y << 8 | z << 4 | x;
        int byteIndex = index >> 1;
        return (index & 1) == 0 ? (arr[byteIndex] & 0xF) : ((arr[byteIndex] >> 4) & 0xF);
    }

    private static String bytesToHex(byte[] bytes, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + length && i < bytes.length; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString();
    }

    /**
     * ULTRAMINE PATH INJECT: Override deflate() to use NEID 16-bit format instead of ultramine's 8-bit format!
     *
     * This handles the ChunkSendManager async path: makeForSend(ChunkSnapshot) → deflate()
     */
    @org.spongepowered.asm.mixin.injection.Inject(
            method = "deflate",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
            remap = false,
            cancellable = true,
            require = 0)
    private void neid$ultramineDeflateOverride(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // Check if this is ultramine path (chunkSnapshot field exists)
        Object chunkSnapshot = null;
        try {
            java.lang.reflect.Field chunkSnapshotField = S21PacketChunkData.class.getDeclaredField("chunkSnapshot");
            chunkSnapshotField.setAccessible(true);
            chunkSnapshot = chunkSnapshotField.get(this);
        } catch (Exception e) {
            // Not ultramine path, let vanilla/other path handle it
            return;
        }

        if (chunkSnapshot == null) {
            // Not ultramine path
            return;
        }

        // This IS ultramine path - cancel original and do our NEID 16-bit packing!
        ci.cancel();
        // DEBUG: Uncomment for debugging
        // LOGGER.info("@@@ INJECT deflate() - converting ChunkSnapshot to NEID 16-bit!");

        java.util.zip.Deflater deflater = new java.util.zip.Deflater(7);
        // DEBUG: Uncomment for debugging
        // LOGGER.info("[DEFLATE] Step 1: Created Deflater");
        try {
            // Get ExtendedBlockStorage[] from ChunkSnapshot
            ExtendedBlockStorage[] ebsArr = (ExtendedBlockStorage[]) chunkSnapshot.getClass().getMethod("getEbsArr")
                    .invoke(chunkSnapshot);
            // DEBUG: Uncomment for debugging
            // LOGGER.info("[DEFLATE] Step 2: Got ebsArr, length={}", ebsArr != null ? ebsArr.length : "null");

            // Calculate mask
            int mask = 0;
            for (int i = 0; i < ebsArr.length; ++i) {
                ExtendedBlockStorage ebs = ebsArr[i];
                if (ebs != null && !ebs.isEmpty()) mask |= 1 << i;
            }
            // DEBUG: Uncomment for debugging
            // LOGGER.info("[DEFLATE] Step 3: Calculated mask=0x{}", Integer.toHexString(mask));

            if (mask == 0) {
                // Empty chunk
                // DEBUG: Uncomment for debugging
                // LOGGER.info("[DEFLATE] Step 4: Empty chunk, returning");
                byte[] EMPTY_CHUNK_SEQUENCE = { 120, -38, -19, -65, 49, 1, 0, 0, 0, -62, -96, -11, 79, 109, 13, 15, -96,
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -128, 119, 3, 48, 0, 0, 1 };
                this.field_149281_e = EMPTY_CHUNK_SEQUENCE;
                this.field_149285_h = EMPTY_CHUNK_SEQUENCE.length;
                this.field_149280_d = 1;
                this.field_149283_c = 1;
                return;
            }

            // Create NEID 16-bit format data
            int ebsCount = Integer.bitCount(mask);
            // DEBUG: Uncomment for debugging
            // LOGGER.info("[DEFLATE] Step 4: ebsCount={}", ebsCount);
            boolean hasNoSky = (boolean) chunkSnapshot.getClass().getMethod("isWorldHasNoSky").invoke(chunkSnapshot);
            // DEBUG: Uncomment for debugging
            // LOGGER.info("[DEFLATE] Step 5: hasNoSky={}", hasNoSky);
            byte[] biomeArray = (byte[]) chunkSnapshot.getClass().getMethod("getBiomeArray").invoke(chunkSnapshot);
            // DEBUG: Uncomment for debugging
            // LOGGER.info("[DEFLATE] Step 6: biomeArray.length={}", biomeArray != null ? biomeArray.length : "null");

            int totalSize = ebsCount * Constants.BYTES_PER_EBS + biomeArray.length;
            // DEBUG: Uncomment for debugging
            // LOGGER.info("[DEFLATE] Step 7: totalSize={}", totalSize);
            byte[] data = new byte[totalSize];
            int offset = 0;

            // DEBUG: Uncomment for debugging
            /*
             * LOGGER.info( "INJECT deflate(): Creating NEID 16-bit format - ebsCount={}, mask=0x{}", ebsCount,
             * Integer.toHexString(mask));
             */

            // PHASE 1: Write all 16-bit blocks
            for (int i = 0; i < ebsArr.length; i++) {
                if ((mask & (1 << i)) == 0) continue;
                ExtendedBlockStorage ebs = ebsArr[i];

                IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
                short[] blockArray = ebsMixin.getBlock16BArray();

                if (blockArray != null) {
                    for (int j = 0; j < 4096; j++) {
                        int blockId = blockArray[j] & 0xFFFF;
                        data[offset++] = (byte) ((blockId >> 8) & 0xFF);
                        data[offset++] = (byte) (blockId & 0xFF);
                    }
                } else {
                    LOGGER.warn("Block16BArray is null for EBS {}, using zeros", i);
                    offset += 8192;
                }
            }

            // PHASE 2: Write all 16-bit metadata
            for (int i = 0; i < ebsArr.length; i++) {
                if ((mask & (1 << i)) == 0) continue;
                ExtendedBlockStorage ebs = ebsArr[i];

                IExtendedBlockStorageMixin ebsMixin = (IExtendedBlockStorageMixin) ebs;
                short[] metaArray = ebsMixin.getBlock16BMetaArray();

                if (metaArray != null) {
                    for (int j = 0; j < 4096; j++) {
                        int meta = metaArray[j] & 0xFFFF;
                        data[offset++] = (byte) ((meta >> 8) & 0xFF);
                        data[offset++] = (byte) (meta & 0xFF);
                    }
                } else {
                    LOGGER.warn("Block16BMetaArray is null for EBS {}, using zeros", i);
                    offset += 8192;
                }
            }

            // PHASE 3: Write BlockLight from MemSlot
            for (int i = 0; i < ebsArr.length; i++) {
                if ((mask & (1 << i)) == 0) continue;
                ExtendedBlockStorage ebs = ebsArr[i];
                Object slot = getSlot(ebs);
                copyFromSlot(slot, "copyBlocklight", data, offset);
                offset += 2048;
            }

            // PHASE 4: Write SkyLight from MemSlot
            if (!hasNoSky) {
                for (int i = 0; i < ebsArr.length; i++) {
                    if ((mask & (1 << i)) == 0) continue;
                    ExtendedBlockStorage ebs = ebsArr[i];
                    Object slot = getSlot(ebs);
                    copyFromSlot(slot, "copySkylight", data, offset);
                    offset += 2048;
                }
            }

            // PHASE 5: Write biome
            System.arraycopy(biomeArray, 0, data, offset, biomeArray.length);

            // Deflate the data
            deflater.setInput(data, 0, data.length);
            deflater.finish();

            byte[] deflated = new byte[4096];
            int deflatedLen = 0;
            while (!deflater.finished()) {
                if (deflatedLen == deflated.length) deflated = java.util.Arrays.copyOf(deflated, deflated.length * 2);
                deflatedLen += deflater.deflate(deflated, deflatedLen, deflated.length - deflatedLen);
            }

            // Release snapshot
            chunkSnapshot.getClass().getMethod("release").invoke(chunkSnapshot);

            // Set deflated data to this packet
            this.field_149281_e = deflated;
            this.field_149285_h = deflatedLen;
            this.field_149280_d = mask;
            this.field_149283_c = mask;

            // DEBUG: Uncomment for debugging
            /*
             * LOGGER.info( "INJECT deflate() complete: rawSize={}, deflatedSize={}, mask=0x{}", data.length,
             * deflatedLen, Integer.toHexString(mask));
             */

        } catch (Exception e) {
            LOGGER.error("INJECT deflate() FAILED!", e);
            // Set empty data to avoid crash
            this.field_149281_e = new byte[0];
            this.field_149285_h = 0;
        } finally {
            deflater.end();
        }
    }
}