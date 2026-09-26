package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.proxy.vulkan.RendererProxy;
import com.g2806.radiante.client.render.FrameGeneration;
import com.g2806.radiante.client.pipeline.Pipeline;
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
 * Settings for the ray tracer: pipeline preset (upscaler and denoiser), lighting options and terrain building. Changes
 * are collected while the screen is open and applied once it is closed with Done, so the pipeline is rebuilt at most
 * once.
 */
public class RadianteOptionsScreen extends OptionsSubScreen {

    public static final Component TITLE = Component.translatable("options.radiante.title");

    private Presets pendingPreset;
    private String pendingDlssMode;
    private int pendingGeneratedFrames = Options.frameGeneration ? Options.generatedFrames : 0;
    private String pendingCloudMode;
    private int pendingChunkThreads = Options.chunkBuildingThreads;
    private int pendingChunkBatchSize = Options.chunkBuildingBatchSize;
    private int pendingChunkTotalBatches = Options.chunkBuildingTotalBatches;
    private boolean pendingCollectEmission = Options.collectChunkEmission;
    private boolean pendingDebugLogging = Options.debugLogging;
    private boolean pendingBiomeFog = Options.biomeFog;
    private boolean pendingFirstPersonShadow = Options.firstPersonShadow;
    private int pendingBiomeFogStrength = Options.biomeFogStrength;
    private Boolean pendingVolumetricFog;
    private Boolean pendingMotionBlur;
    private Boolean pendingDepthOfField;
    private boolean pendingReflex = Options.reflex;
    private boolean applied;

    public RadianteOptionsScreen(Screen lastScreen, net.minecraft.client.Options options) {
        super(lastScreen, options, TITLE);
    }

