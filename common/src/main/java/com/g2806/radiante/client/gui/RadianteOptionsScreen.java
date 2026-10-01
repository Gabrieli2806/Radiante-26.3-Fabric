package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.render.FrameGeneration;
import com.g2806.radiante.client.pipeline.Pipeline;
import com.g2806.radiante.client.pipeline.Presets;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Settings for the ray tracer, laid out the way Sodium lays out its own: a tab per section along the top, the section's
 * options in a scrolling column on the left, and on the right what the option under the mouse does and what it costs.
 * Changes are collected while the screen is open and applied with Apply or Done (or on leaving), so the pipeline is
 * rebuilt at most once. What each section holds is the option builders below; the layout is {@link SettingsLayout}.
 */
public class RadianteOptionsScreen extends Screen {

    public static final Component TITLE = Component.translatable("options.radiante.title");

    private Presets pendingPreset;
    private String pendingDlssMode;
    /** FSR or XeSS render resolution mode; null when neither is in the pipeline. */
    private String pendingUpscalerMode;
    private Integer pendingFarBounceDistance;
    private Integer pendingFarBounces;
    private int pendingGeneratedFrames = Options.frameGeneration ? Options.generatedFrames : 0;
    private int pendingFrameGenerationBackend = Options.frameGenerationBackend;
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
    private Boolean pendingCloudShadows;
    private Boolean pendingRestir;
    private Boolean pendingSer;
    private Boolean pendingFroxelFog;
    private Boolean pendingRainRefraction;
    private Boolean pendingSeamlessGlass;
    // Plain on/off shader pack settings (light grid, cached deep bounces), by attribute; absent until read.
    private java.util.Map<String, Boolean> pendingPackToggles = new java.util.HashMap<>();
    // Shader pack settings that cost frame time; null until read from the pipeline, or when the pack lacks them.
    private Integer pendingBounces;
    private Boolean pendingParallax;
    private Boolean pendingBedrockAtmosphere;
    private Integer pendingFogSamples;
    private Boolean pendingDepthOfField;
    private boolean pendingReflex = Options.reflex;
    /** Numeric pipeline settings, read once per screen; a setting the pipeline lacks has no entry. */
    private java.util.EnumMap<Tunable, Integer> pendingTunables;
    private PictureStyle.ToneMethod pendingToneMethod;
    private boolean applied;

    /** The part of the settings shown; kept while the game runs, so the screen reopens where it was left. */
    enum Category {
        QUALITY, IMAGE, LIGHTING, SKY_AND_WATER, PERFORMANCE, OTHER;

