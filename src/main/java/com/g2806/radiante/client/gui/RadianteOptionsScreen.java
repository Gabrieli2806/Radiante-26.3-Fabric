package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.pipeline.Pipeline;
import com.g2806.radiante.client.pipeline.Pipeline.ShaderPackChoice;
import com.g2806.radiante.client.pipeline.Presets;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Settings for the ray tracer: pipeline preset (upscaler and denoiser), shader pack and terrain building. Changes
 * are collected while the screen is open and applied once it is closed with Done, so the pipeline is rebuilt at most
 * once.
 */
public class RadianteOptionsScreen extends OptionsSubScreen {

    public static final Component TITLE = Component.translatable("options.radiante.title");

    private Presets pendingPreset;
    private ShaderPackChoice pendingShaderPack;
    private String pendingDlssMode;
    private int pendingChunkThreads = Options.chunkBuildingThreads;
    private int pendingChunkBatchSize = Options.chunkBuildingBatchSize;
    private int pendingChunkTotalBatches = Options.chunkBuildingTotalBatches;
    private boolean pendingCollectEmission = Options.collectChunkEmission;
    private boolean applied;

    public RadianteOptionsScreen(Screen lastScreen, net.minecraft.client.Options options) {
        super(lastScreen, options, TITLE);
    }

    private static OptionInstance<Integer> slider(String key, int min, int max, int initial,
        OptionInstance.ValueUpdateListener<Integer> onUpdate) {
        return new OptionInstance<>(key, OptionInstance.noTooltip(),
            (caption, value) -> Component.translatable("options.generic_value", caption, value),
            new OptionInstance.IntRange(min, max, false), Math.max(min, Math.min(max, initial)), onUpdate);
    }

    private OptionInstance<Presets> presetOption() {
        List<Presets> available = new ArrayList<>();
        for (Presets preset : Presets.values()) {
            if (Pipeline.isPresetAvailable(preset.key)) {
                available.add(preset);
            }
        }
        if (available.isEmpty()) {
            return null;
        }

        Presets active = available.getFirst();
        for (Presets preset : available) {
            if (Objects.equals(preset.key, Pipeline.INSTANCE.getActivePresetName())) {
                active = preset;
            }
        }
        this.pendingPreset = active;

        return new OptionInstance<>("options.radiante.preset", OptionInstance.noTooltip(),
            (caption, value) -> Component.translatable(value.key),
            new OptionInstance.Enum<>(available, Codec.STRING.xmap(Presets::valueOf, Presets::name)), active,
            value -> this.pendingPreset = value);
    }

    private OptionInstance<ShaderPackChoice> shaderPackOption() {
        List<ShaderPackChoice> available = new ArrayList<>();
        for (ShaderPackChoice choice : Pipeline.getAvailableShaderPacks()) {
            if (Pipeline.isShaderPackSelectable(choice)) {
                available.add(choice);
            }
        }
        if (available.isEmpty()) {
            return null;
        }

        ShaderPackChoice active = available.getFirst();
        for (ShaderPackChoice choice : available) {
            if (Pipeline.isShaderPackActive(choice)) {
                active = choice;
            }
        }
        this.pendingShaderPack = active;

        List<ShaderPackChoice> values = List.copyOf(available);
        return new OptionInstance<>("options.radiante.shader_pack", OptionInstance.noTooltip(),
            (caption, value) -> Component.literal(value.displayName()),
            new OptionInstance.Enum<>(values, Codec.STRING.xmap(
                id -> values.stream().filter(choice -> choice.id().equals(id)).findFirst().orElse(values.getFirst()),
                ShaderPackChoice::id)),
            active, value -> this.pendingShaderPack = value);
    }

    private OptionInstance<String> dlssModeOption() {
        if (!Pipeline.isPresetAvailable(Presets.RT_DLSSRR.key)) {
            return null;
        }

        String current = Pipeline.getDlssMode();
        this.pendingDlssMode = current != null && Pipeline.DLSS_MODES.contains(current) ? current
            : "render_pipeline.module.dlss.attribute.mode.balanced";

        return new OptionInstance<>("options.radiante.dlss_mode", OptionInstance.noTooltip(),
            (caption, value) -> Component.translatable(value),
            new OptionInstance.Enum<>(Pipeline.DLSS_MODES, Codec.STRING), this.pendingDlssMode,
            value -> this.pendingDlssMode = value);
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }

        OptionInstance<Presets> preset = presetOption();
        OptionInstance<ShaderPackChoice> shaderPack = shaderPackOption();
        if (preset != null && shaderPack != null) {
            this.list.addSmall(preset, shaderPack);
        } else if (preset != null) {
            this.list.addSmall(preset);
        } else if (shaderPack != null) {
            this.list.addSmall(shaderPack);
        }

        OptionInstance<String> dlssMode = dlssModeOption();
        if (dlssMode != null) {
            this.list.addSmall(dlssMode);
        }

        this.list.addSmall(
            slider("options.radiante.chunk_building_threads", 1, Options.getMaxChunkBuildingThreads(),
                this.pendingChunkThreads, value -> this.pendingChunkThreads = value),
            slider("options.radiante.chunk_building_batch_size", 1, 64, this.pendingChunkBatchSize,
                value -> this.pendingChunkBatchSize = value));

        this.list.addSmall(
            slider("options.radiante.chunk_building_total_batches", 1, 64, this.pendingChunkTotalBatches,
                value -> this.pendingChunkTotalBatches = value),
            OptionInstance.createBoolean("options.radiante.collect_chunk_emission", this.pendingCollectEmission,
                value -> this.pendingCollectEmission = value));
    }

    @Override
    public void onClose() {
        this.applyChanges();
        super.onClose();
    }

    private void applyChanges() {
        if (this.applied) {
            return;
        }
        this.applied = true;

        if (this.pendingChunkThreads != Options.chunkBuildingThreads) {
            Options.setChunkBuildingThreads(this.pendingChunkThreads, false);
        }
        if (this.pendingChunkBatchSize != Options.chunkBuildingBatchSize) {
            Options.setChunkBuildingBatchSize(this.pendingChunkBatchSize, false);
        }
        if (this.pendingChunkTotalBatches != Options.chunkBuildingTotalBatches) {
            Options.setChunkBuildingTotalBatches(this.pendingChunkTotalBatches, false);
        }
        if (this.pendingCollectEmission != Options.collectChunkEmission) {
            Options.setCollectChunkEmission(this.pendingCollectEmission, false);
        }
        Options.overwriteConfig();

        boolean rebuild = false;
        if (this.pendingPreset != null
            && !Objects.equals(this.pendingPreset.key, Pipeline.INSTANCE.getActivePresetName())) {
            Pipeline.switchToPresetMode(this.pendingPreset.key, false);
            rebuild = true;
        }
        if (this.pendingShaderPack != null && !Pipeline.isShaderPackActive(this.pendingShaderPack)) {
            rebuild |= Pipeline.setShaderPack(this.pendingShaderPack, false);
        }
        // The mode lives on the DLSS module, which only exists once the DLSS pipeline is assembled.
        if (this.pendingDlssMode != null) {
            rebuild |= Pipeline.setDlssMode(this.pendingDlssMode);
        }
        if (rebuild) {
            Pipeline.savePipeline();
            Pipeline.build();
        }
    }
}