    /** Carries every choice made so far into a fresh screen. */
    private RadianteOptionsScreen(RadianteOptionsScreen previous) {
        this(previous.lastScreen, previous.options);
        this.pendingPreset = previous.pendingPreset;
        this.pendingDlssMode = previous.pendingDlssMode;
        this.pendingGeneratedFrames = previous.pendingGeneratedFrames;
        this.pendingCloudMode = previous.pendingCloudMode;
        this.pendingChunkThreads = previous.pendingChunkThreads;
        this.pendingChunkBatchSize = previous.pendingChunkBatchSize;
        this.pendingChunkTotalBatches = previous.pendingChunkTotalBatches;
        this.pendingCollectEmission = previous.pendingCollectEmission;
        this.pendingDebugLogging = previous.pendingDebugLogging;
        this.pendingBiomeFog = previous.pendingBiomeFog;
        this.pendingFirstPersonShadow = previous.pendingFirstPersonShadow;
        this.pendingBiomeFogStrength = previous.pendingBiomeFogStrength;
        this.pendingVolumetricFog = previous.pendingVolumetricFog;
        this.pendingMotionBlur = previous.pendingMotionBlur;
        this.pendingDepthOfField = previous.pendingDepthOfField;
        this.pendingReflex = previous.pendingReflex;
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

    /** A 0-400 % slider that applies as it moves; 100 % is the shader pack's own brightness. */
    private static OptionInstance<Integer> brightnessSlider(String key, int current,
        java.util.function.Consumer<Integer> onChange) {
        return new OptionInstance<Integer>(key, RadianteOptionsScreen.<Integer>tooltip(key),
            (caption, value) -> Component.translatable("options.percent_value", caption, value),
            new OptionInstance.IntRange(0, 400, false), current, onChange::accept);
    }

    private static <T> OptionInstance.TooltipSupplier<T> tooltip(String key) {
        return OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip"));
    }

    private static OptionInstance<Integer> slider(String key, int min, int max, int initial,
        OptionInstance.ValueUpdateListener<Integer> onUpdate) {
        return new OptionInstance<Integer>(key, RadianteOptionsScreen.<Integer>tooltip(key),
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

    /** Clouds come from the shader pack, which ray marches them, so nothing has to be submitted for them. */
    private OptionInstance<String> cloudModeOption() {
        if (!Pipeline.supportsClouds()) {
            return null;
        }

        // A choice carried over from the previous screen (a quality level, say) is kept; only a fresh screen reads
        // the pipeline. Reading it every time undid the quality level's clouds, so it always came back as Custom.
        if (this.pendingCloudMode == null) {
            String current = Pipeline.getCloudMode();
            this.pendingCloudMode = current != null && Pipeline.CLOUD_MODES.contains(current) ? current
                : Pipeline.CLOUD_MODES.get(0);
        }

        return new OptionInstance<>("options.radiante.cloud_mode", tooltip("options.radiante.cloud_mode"),
            (caption, value) -> Component.translatable(value),
            new OptionInstance.Enum<>(Pipeline.CLOUD_MODES, Codec.STRING), this.pendingCloudMode,
            value -> {
                this.pendingCloudMode = value;
                refreshQualityLater();
            });
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

        if (this.pendingDlssMode == null) {
            String current = Pipeline.getDlssMode();
            this.pendingDlssMode = current != null && Pipeline.DLSS_MODES.contains(current) ? current
                : "render_pipeline.module.dlss.attribute.mode.ultra_performance";
        }

        return new OptionInstance<>("options.radiante.dlss_mode", tooltip("options.radiante.dlss_mode"),
            (caption, value) -> Component.translatable(value),
            new OptionInstance.Enum<>(Pipeline.DLSS_MODES, Codec.STRING), this.pendingDlssMode,
            value -> {
                this.pendingDlssMode = value;
                refreshQualityLater();
            });
    }

    /**
     * Streamline has to be loaded before Minecraft creates its Vulkan device, so turning frame generation on for the
     * first time only takes effect after a restart.
     */
    private OptionInstance<Integer> frameGenerationOption() {
        if (!usingDlss() || !com.g2806.radiante.platform.RadiantePlatform.INSTANCE.supportsStreamline()) {
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

        return new OptionInstance<>("options.radiante.frame_generation", tooltip("options.radiante.frame_generation"),
            (caption, value) -> value == 0 ? Component.translatable("options.off")
                : Component.literal((value + 1) + "x"),
            new OptionInstance.Enum<>(values, Codec.intRange(0, max)),
            Math.min(this.pendingGeneratedFrames, max), value -> this.pendingGeneratedFrames = value);
    }

    /** The quality level the current choices match, or Custom. */
    private QualityPreset currentQuality() {
        for (QualityPreset quality : QualityPreset.values()) {
            if (quality == QualityPreset.CUSTOM) {
                continue;
            }
            boolean dlssMatches = !usingDlss() || Objects.equals(this.pendingDlssMode,
                Pipeline.DLSS_MODES.get(quality.dlssMode));
            boolean fogMatches = this.pendingVolumetricFog == null || this.pendingVolumetricFog == quality.volumetricFog;
            boolean cloudsMatch = this.pendingCloudMode == null
                || Objects.equals(this.pendingCloudMode, Pipeline.CLOUD_MODES.get(quality.cloudMode));
            if (dlssMatches && fogMatches && cloudsMatch && Options.blockLightSampling == quality.blockLights
                && this.options.renderDistance().get() == quality.renderDistance) {
                return quality;
            }
        }
        return QualityPreset.CUSTOM;
    }

    private void applyQuality(QualityPreset quality) {
        if (quality == QualityPreset.CUSTOM) {
            return;
        }
        if (usingDlss()) {
            this.pendingDlssMode = Pipeline.DLSS_MODES.get(quality.dlssMode);
        }
        if (Pipeline.supportsVolumetricFog()) {
            this.pendingVolumetricFog = quality.volumetricFog;
        }
        if (Pipeline.supportsClouds()) {
            this.pendingCloudMode = Pipeline.CLOUD_MODES.get(quality.cloudMode);
        }
        Options.blockLightSampling = quality.blockLights;
        this.options.renderDistance().set(quality.renderDistance);
    }

    /** Every setting on this screen back to how a fresh install has it. */
    private void resetToDefaults() {
        Options.resetVisualDefaults();
        this.pendingPreset = Pipeline.isPresetAvailable(Presets.RT_DLSSRR.key) ? Presets.RT_DLSSRR : null;
        this.pendingDlssMode = "render_pipeline.module.dlss.attribute.mode.ultra_performance";
        this.pendingGeneratedFrames = 0;
        this.pendingCloudMode = Pipeline.supportsClouds() ? Pipeline.CLOUD_MODES.get(1) : null;
        this.pendingChunkThreads = Options.getDefaultChunkBuildingThreads();
        this.pendingChunkBatchSize = 12;
        this.pendingChunkTotalBatches = 12;
        this.pendingCollectEmission = true;
        this.pendingDebugLogging = false;
        this.pendingBiomeFog = true;
        this.pendingFirstPersonShadow = true;
        this.pendingBiomeFogStrength = 100;
        this.pendingVolumetricFog = Pipeline.supportsVolumetricFog() ? Boolean.TRUE : null;
        this.pendingMotionBlur = Boolean.FALSE;
        this.pendingDepthOfField = Boolean.FALSE;
        this.pendingReflex = false;
    }

    @Override
    protected void addOptions() {
        if (this.list == null) {
            return;
        }

        // Filled in first so the quality level below can tell which one the current settings match.
        OptionInstance<Presets> preset = presetOption();
        OptionInstance<String> dlssMode = dlssModeOption();
        OptionInstance<Integer> frameGeneration = frameGenerationOption();
        OptionInstance<String> clouds = cloudModeOption();
        if (Pipeline.supportsVolumetricFog() && this.pendingVolumetricFog == null) {
            this.pendingVolumetricFog = Pipeline.isVolumetricFog();
        }

        List<QualityPreset> levels = List.of(QualityPreset.values());
        OptionInstance<QualityPreset> quality = new OptionInstance<>("options.radiante.quality",
            tooltip("options.radiante.quality"), (caption, value) -> Component.translatable(value.key),
            new OptionInstance.Enum<>(levels, Codec.STRING.xmap(QualityPreset::valueOf, QualityPreset::name)), currentQuality(),
            value -> {
                if (value == QualityPreset.CUSTOM || value == currentQuality()) {
                    return;
                }
                applyQuality(value);
                if (this.minecraft != null) {
                    this.minecraft.execute(this::reopenWithSameChoices);
                }
            });
        net.minecraft.client.gui.components.Button reset = net.minecraft.client.gui.components.Button.builder(
                Component.translatable("options.radiante.reset_defaults"), button -> {
                    resetToDefaults();
                    reopenWithSameChoices();
                })
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("options.radiante.reset_defaults.tooltip")))
            .build();
        this.list.addSmall(quality.createButton(this.options), reset);

        if (preset != null) {
            this.list.addSmall(preset);
        }

        if (dlssMode != null && frameGeneration != null) {
            this.list.addSmall(dlssMode, frameGeneration);
        } else if (dlssMode != null) {
            this.list.addSmall(dlssMode);
        } else if (frameGeneration != null) {
            this.list.addSmall(frameGeneration);
        }

        // Reflex comes with Streamline, which has to be loaded before Minecraft creates its Vulkan device: the
        // first time it is turned on it takes effect after a restart, the same as frame generation.
        if (com.g2806.radiante.platform.RadiantePlatform.INSTANCE.supportsStreamline()) {
            this.list.addSmall(OptionInstance.createBoolean("options.radiante.reflex",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                    RadianteClient.streamlineLoaded() ? "options.radiante.reflex.tooltip"
                        : "options.radiante.reflex.tooltip_restart")),
                this.pendingReflex, value -> this.pendingReflex = value));
        }

        addHdrOptions();

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
            OptionInstance.createBoolean("options.radiante.collect_chunk_emission",
                tooltip("options.radiante.collect_chunk_emission"), this.pendingCollectEmission,
                value -> this.pendingCollectEmission = value));

