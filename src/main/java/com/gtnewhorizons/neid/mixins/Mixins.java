package com.gtnewhorizons.neid.mixins;

import javax.annotation.Nonnull;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;
import com.gtnewhorizons.neid.NEIDConfig;

public enum Mixins implements IMixins {

    // spotless:off
    VANILLA_STARTUP(new MixinBuilder()
        .addCommonMixins(
            "minecraft.MixinWorld",
            "minecraft.MixinExtendedBlockStorage",
            "minecraft.MixinStatList",
            "minecraft.MixinBlockFire",
            "minecraft.MixinS22PacketMultiBlockChange",
            "minecraft.MixinS23PacketBlockChange",
            "minecraft.MixinS24PacketBlockAction",
            "minecraft.MixinS26PacketMapChunkBulk",
            "minecraft.MixinItemInWorldManager",
            "minecraft.MixinBlock")),
    VANILLA_STARTUP_CHUNK_SAVE(new MixinBuilder()
        .addCommonMixins("minecraft.MixinAnvilChunkLoader")
        .addExcludedMod(TargetMods.ULTRAMINE)),
    VANILLA_STARTUP_ONLY_WITHOUT_THERMOS(new MixinBuilder()
        .addCommonMixins("minecraft.MixinS21PacketChunkData")
        .addExcludedMod(TargetMods.THERMOS)
        .addExcludedMod(TargetMods.ULTRAMINE)),
    VANILLA_STARTUP_ONLY_WITH_THERMOS(new MixinBuilder()
        .addCommonMixins("minecraft.MixinS21PacketChunkDataThermosTainted")
        .addRequiredMod(TargetMods.THERMOS)
        .addExcludedMod(TargetMods.ULTRAMINE)),
    VANILLA_STARTUP_WITH_ULTRAMINE(new MixinBuilder()
        .addCommonMixins(
            "minecraft.MixinExtendedBlockStorageUltramine",
            "minecraft.MixinS21PacketChunkDataUltramine",
            "minecraft.MixinAnvilChunkLoaderUltramine",
            "minecraft.MixinEbsSaveFakeNbt")
        .addRequiredMod(TargetMods.ULTRAMINE)),
    VANILLA_STARTUP_CLIENT(new MixinBuilder()
        .addClientMixins(
            "minecraft.client.MixinRenderGlobal",
            "minecraft.client.MixinNetHandlerPlayClient",
            "minecraft.client.MixinPlayerControllerMP",
            "minecraft.client.MixinChunk")),
    VANILLA_STARTUP_DATAWATCHER(new MixinBuilder()
        .addCommonMixins("minecraft.MixinDataWatcher")
        .setApplyIf(() -> NEIDConfig.ExtendDataWatcher));
    // spotless:on

    private final MixinBuilder builder;

    Mixins(MixinBuilder builder) {
        this.builder = builder.setPhase(Phase.EARLY);
    }

    @Nonnull
    @Override
    public MixinBuilder getBuilder() {
        return builder;
    }
}
