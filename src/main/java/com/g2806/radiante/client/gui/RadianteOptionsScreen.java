package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.proxy.vulkan.RendererProxy;
import com.g2806.radiante.client.render.FrameGeneration;
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
    private int pendingGeneratedFrames = Options.frameGeneration ? Options.generatedFrames : 0;
    private String pendingCloudMode;
    private int pendingChunkThreads = Options.chunkBuildingThreads;
    private int pendingChunkBatchSize = Options.chunkBuildingBatchSize;
    private int pendingChunkTotalBatches = Options.chunkBuildingTotalBatches;
    private boolean pendingCollectEmission = Options.collectChunkEmission;
    private boolean pendingDebugLogging = Options.debugLogging;
    private boolean pendingBiomeFog = Options.biomeFog;
    private boolean applied;

    public RadianteOptionsScreen(Screen lastScreen, net.minecraft.client.Options options) {
        super(lastScreen, options, TITLE);
    }

    /** Carries every choice made so far into a fresh screen. */
    private RadianteOptionsScreen(RadianteOptionsScreen previous) {
        this(previous.lastScreen, previous.options);
        this.pendingPreset = previous.pendingPreset;
        this.pendingShaderPack = previous.pendingShaderPack;
        this.pendingDlssMode = previous.pendingDlssMode;
        this.pendingGeneratedFrames = previous.pendingGeneratedFrames;
        this.pendingCloudMode = previous.pendingCloudMode;
        this.pendingChunkThreads = previous.pendingChunkThreads;
        this.pendingChunkBatchSize = previous.pendingChunkBatchSize;
        this.pendingChunkTotalBatches = previous.pendingChunkTotalBatches;
        this.pendingCollectEmission = previous.pendingCollectEmission;
        this.pendingDebugLogging = previous.pendingDebugLogging;
        this.pendingBiomeFog = previous.pendingBiomeFog;
    }

    /**
     * Lays the screen out again after a change that adds or removes options. Rebuilding this screen in place does
     * not work: the layout an options screen holds is created once with the screen and appended to on every init,
     * so a second pass leaves the first pass's widgets in it - they come back greyed out and stale, including
     * options the new pipeline does not even have. A new screen gets a new layout.
     */
    private void reopenWithSameChoices() {
        if (this.minecraft == null) {
            return;
        }
        // The replacement screen carries the choices, so this one must not write them on the way out.
        this.applied = true;
        this.minecraft.gui.setScreen(new RadianteOptionsScreen(this));
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
        // The list is rebuilt whenever the preset changes, so a choice already made has to survive that.
        if (this.pendingPreset != null && available.contains(this.pendingPreset)) {
            active = this.pendingPreset;
        }
        this.pendingPreset = active;

        return new OptionInstance<>("options.radiante.preset", OptionInstance.noTooltip(),
            (caption, value) -> Component.translatable(value.key),
            new OptionInstance.Enum<>(available, Codec.STRING.xmap(Presets::valueOf, Presets::name)), active,
            value -> {
                boolean changed = value != this.pendingPreset;
                this.pendingPreset = value;
                // Which of the settings below make sense depends on this one, so the screen has to be laid out
                // again. Doing it straight away would edit the widget list that is handling this very click.
                if (changed && this.minecraft != null) {
                    this.minecraft.execute(this::reopenWithSameChoices);
                }
            });
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

    /** Clouds come from the shader pack, which ray marches them, so nothing has to be submitted for them. */
    private OptionInstance<String> cloudModeOption() {
        if (!Pipeline.supportsClouds()) {
            return null;
        }

        String current = Pipeline.getCloudMode();
        this.pendingCloudMode = current != null && Pipeline.CLOUD_MODES.contains(current) ? current
            : Pipeline.CLOUD_MODES.get(0);

        return new OptionInstance<>("options.radiante.cloud_mode", OptionInstance.noTooltip(),
            (caption, value) -> Component.translatable(value),
            new OptionInstance.Enum<>(Pipeline.CLOUD_MODES, Codec.STRING), this.pendingCloudMode,
            value -> this.pendingCloudMode = value);
    }

    /**
     * DLSS is one vendor's upscaler and denoiser together; its quality modes and its frame generation belong to it
     * alone. Offering them next to FSR or XeSS suggests they can be combined, and they cannot.
     */
    private boolean usingDlss() {
        return this.pendingPreset == Presets.RT_DLSSRR;
    }

    private OptionInstance<String> dlssModeOption() {
        if (!usingDlss()) {
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

    /**
     * Streamline has to be loaded before Minecraft creates its Vulkan device, so turning frame generation on for the
     * first time only takes effect after a restart.
     */
    private OptionInstance<Integer> frameGenerationOption() {
        if (!usingDlss()) {
            return null;
        }

        int max = RendererProxy.maxGeneratedFrames();
        if (max <= 0 && !RadianteClient.streamlineLoaded()) {
            // Offer 2x so the player can switch it on; the real maximum shows up after the restart.
            max = 1;
        }
        if (max <= 0) {
            return null;
        }

        List<Integer> values = new ArrayList<>();
        for (int frames = 0; frames <= max; frames++) {
            values.add(frames);
        }

        return new OptionInstance<>("options.radiante.frame_generation", OptionInstance.noTooltip(),
            (caption, value) -> value == 0 ? Component.translatable("options.off")
                : Component.literal((value + 1) + "x"),
            new OptionInstance.Enum<>(values, Codec.intRange(0, max)),
            Math.min(this.pendingGeneratedFrames, max), value -> this.pendingGeneratedFrames = value);
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
        OptionInstance<Integer> frameGeneration = frameGenerationOption();
        if (dlssMode != null && frameGeneration != null) {
            this.list.addSmall(dlssMode, frameGeneration);
        } else if (dlssMode != null) {
            this.list.addSmall(dlssMode);
        } else if (frameGeneration != null) {
            this.list.addSmall(frameGeneration);
        }

        OptionInstance<String> clouds = cloudModeOption();
        if (clouds != null) {
            this.list.addSmall(clouds);
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

        this.list.addSmall(
            OptionInstance.createBoolean("options.radiante.biome_fog",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.radiante.biome_fog.tooltip")),
                this.pendingBiomeFog, value -> this.pendingBiomeFog = value),
            OptionInstance.createBoolean("options.radiante.debug_logging", this.pendingDebugLogging,
                value -> this.pendingDebugLogging = value));
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
        Options.biomeFog = this.pendingBiomeFog;
        if (this.pendingDebugLogging != Options.debugLogging) {
            Options.setDebugLogging(this.pendingDebugLogging, false);
        }
        if (!usingDlss()) {
            this.pendingGeneratedFrames = 0;
        }
        boolean wantsFrameGeneration = this.pendingGeneratedFrames > 0;
        if (wantsFrameGeneration != Options.frameGeneration || this.pendingGeneratedFrames != Options.generatedFrames) {
            Options.frameGeneration = wantsFrameGeneration;
            Options.generatedFrames = Math.max(1, this.pendingGeneratedFrames);
            FrameGeneration.setGeneratedFrames(this.pendingGeneratedFrames);
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
        if (this.pendingCloudMode != null && !Objects.equals(this.pendingCloudMode, Pipeline.getCloudMode())) {
            rebuild |= Pipeline.setCloudMode(this.pendingCloudMode);
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
