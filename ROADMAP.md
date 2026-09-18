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

## NameTag support

Entity name tags aren't submitted through the ray tracer yet and don't render.
Likely the same `submitText`/`TextRenderable` path used for signs, but for
billboarded text that always faces the camera — needs its own investigation,
signs don't face the camera.

## Moving block geometry

Falling sand/gravel, moving pistons, and primed TNT don't render correctly.
These are all entity-like block renders (`FallingBlockRenderState`,
`PistonMovingBlockRenderState`, `PrimedTntRenderState`) that go through
`EntityCollector`/`EntityManager` already, but each likely has its own quirk
(interpolated block model in the case of the piston head, a spinning/growing
billboard for TNT) that hasn't been tested against yet.

## Per-biome fog/ambiance

Bedrock RTX tints fog and ambient light by biome (desert reads warm and hazy,
swamp reads green and thick, etc). Nothing in the current pipeline does this —
fog is currently a flat color/distance pair set once per frame
(`RadianteRenderer.updateUniforms`, `WorldUBO.fogColor/fogStart/fogEnd`), with
no per-biome variation. Would need biome color data threaded from Java into a
new uniform, then read in the fog/sky shaders.

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
and the moon's cool tint is now a look question rather than a bug. Worth a second
opinion in-game before touching.

Adjacent observation, not changed: the sun's angular size is small next to vanilla's.
`evalSunBillboard` uses `tan(0.03)` (~3.4° across) while vanilla draws the sun quad
at roughly 17°. The bloom hides most of it, but it is a real difference if anyone
wants the vanilla read.

## Also open from earlier sessions (not new, just not yet done)

- NRD denoiser is visibly noisier than DLSS Ray Reconstruction — confirmed and
  measured (99.9% drop in indirect-light coverage vs. DLSS-RR on the same
  scene), root cause not yet found. Blocks offering NRD-based presets
  (FSR/XeSS/plain NRD) as a real alternative to DLSS.
- Two `VK_ERROR_DEVICE_LOST` crashes reported (`Failed to wait for semaphore`),
  not yet reproduced/diagnosed.
- Held-item light (a torch in hand doesn't light its surroundings, only itself).
- Vanilla (non-volumetric) cloud rendering — needs decoding Minecraft's packed
  cloud face format.
- Weather, block selection outline, block-breaking animation, enchantment
  glint, damage flash — not ported yet.
