# Radiante — Roadmap

Post-0.1.0 items, roughly in the order they came up. Nothing here is scheduled; it's
a backlog, not a promise.

## Investigate: backport to 26.1

Check whether the render pearl Vulkan backend (`com.mojang.renderpearl`) exists in
26.1, and if the mixin targets (`VulkanInstance`, `VulkanBackend`, `VulkanDevice`,
`FrontendCommandEncoder`, the `LevelRenderState`/`SubmitNodeCollector` extraction
path) are stable enough between 26.1 and 26.3 to share a codebase, or whether it
needs its own mixin set behind a version-specific module. This is exploratory:
the answer could be "not worth it" if the two versions diverge too much.

## NameTag support — implemented, pending in-game check

`EntityCollector.submitNameTag` was empty. It now places the tag like vanilla
(attachment point + 0.5, rotated by `camera.orientation`, scaled 0.025) and
writes it through the sign text path in the `POLYGON_OFFSET` layer, so the
text any-hit shader cuts the letters out. The letters are mildly emissive so
they read in the dark. The translucent backing plate is vanilla's colour; the
text any-hit shader accepts partly transparent text surfaces stochastically
(hit with probability = alpha), and name tag layers are submitted as their own
instance under the particle mask, which shadow rays skip — so neither the
plate nor the letters cast shadows. Vanilla's see-through copy is not drawn.

## Moving block geometry — fixed, pending in-game check

Primed TNT already worked (it submits a block model). Falling blocks and
piston-moved blocks (including the extending head) go through
`SubmitNodeCollector.submitMovingBlock`, which `EntityCollector` left empty on
the wrong assumption that the terrain pass covered them — the section only holds
an invisible `moving_piston` (or air) while they move. It now tesselates them
with a `ModelBlockRenderer` the way vanilla's `MovingBlockFeatureRenderer` does
and writes the quads into the solid/cutout/translucent moving-block layers.

## Per-biome fog/ambiance — implemented, pending in-game tuning

Option "Biome Fog" (`Options.biomeFog`, on by default). `BiomeAmbiance` samples
a 3x3 grid of biomes 12 blocks apart around the camera, maps each to a tint and
an extinction per block (vanilla keys first, then biome tags, then climate for
modded biomes), eases the result in over ~2 s, thickens it with rain, and
scales it by the camera's sky light so caves stay clear. It reaches the shaders
as `SkyUBO.biomeFog` (rgb tint, a density). Both packs apply it in `world.rgen`
as an extra exponential haze lit by the sky's horizon colour, folded into the
DLSS-RR output and the NRD compose fog alike; the volumetric branch now
combines with it instead of overwriting it. Values are our own, not copied
from any Bedrock pack. Also in the Nether and the End: there is no sky to light
it, so the haze colour is the biome's vanilla fog colour (per biome, blended by
vanilla) times a small tint, with the End lifted off near-black. A "Biome Fog
Strength" slider (0-400 %) scales every density. With Debug Logging on, the
log prints the current biome, colour, density and sky exposure every 5 s.

"Volumetric Fog" option (the packs' `volumetric_light_mode`): the overworld
biome fog becomes part of the ray-marched medium — extinction plus in-scatter
of the sun/moon through the march's shadow rays (light shafts) and of the sky
(ambient), HG g=0.55 blended with isotropic. vanilla-pt integrates it in
`integrateVolumetricFog` and continues it analytically past the march to the
far terrain/horizon; advanced adds it to `volumetric_light.rgen` and to the
transmittance in `world.rgen`, with the plain haze covering the stretch past
the march. Plain haze still used when the option is off and in Nether/End.

## Advanced settings menu

Bring back something like Radiance's fuller customization screen — the current
`RadianteOptionsScreen` only exposes a handful of high-level toggles (preset,
shader pack, chunk building, debug logging). Radiance's had per-module
attribute editing exposed directly. Needs a design decision: expose the raw
module/attribute graph, or a curated set of the ones that actually matter to
players (ray bounces, denoiser strength, etc).

## Sun and moon color — DONE

Root cause was not the tint: `RadianteRenderer` passed `0, 0` as `sunTextureId` and
`moonTextureId`, and id 0 is the 1x1 white fallback texture. Neither body had ever
sampled its real texture, so the sun was a white square and the moon was the same
white square with no phases.

Compounding it, the shader indexed the moon as a fixed 4x2 grid — the layout of
1.21's `moon_phases.png`. In 26.3 the eight phases are separate files under
`environment/celestial/moon/` that the game stitches into a runtime `celestials`
atlas, so no fixed grid could ever have been right.

Fixed by adding `sunUvRect`/`moonUvRect` to `SkyUBO`, resolving the real sprites on
the Java side (`atlas.getSprite("sun")` and `"moon/" + phase.getSerializedName()`,
the same lookup `SkyRenderer` uses), and sampling those rectangles in both shader
packs via a new `sampleSpriteLod0`. Verified: the sun renders as a yellow disc, and
three consecutive in-game days show three distinct moon phases.

Left alone deliberately: `sun_radiance` (`vec3(8,8,8)`) and `moon_radiance`
(`vec3(0.64,0.8,1.6)`) are unchanged. With real textures the colour reads correctly,
and the moon's cool tint is now a look question rather than a bug.

Tried and reverted: softening the amber rim of `sun.png`, which the path tracer shows
because the rim's blue channel is under a third of its red while vanilla blows the
whole disc out to white. Mixing the disc toward white lit the sprite's dark padding
and turned the sun into a white blob; desaturating toward luminance fixed that but
drained the warmth that makes the sun read as the sun. Both looked worse than the
rim. The sprite is drawn as-is.

Angular size was wrong too, and is now fixed. Vanilla builds both bodies as a quad
spanning -1..1, scaled and pushed out by `applyCelestialBodyTransform(pose, 100, s)`
with `s` = 30 for the sun and 20 for the moon, so the half-angle's tangent is `s/100`.
The shader had `tan(0.03)` and `tan(0.05)`, leaving the sun ten times and the moon
four times too small. Both now use `30.0/100.0` and `20.0/100.0`.

Verified by rendering the same camera with ray tracing off, so Minecraft drew the sky
itself, and comparing crops: the sun's disc and the moon's disc are the same size in
both renderers. The moon's "hollow square" look is not a bug either - it is the real
`new_moon` sprite, and vanilla draws the identical silhouette.

## Also open from earlier sessions (not new, just not yet done)

- NRD denoiser is visibly noisier than DLSS Ray Reconstruction — confirmed and
  measured (99.9% drop in indirect-light coverage vs. DLSS-RR on the same
  scene), root cause not yet found. Blocks offering NRD-based presets
  (FSR/XeSS/plain NRD) as a real alternative to DLSS.
- `VK_ERROR_DEVICE_LOST` / 5 s semaphore timeout a few seconds into a world:
  reproduced, and it is DLSS Ray Reconstruction at **Ultra Performance** —
  clean HEAD crashes with it, Balanced and Performance run. Worked around (mode
  hidden in the options, mapped to Performance natively); the root cause of the
  GPU hang is still open. Older reports may have been the same thing.
- Held-item light (a torch in hand doesn't light its surroundings, only itself).
- Vanilla (non-volumetric) cloud rendering — needs decoding Minecraft's packed
  cloud face format.
- Block selection outline, block-breaking animation, enchantment glint, damage
  flash — not ported yet. (Weather is in: rain/snow sheets from
  `WeatherRenderState`, weather mask, new `ALPHA_MODE_STOCHASTIC` = 9.)
