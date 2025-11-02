package com.gtnewhorizons.neid.mixins.early.minecraft;

import java.lang.reflect.Method;

import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import com.gtnewhorizons.neid.Constants;

/**
 * Ultramine-specific compatibility mixin for S21PacketChunkData.
 *
 * ultramine_core uses MemSlot with 8-bit LSB + 4-bit MSB format. We need to convert this to vanilla NEID 16-bit format
 * for the client.
 *
 * Priority 1500 ensures it applies after base NEID mixins.
 */
@Mixin(value = S21PacketChunkData.class, priority = 1500)
public abstract class MixinS21PacketChunkDataUltramine {

    private static final Logger LOGGER = LogManager.getLogger("NEID-Ultramine");

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
            LOGGER.info(
                    "==== NEID func_149269_a() OVERWRITE called! chunk ({},{}), fullChunk={}, mask=0x{}",
                    chunk.xPosition,
                    chunk.zPosition,
                    fullChunk,
                    Integer.toHexString(sectionMask));

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

            LOGGER.info(
                    "func_149269_a() complete: ebsMask=0x{}, dataSize={}",
                    Integer.toHexString(ebsMask),
                    neidData.length);
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

            LOGGER.info("Creating NEID format: ebsCount={}, totalSize={}", ebsCount, totalSize);

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

                int nonZeroBlocks = 0;
                int blocksWithMSB = 0;

                // CRITICAL: Write in LINEAR order (matching client's ShortBuffer.put())
                // Index calculation y << 8 | z << 4 | x creates sequential indices 0,1,2,3...
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int index = y << 8 | z << 4 | x;
                            int lsbVal = lsb[index] & 0xFF;
                            int msbVal = get4bitsCoordinate(msb, x, y, z);
                            int blockId = (msbVal << 8) | lsbVal;

                            if (blockId != 0) {
                                nonZeroBlocks++;
                                if (msbVal != 0) blocksWithMSB++;
                            }

                            // Write as big-endian 16-bit
                            data[offset++] = (byte) ((blockId >> 8) & 0xFF);
                            data[offset++] = (byte) (blockId & 0xFF);
                        }
                    }
                }

                LOGGER.info("EBS section={}: nonZero={}, withMSB={}", sectionIndex, nonZeroBlocks, blocksWithMSB);
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

            LOGGER.info("First 32 bytes: {}", bytesToHex(data, 0, 32));
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
}