        String key() {
            return "options.radiante.category." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Reset to Defaults was pressed: the stored pipeline and shader pack settings go too, on apply. */
    private boolean forgetStoredSettings;

    private final Screen lastScreen;
    private final net.minecraft.client.Options options;
    /** The options of the section shown, filled by the add*Options builders. */
    private final List<OptionInstance<?>> page = new ArrayList<>();
    private final List<SettingsLayout.Section> sections = new ArrayList<>();
    private SettingsLayout layout;
    /** The search text, kept when the screen is laid out again after a change. */
    private String search = "";
    /** Where the option column was scrolled to, kept when the screen is laid out again after a change. */
    private double scroll;

    public RadianteOptionsScreen(Screen lastScreen, net.minecraft.client.Options options) {
        super(TITLE);
        this.lastScreen = lastScreen;
        this.options = options;
    }

    /** Carries every choice made so far into a fresh screen. */
    private RadianteOptionsScreen(RadianteOptionsScreen previous) {
        this(previous.lastScreen, previous.options);
        this.pendingPreset = previous.pendingPreset;
        this.pendingDlssMode = previous.pendingDlssMode;
        this.pendingUpscalerMode = previous.pendingUpscalerMode;
        this.pendingFarBounceDistance = previous.pendingFarBounceDistance;
        this.pendingFarBounces = previous.pendingFarBounces;
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
        this.pendingCloudShadows = previous.pendingCloudShadows;
        this.pendingRestir = previous.pendingRestir;
        this.pendingSer = previous.pendingSer;
        this.pendingFroxelFog = previous.pendingFroxelFog;
        this.pendingRainRefraction = previous.pendingRainRefraction;
        this.pendingSeamlessGlass = previous.pendingSeamlessGlass;
        this.pendingPackToggles = new java.util.HashMap<>(previous.pendingPackToggles);
        this.pendingFrameGenerationBackend = previous.pendingFrameGenerationBackend;
        this.pendingBounces = previous.pendingBounces;
        this.pendingParallax = previous.pendingParallax;
        this.forgetStoredSettings = previous.forgetStoredSettings;
        this.pendingBedrockAtmosphere = previous.pendingBedrockAtmosphere;
        this.pendingFogSamples = previous.pendingFogSamples;
        this.pendingDepthOfField = previous.pendingDepthOfField;
        this.pendingReflex = previous.pendingReflex;
        this.pendingTunables = previous.pendingTunables;
        this.pendingToneMethod = previous.pendingToneMethod;
        this.scroll = previous.layout != null ? previous.layout.scroll() : previous.scroll;
        this.search = previous.layout != null ? previous.layout.searchText() : previous.search;
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
     * Frame generation: DLSS on NVIDIA cards that support it, FSR on the others. It runs in the renderer, so it
     * switches on and off without a restart. Not offered with HDR output, which presents its own way.
     */
    private OptionInstance<Integer> frameGenerationOption() {
        int max = FrameGeneration.maxGeneratedFrames();
        String backend = FrameGeneration.backendName();
        if (FrameGeneration.hasBackendChoice() && this.pendingFrameGenerationBackend != Options.frameGenerationBackend) {
            // Not applied yet: show the range and name of the one picked.
            boolean fsr = this.pendingFrameGenerationBackend == 2;
            max = fsr ? 1 : FrameGeneration.dlssMaxGeneratedFrames();
            backend = fsr ? "FSR" : "DLSS";
        }
        if (max <= 0 || Options.hdrOutput) {
            return null;
        }

        List<Integer> values = new ArrayList<>();
        for (int frames = 0; frames <= max; frames++) {
            values.add(frames);
        }

        String backendName = backend;
        return new OptionInstance<>("options.radiante.frame_generation",
            OptionInstance.cachedConstantTooltip(Component.translatable("options.radiante.frame_generation.tooltip",
                backendName == null ? "" : backendName)),
            (caption, value) -> value == 0 ? Component.translatable("options.off")
                : Component.literal((value + 1) + "x" + (backendName == null ? "" : " (" + backendName + ")")),
            new OptionInstance.Enum<>(values, Codec.intRange(0, max)),
            Math.min(this.pendingGeneratedFrames, max), value -> {
                this.pendingGeneratedFrames = value;
                refreshQualityLater();
            });
    }

    /** Shader Execution Reordering, where the shader pack has it. */
    private OptionInstance<Boolean> serOption() {
        if (!Pipeline.supportsShaderPackToggle(Pipeline.SER_ATTRIBUTE)) {
            return null;
        }
        if (this.pendingSer == null) {
            this.pendingSer = Pipeline.isShaderPackToggleOn(Pipeline.SER_ATTRIBUTE);
        }
        return OptionInstance.createBoolean("options.radiante.ser", tooltip("options.radiante.ser"),
            this.pendingSer, value -> this.pendingSer = value);
    }

    /** ReSTIR for block lights, where the shader pack has it. */
    private OptionInstance<Boolean> restirOption() {
        if (!Pipeline.supportsShaderPackToggle(Pipeline.RESTIR_ATTRIBUTE)) {
            return null;
        }
        if (this.pendingRestir == null) {
            this.pendingRestir = Pipeline.isShaderPackToggleOn(Pipeline.RESTIR_ATTRIBUTE);
        }
        return OptionInstance.createBoolean("options.radiante.restir", tooltip("options.radiante.restir"),
            this.pendingRestir, value -> this.pendingRestir = value);
    }

    /** DLSS or FSR frame generation, offered where the GPU runs both (FSR works next to any upscaler). */
    private OptionInstance<Integer> frameGenerationBackendOption() {
        if (!FrameGeneration.hasBackendChoice() || Options.hdrOutput) {
            return null;
        }
        return new OptionInstance<>("options.radiante.frame_generation_backend",
            tooltip("options.radiante.frame_generation_backend"),
            (caption, value) -> Component.translatable(switch (value) {
                case 1 -> "options.radiante.frame_generation_backend.dlss";
                case 2 -> "options.radiante.frame_generation_backend.fsr";
                default -> "options.radiante.frame_generation_backend.auto";
            }),
            new OptionInstance.Enum<>(List.of(0, 1, 2), Codec.intRange(0, 2)), this.pendingFrameGenerationBackend,
            value -> {
                this.pendingFrameGenerationBackend = value;
                // The multiplier's range follows the backend (FSR: 2x only).
                refreshQualityLater();
            });
    }

    // The options that only take effect after a restart; kept to mark them on the list.
    private OptionInstance<?> hdrToggle;
    private OptionInstance<?> reflexToggle;

    /** HDR output chosen differently from what the window was created with. */
    private boolean hdrPending() {
        return Options.hdrOutput != com.g2806.radiante.client.hdr.HdrDisplay.isActive();
    }

    /** Reflex chosen while Streamline is not loaded: it is loaded only at start. */
    private boolean streamlinePending() {
        return this.pendingReflex && !RadianteClient.streamlineLoaded();
    }

    /** What the player changed that needs the game started again, for the notice after leaving. */
    private List<Component> restartChanges() {
        List<Component> changes = new ArrayList<>();
        if (hdrPending()) {
            changes.add(Component.translatable("options.radiante.hdr_output"));
        }
        if (this.pendingReflex && !RadianteClient.streamlineLoaded()) {
            changes.add(Component.translatable("options.radiante.reflex"));
        }
        return changes;
    }

    /** The FSR or XeSS modes when the chosen pipeline has one of those upscalers; null otherwise. */
    private List<String> upscalerModes() {
        if (this.pendingPreset == Presets.RT_NRD_FSR) {
            return Pipeline.FSR_MODES;
        }
        if (this.pendingPreset == Presets.RT_NRD_XESS) {
            return Pipeline.XESS_MODES;
        }
        return null;
    }

    private String upscalerModule() {
        return this.pendingPreset == Presets.RT_NRD_FSR ? Pipeline.FSR_MODULE_NAME : Pipeline.XESS_MODULE_NAME;
    }

    private String upscalerAttribute() {
        return this.pendingPreset == Presets.RT_NRD_FSR ? Pipeline.FSR_MODE_ATTRIBUTE : Pipeline.XESS_MODE_ATTRIBUTE;
    }

    /** Render resolution for FSR and XeSS, as DLSS Mode is for DLSS. */
    private OptionInstance<String> upscalerModeOption() {
        List<String> modes = upscalerModes();
        if (modes == null) {
            return null;
        }
        if (this.pendingUpscalerMode == null || !modes.contains(this.pendingUpscalerMode)) {
            String current = Pipeline.getModuleValue(upscalerModule(), upscalerAttribute());
            this.pendingUpscalerMode = current != null && modes.contains(current) ? current : modes.get(3);
        }
        return new OptionInstance<>("options.radiante.upscaler_mode", tooltip("options.radiante.upscaler_mode"),
            (caption, value) -> Component.translatable(value),
            new OptionInstance.Enum<>(modes, Codec.STRING), this.pendingUpscalerMode, value -> {
                this.pendingUpscalerMode = value;
                refreshQualityLater();
            });
    }

    /** The FSR / XeSS mode a quality level uses: the same step as its DLSS mode. */
    private String upscalerModeFor(QualityPreset quality) {
        List<String> modes = upscalerModes();
        return modes == null ? null : modes.get(Math.min(quality.dlssMode, modes.size() - 1));
    }

    /** The quality level the current choices match, or Custom. */
    private QualityPreset currentQuality() {
        for (QualityPreset quality : QualityPreset.values()) {
            if (quality == QualityPreset.CUSTOM) {
                continue;
            }
            boolean dlssMatches = !usingDlss() || Objects.equals(this.pendingDlssMode,
                Pipeline.DLSS_MODES.get(quality.dlssMode));
            dlssMatches &= upscalerModes() == null || this.pendingUpscalerMode == null
                || Objects.equals(this.pendingUpscalerMode, upscalerModeFor(quality));
            boolean fogMatches = this.pendingVolumetricFog == null || this.pendingVolumetricFog == quality.volumetricFog;
            boolean cloudsMatch = this.pendingCloudMode == null
                || Objects.equals(this.pendingCloudMode, Pipeline.CLOUD_MODES.get(quality.cloudMode));
            boolean shaderMatches = (this.pendingBounces == null || this.pendingBounces == quality.bounces)
                && (this.pendingParallax == null || this.pendingParallax == quality.parallax)
                && (this.pendingFogSamples == null || this.pendingFogSamples == quality.fogSamples);
            if (dlssMatches && fogMatches && cloudsMatch && shaderMatches
                && Options.blockLightSampling == quality.blockLights
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
        if (upscalerModes() != null) {
            this.pendingUpscalerMode = upscalerModeFor(quality);
        }
        if (Pipeline.supportsVolumetricFog()) {
            this.pendingVolumetricFog = quality.volumetricFog;
        }
        if (Pipeline.supportsClouds()) {
            this.pendingCloudMode = Pipeline.CLOUD_MODES.get(quality.cloudMode);
        }
        Options.blockLightSampling = quality.blockLights;
        this.options.renderDistance().set(quality.renderDistance);
        if (this.pendingBounces != null) {
            this.pendingBounces = quality.bounces;
        }
        if (this.pendingParallax != null) {
            this.pendingParallax = quality.parallax;
        }
        if (this.pendingFogSamples != null) {
            this.pendingFogSamples = quality.fogSamples;
        }
    }

    /** Every setting on this screen back to how a fresh install has it. */
    private void resetToDefaults() {
        Options.resetVisualDefaults();
        this.forgetStoredSettings = true;
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
        this.pendingCloudShadows = Boolean.FALSE;
        this.pendingRestir = Boolean.TRUE;
        this.pendingSer = Boolean.FALSE;
        this.pendingFroxelFog = Pipeline.supportsShaderPackToggle(Pipeline.FROXEL_FOG_ATTRIBUTE) ? Boolean.TRUE : null;
        this.pendingRainRefraction =
            Pipeline.supportsShaderPackToggle(Pipeline.RAIN_REFRACTION_ATTRIBUTE) ? Boolean.TRUE : null;
        this.pendingSeamlessGlass =
            Pipeline.supportsShaderPackToggle(Pipeline.SEAMLESS_GLASS_ATTRIBUTE) ? Boolean.TRUE : null;
        this.pendingPackToggles.clear();
        for (String attribute : List.of(Pipeline.CACHE_DEEP_BOUNCES_ATTRIBUTE)) {
            if (Pipeline.supportsShaderPackToggle(attribute)) {
                this.pendingPackToggles.put(attribute, Boolean.TRUE);
            }
        }
        this.pendingDepthOfField = Boolean.FALSE;
        this.pendingReflex = false;
        // The shader pack's own defaults.
        this.pendingBounces = this.pendingBounces == null ? null : 4;
        this.pendingParallax = this.pendingParallax == null ? null : Boolean.TRUE;
        this.pendingBedrockAtmosphere = this.pendingBedrockAtmosphere == null ? null : Boolean.TRUE;
        this.pendingFogSamples = this.pendingFogSamples == null ? null : 16;
        this.pendingUpscalerMode = null;
        this.pendingFarBounceDistance = this.pendingFarBounceDistance == null ? null : 0;
        this.pendingFarBounces = this.pendingFarBounces == null ? null : 1;
        if (this.pendingTunables != null) {
            this.pendingTunables.replaceAll((tunable, value) -> tunable.defaultSteps);
        }
        if (this.pendingToneMethod != null) {
            this.pendingToneMethod = PictureStyle.ToneMethod.PBR_NEUTRAL;
        }
    }

    private void readTunables() {
        if (this.pendingTunables != null) {
            return;
        }
        this.pendingTunables = new java.util.EnumMap<>(Tunable.class);
        for (Tunable tunable : Tunable.ALL) {
            Integer value = tunable.read();
            if (value != null) {
                this.pendingTunables.put(tunable, value);
            }
        }
        this.pendingToneMethod = PictureStyle.ToneMethod.of(Pipeline.getModuleValue(Pipeline.TONE_MAPPING_MODULE_NAME,
            "render_pipeline.module.tone_mapping.attribute.method"));
    }

    private PictureStyle currentStyle() {
        for (PictureStyle style : PictureStyle.values()) {
            if (style.matches(this.pendingToneMethod, this.pendingTunables)) {
                return style;
            }
        }
        return PictureStyle.CUSTOM;
    }

    /** A slider for a numeric pipeline setting, or null when the pipeline lacks it. */
    private OptionInstance<Integer> tunable(Tunable tunable, boolean affectsStyle) {
        Integer current = this.pendingTunables.get(tunable);
        if (current == null) {
            return null;
        }
        return new OptionInstance<Integer>(tunable.key(), RadianteOptionsScreen.<Integer>tooltip(tunable.key()),
            (caption, value) -> tunable.format == Tunable.Format.EV
                ? Component.translatable("options.radiante.ev_value", caption,
                    String.format(java.util.Locale.ROOT, "%+.1f", value * tunable.unit))
                : Component.translatable("options.percent_value", caption, value),
            new OptionInstance.IntRange(tunable.min, tunable.max, false), current, value -> {
                this.pendingTunables.put(tunable, value);
                if (affectsStyle) {
                    refreshQualityLater();
                }
            });
    }

    /** Adds options two to a row, skipping the ones the pipeline lacks. */
    private void addRows(OptionInstance<?>... options) {
        List<OptionInstance<?>> present = new ArrayList<>();
        for (OptionInstance<?> option : options) {
            if (option != null) {
                present.add(option);
            }
        }
        this.page.addAll(present);
    }

    /** Builds every section's options, in order, into {@link #sections}. */
    private void buildPage() {

        // Filled in first so the quality level below can tell which one the current settings match.
        OptionInstance<Presets> preset = presetOption();
        OptionInstance<String> dlssMode = dlssModeOption();
        OptionInstance<Integer> frameGeneration = frameGenerationOption();
        OptionInstance<String> clouds = cloudModeOption();
        if (Pipeline.supportsVolumetricFog() && this.pendingVolumetricFog == null) {
            this.pendingVolumetricFog = Pipeline.isVolumetricFog();
        }
        if (this.pendingBounces == null && Pipeline.getShaderPackValue(Pipeline.RAY_BOUNCES_ATTRIBUTE) != null) {
            this.pendingBounces = Math.max(1, Math.min(8, Pipeline.getShaderPackInt(Pipeline.RAY_BOUNCES_ATTRIBUTE, 4)));
        }
        if (this.pendingParallax == null && Pipeline.supportsShaderPackToggle(Pipeline.PARALLAX_ATTRIBUTE)) {
            this.pendingParallax = Pipeline.isShaderPackToggleOn(Pipeline.PARALLAX_ATTRIBUTE);
        }
        if (this.pendingBedrockAtmosphere == null
            && Pipeline.supportsShaderPackToggle(Pipeline.BEDROCK_ATMOSPHERE_ATTRIBUTE)) {
            this.pendingBedrockAtmosphere = Pipeline.isShaderPackToggleOn(Pipeline.BEDROCK_ATMOSPHERE_ATTRIBUTE);
        }
        if (this.pendingFogSamples == null
            && Pipeline.getShaderPackValue(Pipeline.VOLUMETRIC_SAMPLES_ATTRIBUTE) != null) {
            this.pendingFogSamples = Math.max(4, Math.min(32,
                Pipeline.getShaderPackInt(Pipeline.VOLUMETRIC_SAMPLES_ATTRIBUTE, 16)));
        }

        readTunables();

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
        this.sections.clear();
        for (Category section : Category.values()) {
            this.page.clear();
            switch (section) {
                case QUALITY -> {
                    addRows(quality);
                    addQualityOptions(preset, dlssMode, frameGeneration);
                }
                case IMAGE -> addImageOptions();
                case LIGHTING -> addLightingOptions();
                case SKY_AND_WATER -> addSkyAndWaterOptions(clouds);
                case PERFORMANCE -> addPerformanceOptions();
                case OTHER -> addOtherOptions();
            }
            this.sections.add(new SettingsLayout.Section(section, List.copyOf(this.page)));
        }
    }

    private void addQualityOptions(OptionInstance<Presets> preset, OptionInstance<String> dlssMode,
        OptionInstance<Integer> frameGeneration) {
        OptionInstance<Boolean> reflex = null;
        // Reflex comes with Streamline, which has to be loaded before Minecraft creates its Vulkan device: the
        // first time it is turned on it takes effect after a restart, the same as frame generation.
        if (com.g2806.radiante.platform.RadiantePlatform.INSTANCE.supportsStreamline()) {
            reflex = OptionInstance.createBoolean("options.radiante.reflex",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                    RadianteClient.streamlineLoaded() ? "options.radiante.reflex.tooltip"
                        : "options.radiante.reflex.tooltip_restart")),
                this.pendingReflex, value -> {
                    this.pendingReflex = value;
                    refreshQualityLater();
                });
        }
        this.reflexToggle = reflex;
        addRows(preset, dlssMode, upscalerModeOption(), frameGenerationBackendOption(), frameGeneration, reflex);
    }

    private void addImageOptions() {
        OptionInstance<PictureStyle> style = null;
        if (this.pendingTunables.containsKey(Tunable.SATURATION)) {
            style = new OptionInstance<>("options.radiante.picture_style", tooltip("options.radiante.picture_style"),
                (caption, value) -> Component.translatable(value.key),
                new OptionInstance.Enum<>(List.of(PictureStyle.values()),
                    Codec.STRING.xmap(PictureStyle::valueOf, PictureStyle::name)),
                currentStyle(), value -> {
                    if (value == PictureStyle.CUSTOM || value == currentStyle()) {
                        return;
                    }
                    value.applyTo(this.pendingTunables);
                    if (this.pendingToneMethod != null) {
                        this.pendingToneMethod = value.method;
                    }
                    refreshQualityLater();
                });
        }
        OptionInstance<PictureStyle.ToneMethod> method = this.pendingToneMethod == null ? null
            : new OptionInstance<>("options.radiante.tone_method", tooltip("options.radiante.tone_method"),
                (caption, value) -> Component.translatable(value.key()),
                new OptionInstance.Enum<>(List.of(PictureStyle.ToneMethod.values()),
                    Codec.STRING.xmap(PictureStyle.ToneMethod::valueOf, PictureStyle.ToneMethod::name)),
                this.pendingToneMethod, value -> {
                    this.pendingToneMethod = value;
                    refreshQualityLater();
                });
        addRows(style, method, tunable(Tunable.SATURATION, true), tunable(Tunable.EXPOSURE_ADAPTATION, false),
            tunable(Tunable.EXPOSURE_BIAS, false));
        addHdrOptions();
        if (Pipeline.supportsShaderPackToggle(Pipeline.MOTION_BLUR_ATTRIBUTE)
            && Pipeline.supportsShaderPackToggle(Pipeline.DEPTH_OF_FIELD_ATTRIBUTE)) {
            if (this.pendingMotionBlur == null) {
                this.pendingMotionBlur = Pipeline.isShaderPackToggleOn(Pipeline.MOTION_BLUR_ATTRIBUTE);
            }
            if (this.pendingDepthOfField == null) {
                this.pendingDepthOfField = Pipeline.isShaderPackToggleOn(Pipeline.DEPTH_OF_FIELD_ATTRIBUTE);
            }
            addRows(
                OptionInstance.createBoolean("options.radiante.motion_blur", tooltip("options.radiante.motion_blur"),
                    this.pendingMotionBlur, value -> this.pendingMotionBlur = value),
                OptionInstance.createBoolean("options.radiante.depth_of_field",
                    tooltip("options.radiante.depth_of_field"), this.pendingDepthOfField,
                    value -> this.pendingDepthOfField = value));
        }
    }

    private void addLightingOptions() {
        addRows(brightnessSlider("options.radiante.day_brightness", Options.dayBrightness,
                value -> Options.dayBrightness = value),
            brightnessSlider("options.radiante.night_brightness", Options.nightBrightness,
                value -> Options.nightBrightness = value),
            brightnessSlider("options.radiante.emission_brightness", Options.emissionBrightness,
                value -> Options.emissionBrightness = value),
            brightnessSlider("options.radiante.held_light_brightness", Options.heldLightBrightness,
                value -> Options.heldLightBrightness = value),
            tunable(Tunable.BOUNCE_LIGHT, true), tunable(Tunable.SKY_LIGHT, true),
            OptionInstance.createBoolean("options.radiante.block_light_sampling",
                tooltip("options.radiante.block_light_sampling"), Options.blockLightSampling,
                value -> {
                    Options.blockLightSampling = value;
                    refreshQualityLater();
                }),
            restirOption(), serOption(),
            OptionInstance.createBoolean("options.radiante.held_item_light",
                tooltip("options.radiante.held_item_light"), Options.heldItemLight,
                value -> Options.heldItemLight = value),
            slider("options.radiante.entity_light_reach", 8, 256, Options.entityLightReach,
                value -> Options.setEntityLightReach(value, true)),
            OptionInstance.createBoolean("options.radiante.pixel_lighting",
                tooltip("options.radiante.pixel_lighting"), Options.pixelLighting, value -> Options.pixelLighting = value),
            OptionInstance.createBoolean("options.radiante.first_person_shadow",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.radiante.first_person_shadow.tooltip")),
                this.pendingFirstPersonShadow, value -> this.pendingFirstPersonShadow = value));
    }

    private void addSkyAndWaterOptions(OptionInstance<String> clouds) {
        OptionInstance<Boolean> atmosphere = this.pendingBedrockAtmosphere == null ? null
            : OptionInstance.createBoolean("options.radiante.atmosphere_style",
                tooltip("options.radiante.atmosphere_style"),
                (caption, value) -> Component.translatable(value ? "options.radiante.atmosphere_style.bedrock"
                    : "options.radiante.atmosphere_style.java"),
                this.pendingBedrockAtmosphere, value -> this.pendingBedrockAtmosphere = value);
        OptionInstance<Boolean> volumetricFog = null;
        OptionInstance<Integer> volumetricStrength = null;
        OptionInstance<Boolean> fogStyle = null;
        if (Pipeline.supportsVolumetricFog()) {
            volumetricFog = OptionInstance.createBoolean("options.radiante.volumetric_fog",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.radiante.volumetric_fog.tooltip")),
                this.pendingVolumetricFog, value -> {
                    this.pendingVolumetricFog = value;
                    refreshQualityLater();
                });
            if (Pipeline.supportsShaderPackToggle(Pipeline.FROXEL_FOG_ATTRIBUTE)) {
                if (this.pendingFroxelFog == null) {
                    this.pendingFroxelFog = Pipeline.isShaderPackToggleOn(Pipeline.FROXEL_FOG_ATTRIBUTE);
                }
                fogStyle = OptionInstance.createBoolean("options.radiante.fog_style", tooltip("options.radiante.fog_style"),
                    (caption, value) -> Component.translatable(value ? "options.radiante.fog_style.froxel"
                        : "options.radiante.fog_style.march"),
                    this.pendingFroxelFog, value -> this.pendingFroxelFog = value);
            }
            volumetricStrength = brightnessSlider("options.radiante.volumetric_fog_strength",
                Options.volumetricFogStrength, value -> Options.volumetricFogStrength = value);
        }
        OptionInstance<Boolean> cloudShadows = null;
        if (Pipeline.supportsShaderPackToggle(Pipeline.CLOUD_SHADOWS_ATTRIBUTE)) {
            if (this.pendingCloudShadows == null) {
                this.pendingCloudShadows = Pipeline.isShaderPackToggleOn(Pipeline.CLOUD_SHADOWS_ATTRIBUTE);
            }
            cloudShadows = OptionInstance.createBoolean("options.radiante.cloud_shadows",
                tooltip("options.radiante.cloud_shadows"), this.pendingCloudShadows,
                value -> this.pendingCloudShadows = value);
        }
        OptionInstance<Boolean> rainRefraction = null;
        if (Pipeline.supportsShaderPackToggle(Pipeline.RAIN_REFRACTION_ATTRIBUTE)) {
            if (this.pendingRainRefraction == null) {
                this.pendingRainRefraction = Pipeline.isShaderPackToggleOn(Pipeline.RAIN_REFRACTION_ATTRIBUTE);
            }
            rainRefraction = OptionInstance.createBoolean("options.radiante.rain_refraction",
                tooltip("options.radiante.rain_refraction"), this.pendingRainRefraction,
                value -> this.pendingRainRefraction = value);
        }
        OptionInstance<Boolean> seamlessGlass = null;
        if (Pipeline.supportsShaderPackToggle(Pipeline.SEAMLESS_GLASS_ATTRIBUTE)) {
            if (this.pendingSeamlessGlass == null) {
                this.pendingSeamlessGlass = Pipeline.isShaderPackToggleOn(Pipeline.SEAMLESS_GLASS_ATTRIBUTE);
            }
            seamlessGlass = OptionInstance.createBoolean("options.radiante.seamless_glass",
                tooltip("options.radiante.seamless_glass"), this.pendingSeamlessGlass,
                value -> this.pendingSeamlessGlass = value);
        }
        addRows(atmosphere, clouds, cloudShadows, tunable(Tunable.SUN_GLOW, false),
            tunable(Tunable.LIGHT_SHAFTS, false),
            OptionInstance.createBoolean("options.radiante.vanilla_sun_path",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.radiante.vanilla_sun_path.tooltip")),
                Options.vanillaSunPath, value -> Options.vanillaSunPath = value),
            OptionInstance.createBoolean("options.radiante.vanilla_celestial_orientation",
                OptionInstance.cachedConstantTooltip(
                    Component.translatable("options.radiante.vanilla_celestial_orientation.tooltip")),
                Options.vanillaCelestialOrientation, value -> Options.vanillaCelestialOrientation = value),
            OptionInstance.createBoolean("options.radiante.rain_wetness",
                tooltip("options.radiante.rain_wetness"), Options.rainWetness,
                value -> Options.rainWetness = value),
            rainRefraction, seamlessGlass,
            OptionInstance.createBoolean("options.radiante.biome_fog",
                OptionInstance.cachedConstantTooltip(Component.translatable("options.radiante.biome_fog.tooltip")),
                this.pendingBiomeFog, value -> this.pendingBiomeFog = value),
            new OptionInstance<>("options.radiante.biome_fog_strength", tooltip("options.radiante.biome_fog_strength"),
                (caption, value) -> Component.translatable("options.percent_value", caption, value),
                new OptionInstance.IntRange(0, 400, false), this.pendingBiomeFogStrength,
                value -> this.pendingBiomeFogStrength = value),
            volumetricFog, fogStyle, volumetricStrength,
            tunable(Tunable.WATER_WAVES, false), tunable(Tunable.WATER_DENSITY, false),
            tunable(Tunable.WATER_GOD_RAYS, false));
    }

    /** An on/off shader pack setting applied with the rest; null where the pack lacks it. */
    private OptionInstance<Boolean> packToggle(String attribute, String key) {
        if (!Pipeline.supportsShaderPackToggle(attribute)) {
            return null;
        }
        boolean value = this.pendingPackToggles.computeIfAbsent(attribute, Pipeline::isShaderPackToggleOn);
        return OptionInstance.createBoolean(key, tooltip(key), value,
            newValue -> this.pendingPackToggles.put(attribute, newValue));
    }

    private void addPerformanceOptions() {
        OptionInstance<Integer> bounces = this.pendingBounces == null ? null
            : slider("options.radiante.ray_bounces", 1, 8, this.pendingBounces, value -> {
                this.pendingBounces = value;
                refreshQualityLater();
            });
        OptionInstance<Boolean> parallax = this.pendingParallax == null ? null
            : OptionInstance.createBoolean("options.radiante.parallax", tooltip("options.radiante.parallax"),
                this.pendingParallax, value -> {
                    this.pendingParallax = value;
                    refreshQualityLater();
                });
        OptionInstance<Integer> fogSamples = this.pendingFogSamples == null || !Pipeline.supportsVolumetricFog() ? null
            : slider("options.radiante.volumetric_fog_samples", 4, 32, this.pendingFogSamples, value -> {
                this.pendingFogSamples = value;
                refreshQualityLater();
            });
        if (this.pendingFarBounceDistance == null
            && Pipeline.getShaderPackValue(Pipeline.FAR_BOUNCE_DISTANCE_ATTRIBUTE) != null) {
            this.pendingFarBounceDistance = Pipeline.getShaderPackInt(Pipeline.FAR_BOUNCE_DISTANCE_ATTRIBUTE, 0);
            this.pendingFarBounces = Pipeline.getShaderPackInt(Pipeline.FAR_BOUNCES_ATTRIBUTE, 1);
        }
        OptionInstance<Integer> farDistance = this.pendingFarBounceDistance == null ? null
            : new OptionInstance<Integer>("options.radiante.far_bounce_distance",
                tooltip("options.radiante.far_bounce_distance"),
                (caption, value) -> value == 0 ? Component.translatable("options.generic_value", caption,
                    Component.translatable("options.off"))
                    : Component.translatable("options.radiante.chunks_value", caption, value),
                new OptionInstance.IntRange(0, 32, false), this.pendingFarBounceDistance / 16,
                value -> this.pendingFarBounceDistance = value * 16);
        OptionInstance<Integer> farBounces = this.pendingFarBounces == null ? null
            : slider("options.radiante.far_bounces", 1, 4, this.pendingFarBounces,
                value -> this.pendingFarBounces = value);
        addRows(bounces, packToggle(Pipeline.CACHE_DEEP_BOUNCES_ATTRIBUTE, "options.radiante.cache_deep_bounces"),
            parallax, farDistance, farBounces, fogSamples,
            slider("options.radiante.chunk_building_threads", 1, Options.getMaxChunkBuildingThreads(),
                this.pendingChunkThreads, value -> this.pendingChunkThreads = value),
            slider("options.radiante.chunk_building_batch_size", 1, 64, this.pendingChunkBatchSize,
                value -> this.pendingChunkBatchSize = value),
            slider("options.radiante.chunk_building_total_batches", 1, 64, this.pendingChunkTotalBatches,
                value -> this.pendingChunkTotalBatches = value),
            OptionInstance.createBoolean("options.radiante.collect_chunk_emission",
                tooltip("options.radiante.collect_chunk_emission"), this.pendingCollectEmission,
                value -> this.pendingCollectEmission = value));
    }

    private void addOtherOptions() {
        addRows(OptionInstance.createBoolean("options.radiante.block_outline",
                tooltip("options.radiante.block_outline"), Options.blockOutline,
                value -> Options.blockOutline = value),
            OptionInstance.createBoolean("options.radiante.parallax_transparent_edges",
                tooltip("options.radiante.parallax_transparent_edges"), Options.parallaxTransparentEdges,
                value -> Options.parallaxTransparentEdges = value),
            OptionInstance.createBoolean("options.radiante.debug_logging",
                this.pendingDebugLogging, value -> this.pendingDebugLogging = value));
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
                refreshQualityLater();
            });
        this.hdrToggle = toggle;
        if (!Options.hdrOutput) {
            addRows(toggle);
            return;
        }
        addRows(toggle, nitsSlider("options.radiante.hdr_peak", 400, 4000, Options.hdrPeakNits,
                value -> Options.hdrPeakNits = value),
            nitsSlider("options.radiante.hdr_paper_white", 80, 400, Options.hdrPaperWhiteNits,
                value -> Options.hdrPaperWhiteNits = value),
            OptionInstance.createBoolean("options.radiante.hdr_debug_view",
                tooltip("options.radiante.hdr_debug_view"), Options.hdrDebugView,
                value -> Options.hdrDebugView = value));
    }