        this.list.addSmall(
            OptionInstance.createBoolean("options.radiante.biome_fog",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.radiante.biome_fog.tooltip")),
                this.pendingBiomeFog, value -> this.pendingBiomeFog = value),
            new OptionInstance<>("options.radiante.biome_fog_strength", tooltip("options.radiante.biome_fog_strength"),
                (caption, value) -> Component.translatable("options.percent_value", caption, value),
                new OptionInstance.IntRange(0, 400, false), this.pendingBiomeFogStrength,
                value -> this.pendingBiomeFogStrength = value));

        OptionInstance<Boolean> firstPersonShadow = OptionInstance.createBoolean(
            "options.radiante.first_person_shadow",
            OptionInstance.cachedConstantTooltip(Component.translatable("options.radiante.first_person_shadow.tooltip")),
            this.pendingFirstPersonShadow, value -> this.pendingFirstPersonShadow = value);
        OptionInstance<Boolean> debugLogging = OptionInstance.createBoolean("options.radiante.debug_logging",
            this.pendingDebugLogging, value -> this.pendingDebugLogging = value);
        this.list.addSmall(OptionInstance.createBoolean("options.radiante.block_light_sampling",
                tooltip("options.radiante.block_light_sampling"), Options.blockLightSampling,
                value -> {
                    Options.blockLightSampling = value;
                    refreshQualityLater();
                }),
            OptionInstance.createBoolean("options.radiante.held_item_light",
                tooltip("options.radiante.held_item_light"), Options.heldItemLight,
                value -> Options.heldItemLight = value));
        this.list.addSmall(brightnessSlider("options.radiante.day_brightness", Options.dayBrightness,
                value -> Options.dayBrightness = value),
            brightnessSlider("options.radiante.night_brightness", Options.nightBrightness,
                value -> Options.nightBrightness = value));
        this.list.addSmall(brightnessSlider("options.radiante.emission_brightness", Options.emissionBrightness,
                value -> Options.emissionBrightness = value),
            brightnessSlider("options.radiante.held_light_brightness", Options.heldLightBrightness,
                value -> Options.heldLightBrightness = value));
        this.list.addSmall(OptionInstance.createBoolean("options.radiante.block_outline",
                tooltip("options.radiante.block_outline"), Options.blockOutline,
                value -> Options.blockOutline = value),
            OptionInstance.createBoolean("options.radiante.parallax_transparent_edges",
                tooltip("options.radiante.parallax_transparent_edges"), Options.parallaxTransparentEdges,
                value -> Options.parallaxTransparentEdges = value));
        this.list.addSmall(OptionInstance.createBoolean("options.radiante.pixel_lighting",
            tooltip("options.radiante.pixel_lighting"), Options.pixelLighting, value -> Options.pixelLighting = value),
            null);
        this.list.addSmall(OptionInstance.createBoolean("options.radiante.vanilla_sun_path",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.radiante.vanilla_sun_path.tooltip")),
                Options.vanillaSunPath, value -> Options.vanillaSunPath = value),
            OptionInstance.createBoolean("options.radiante.vanilla_celestial_orientation",
                OptionInstance.cachedConstantTooltip(
                    Component.translatable("options.radiante.vanilla_celestial_orientation.tooltip")),
                Options.vanillaCelestialOrientation, value -> Options.vanillaCelestialOrientation = value));
        if (Pipeline.supportsVolumetricFog()) {
            this.list.addSmall(
                OptionInstance.createBoolean("options.radiante.volumetric_fog",
                    OptionInstance.cachedConstantTooltip(
                        Component.translatable("options.radiante.volumetric_fog.tooltip")),
                    this.pendingVolumetricFog, value -> {
                        this.pendingVolumetricFog = value;
                        refreshQualityLater();
                    }),
                brightnessSlider("options.radiante.volumetric_fog_strength", Options.volumetricFogStrength,
                    value -> Options.volumetricFogStrength = value));
            this.list.addSmall(firstPersonShadow, debugLogging);
        } else {
            this.list.addSmall(firstPersonShadow, debugLogging);
        }
        if (Pipeline.supportsShaderPackToggle(Pipeline.MOTION_BLUR_ATTRIBUTE)
            && Pipeline.supportsShaderPackToggle(Pipeline.DEPTH_OF_FIELD_ATTRIBUTE)) {
            if (this.pendingMotionBlur == null) {
                this.pendingMotionBlur = Pipeline.isShaderPackToggleOn(Pipeline.MOTION_BLUR_ATTRIBUTE);
            }
            if (this.pendingDepthOfField == null) {
                this.pendingDepthOfField = Pipeline.isShaderPackToggleOn(Pipeline.DEPTH_OF_FIELD_ATTRIBUTE);
            }
            this.list.addSmall(
                OptionInstance.createBoolean("options.radiante.motion_blur", tooltip("options.radiante.motion_blur"),
                    this.pendingMotionBlur, value -> this.pendingMotionBlur = value),
                OptionInstance.createBoolean("options.radiante.depth_of_field",
                    tooltip("options.radiante.depth_of_field"), this.pendingDepthOfField,
                    value -> this.pendingDepthOfField = value));
        }
    }

    /**
     * HDR display output. The window's format is picked when it is created, so the toggle takes effect after a
     * restart; its tooltip says whether HDR is running now. The brightness sliders apply at once.
     */
    private void addHdrOptions() {
        String tooltipKey = com.g2806.radiante.client.hdr.HdrDisplay.isActive() ? "options.radiante.hdr_output.tooltip"
            : Options.hdrOutput ? "options.radiante.hdr_output.tooltip_pending"
                : "options.radiante.hdr_output.tooltip";
        OptionInstance<Boolean> toggle = OptionInstance.createBoolean("options.radiante.hdr_output",
            OptionInstance.cachedConstantTooltip(Component.translatable(tooltipKey)), Options.hdrOutput, value -> {
                Options.hdrOutput = value;
                if (this.minecraft != null) {
                    this.minecraft.execute(this::reopenWithSameChoices);
                }
            });
        if (!Options.hdrOutput) {
            this.list.addSmall(toggle, null);
            return;
        }
        this.list.addSmall(toggle, nitsSlider("options.radiante.hdr_peak", 400, 4000, Options.hdrPeakNits,
            value -> Options.hdrPeakNits = value));
        this.list.addSmall(nitsSlider("options.radiante.hdr_paper_white", 80, 400, Options.hdrPaperWhiteNits,
            value -> Options.hdrPaperWhiteNits = value), null);
    }

    private static OptionInstance<Integer> nitsSlider(String key, int min, int max, int current,
        java.util.function.Consumer<Integer> onChange) {
        return new OptionInstance<Integer>(key, RadianteOptionsScreen.<Integer>tooltip(key),
            (caption, value) -> Component.translatable("options.radiante.nits_value", caption, value),
            new OptionInstance.IntRange(min, max, false), Math.max(min, Math.min(max, current)), onChange::accept);
    }

    /** A setting the quality level covers was changed by hand: the level shown above it has to follow. */
    private void refreshQualityLater() {
        if (this.minecraft != null) {
            this.minecraft.execute(this::reopenWithSameChoices);
        }
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
        Options.firstPersonShadow = this.pendingFirstPersonShadow;
        if (this.pendingReflex != Options.reflex) {
            Options.reflex = this.pendingReflex;
            FrameGeneration.applyReflex();
        }
        Options.biomeFogStrength = this.pendingBiomeFogStrength;
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
        if (this.pendingVolumetricFog != null) {
            rebuild |= Pipeline.setVolumetricFog(this.pendingVolumetricFog);
        }
        if (this.pendingMotionBlur != null) {
            rebuild |= Pipeline.setShaderPackToggle(Pipeline.MOTION_BLUR_ATTRIBUTE, this.pendingMotionBlur);
        }
        if (this.pendingDepthOfField != null) {
            rebuild |= Pipeline.setShaderPackToggle(Pipeline.DEPTH_OF_FIELD_ATTRIBUTE, this.pendingDepthOfField);
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