    private static OptionInstance<Integer> nitsSlider(String key, int min, int max, int current,
        java.util.function.Consumer<Integer> onChange) {
        return new OptionInstance<Integer>(key, RadianteOptionsScreen.<Integer>tooltip(key),
            (caption, value) -> Component.translatable("options.radiante.nits_value", caption, value),
            new OptionInstance.IntRange(min, max, false), Math.max(min, Math.min(max, current)), onChange::accept);
    }

    /** A setting the quality level covers was changed by hand: the level shown above it has to follow. */
    private void refreshQualityLater() {
        // Once the mouse is let go: laying the screen out again under a slider being dragged dropped the drag, so
        // those sliders only moved by clicking where the value should be.
        this.refreshPending = true;
    }

    private boolean refreshPending;

    @Override
    protected void init() {
        buildPage();
        this.layout = new SettingsLayout(this.font, this.width, this.height, this.sections,
            new SettingsLayout.Actions(
                () -> {
                    resetToDefaults();
                    reopenWithSameChoices();
                },
                () -> {
                    // Undo: a fresh screen reads everything back from what is applied.
                    this.applied = true;
                    RadianteOptionsScreen fresh = new RadianteOptionsScreen(this.lastScreen, this.options);
                    fresh.scroll = this.layout.scroll();
                    this.minecraft.gui.setScreen(fresh);
                },
                () -> {
                    // The fresh screen reads the settings back when it is built, so only once they are applied:
                    // built before, it showed (and on leaving wrote back) the old frame generation, Reflex and the
                    // other values kept in Options, so a change had to be applied twice to stick.
                    double scroll = this.layout.scroll();
                    applyAndOpen(() -> {
                        RadianteOptionsScreen fresh = new RadianteOptionsScreen(this.lastScreen, this.options);
                        fresh.scroll = scroll;
                        return fresh;
                    });
                },
                this::onClose),
            value -> this.scroll = value);
        this.layout.restartInfo(new SettingsLayout.RestartInfo() {
            @Override
            public boolean needsRestart(OptionInstance<?> option) {
                return option == hdrToggle || option == reflexToggle;
            }

            @Override
            public boolean pending(OptionInstance<?> option) {
                if (option == hdrToggle) {
                    return hdrPending();
                }
                if (option == reflexToggle) {
                    return streamlinePending();
                }
                return false;
            }
        });
        addRenderableWidget(this.layout.init(this.scroll, this.search));
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY,
        float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.layout != null) {
            this.layout.extract(graphics, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        return this.layout != null && this.layout.mouseClicked(event.x(), event.y(), event.button());
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        if (this.layout != null && this.layout.mouseDragged(event.x())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (this.layout != null) {
            this.layout.mouseReleased();
        }
        if (this.refreshPending) {
            this.refreshPending = false;
            reopenWithSameChoices();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.layout != null && this.layout.scrollBy(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        applyAndOpen(this.lastScreen);
    }

    /** Applies what was changed; true when the pipeline has to be rebuilt (left to the caller, see applyAndOpen). */
    private boolean applyChanges() {
        if (this.applied) {
            return false;
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
        if (FrameGeneration.maxGeneratedFrames() <= 0) {
            this.pendingGeneratedFrames = 0;
        }
        Options.frameGenerationBackend = this.pendingFrameGenerationBackend;
        FrameGeneration.applyBackend();
        this.pendingGeneratedFrames = Math.min(this.pendingGeneratedFrames, FrameGeneration.maxGeneratedFrames());
        boolean wantsFrameGeneration = this.pendingGeneratedFrames > 0;
        if (wantsFrameGeneration != Options.frameGeneration || this.pendingGeneratedFrames != Options.generatedFrames) {
            Options.frameGeneration = wantsFrameGeneration;
            Options.generatedFrames = Math.max(1, this.pendingGeneratedFrames);
            FrameGeneration.setGeneratedFrames(this.pendingGeneratedFrames);
        }
        Options.overwriteConfig();

        boolean rebuild = false;
        if (this.forgetStoredSettings) {
            // Every shader pack setting back to the pack's defaults, those without a place on this screen included;
            // the ones on it are applied over them below.
            Pipeline.resetToDefaults();
            rebuild = true;
        }
        if (this.pendingPreset != null
            && !Objects.equals(this.pendingPreset.key, Pipeline.INSTANCE.getActivePresetName())) {
            Pipeline.switchToPresetMode(this.pendingPreset.key, false);
            rebuild = true;
        }
        if (this.pendingVolumetricFog != null) {
            rebuild |= Pipeline.setVolumetricFog(this.pendingVolumetricFog);
        }
        if (this.pendingBounces != null) {
            rebuild |= Pipeline.setShaderPackValue(Pipeline.RAY_BOUNCES_ATTRIBUTE, String.valueOf(this.pendingBounces));
        }
        if (this.pendingBedrockAtmosphere != null) {
            rebuild |= Pipeline.setShaderPackToggle(Pipeline.BEDROCK_ATMOSPHERE_ATTRIBUTE, this.pendingBedrockAtmosphere);
        }
        if (this.pendingParallax != null) {
            rebuild |= Pipeline.setShaderPackToggle(Pipeline.PARALLAX_ATTRIBUTE, this.pendingParallax);
        }
        if (this.pendingFogSamples != null) {
            rebuild |= Pipeline.setShaderPackValue(Pipeline.VOLUMETRIC_SAMPLES_ATTRIBUTE,
                String.valueOf(this.pendingFogSamples));
        }
        if (this.pendingTunables != null) {
            for (java.util.Map.Entry<Tunable, Integer> entry : this.pendingTunables.entrySet()) {
                rebuild |= entry.getKey().write(entry.getValue());
            }
        }
        if (this.pendingToneMethod != null) {
            rebuild |= Pipeline.setModuleValue(Pipeline.TONE_MAPPING_MODULE_NAME,
                "render_pipeline.module.tone_mapping.attribute.method", this.pendingToneMethod.value);
        }
        if (this.pendingMotionBlur != null) {
            rebuild |= Pipeline.setShaderPackToggle(Pipeline.MOTION_BLUR_ATTRIBUTE, this.pendingMotionBlur);
        }
        if (this.pendingSer != null) {
            rebuild |= Pipeline.setShaderPackToggle(Pipeline.SER_ATTRIBUTE, this.pendingSer);
        }
        if (this.pendingFroxelFog != null) {
            rebuild |= Pipeline.setShaderPackToggle(Pipeline.FROXEL_FOG_ATTRIBUTE, this.pendingFroxelFog);
        }
        if (this.pendingRainRefraction != null) {
            rebuild |= Pipeline.setShaderPackToggle(Pipeline.RAIN_REFRACTION_ATTRIBUTE, this.pendingRainRefraction);
        }
        for (java.util.Map.Entry<String, Boolean> toggle : this.pendingPackToggles.entrySet()) {
            rebuild |= Pipeline.setShaderPackToggle(toggle.getKey(), toggle.getValue());
        }
        if (this.pendingSeamlessGlass != null) {
            rebuild |= Pipeline.setShaderPackToggle(Pipeline.SEAMLESS_GLASS_ATTRIBUTE, this.pendingSeamlessGlass);
        }
        if (this.pendingRestir != null) {
            rebuild |= Pipeline.setShaderPackToggle(Pipeline.RESTIR_ATTRIBUTE, this.pendingRestir);
        }
        if (this.pendingCloudShadows != null) {
            rebuild |= Pipeline.setShaderPackToggle(Pipeline.CLOUD_SHADOWS_ATTRIBUTE, this.pendingCloudShadows);
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
        if (this.pendingUpscalerMode != null && upscalerModes() != null) {
            rebuild |= Pipeline.setModuleValue(upscalerModule(), upscalerAttribute(), this.pendingUpscalerMode);
        }
        if (this.pendingFarBounceDistance != null) {
            rebuild |= Pipeline.setShaderPackValue(Pipeline.FAR_BOUNCE_DISTANCE_ATTRIBUTE,
                String.valueOf(this.pendingFarBounceDistance));
            rebuild |= Pipeline.setShaderPackValue(Pipeline.FAR_BOUNCES_ATTRIBUTE, String.valueOf(this.pendingFarBounces));
        }
        if (rebuild) {
            Pipeline.savePipeline();
        }
        return rebuild;
    }

    /** Opens the next screen, through the rebuild's wait screen when the pipeline has to be built again. */
    private void applyAndOpen(Screen next) {
        applyAndOpen(() -> next);
    }

    /** {@code next} is only made after the changes are applied, so a new settings screen shows them. */
    private void applyAndOpen(java.util.function.Supplier<Screen> nextAfterApply) {
        List<Component> restart = restartChanges();
        boolean rebuild = applyChanges();
        Screen next = nextAfterApply.get();
        if (!restart.isEmpty()) {
            next = new RestartRequiredScreen(next, restart);
        }
        if (rebuild) {
            this.minecraft.gui.setScreen(new ApplyingSettingsScreen(next));
        } else {
            this.minecraft.gui.setScreen(next);
        }
    }
}
