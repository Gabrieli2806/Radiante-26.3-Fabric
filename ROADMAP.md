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

## Far render distance performance — done

Measured at 32 chunks, 1080p, RTX 5070 12 GB: 22 fps while loading and ~7 fps once
loaded, because video memory filled up (11.7 of 12.2 GB) and spilled. Now ~90 fps
steady, ~10.2 GB in use. What changed:
- `PackedMaterialVertex`: material vertices 80 -> 40 bytes (unpacked in
  `loadTriangleMaterial`); material buffers were 5.4 of ~9 GB.
- Chunk BLAS built with `ALLOW_COMPACTION` and swapped for compacted copies once
  their batch finishes (`Chunks::compactFinishedBlases`), ~35% smaller.
- Per-frame CPU: chunk instance data cached between chunk changes, chunks no
  longer re-retained every frame, hit-group names by pointer with a small
  resolve cache, per-frame metadata buffers reused, empty slots skipped via a
  flat `occupied` array. World prepare went from ~38 ms to ~10 ms.
- Java: `ChunkManager` reacts to the sections the tracker reports changed
  (`SectionDirtyStateMixin`) instead of walking ~100k sections a frame, and
  checks neighbour readiness once per column.
Set `RADIANTE_DEV_PROFILE=1` to log per-step timings and chunk memory.

Second pass (compact chunk geometry, `packChunkGeometry` in chunks.cpp):
16-bit indices, half-float positions relative to the section (exact on the 1/16
block grid), and a per-geometry material header plus 20 bytes per vertex when
the geometry's texture/flags/emission are uniform (terrain always is). Tagged
by the low bit of each buffer address; `util/vertex.glsl` decodes it, entities
keep the general layout. Chunk geometry went from ~4.2 GB to ~2.2 GB at 32
chunks (process ~7.6 -> ~5.6 GB). Each chunk now owns one geometry buffer and
each compacted BLAS its own buffer, so rebuilding a chunk frees exactly its
memory instead of pinning its whole build batch (slow growth over long
sessions). Still possible: fewer TLAS instances by merging sections.

## Frosted glass (inherited from Radiance) — fixed

Glass, stained glass and panes read as blurry/frosted instead of clear.
`convertLabPBRMaterial` (`util/labpbr.glsl`) derives roughness from the specular
map's red channel, and vanilla blocks have no specular map: the sample comes
back all zeros, which decodes as a fully rough surface. Combined with
`texAlbedo.a < 1` setting `transmission = 1`, light passing through scattered in
every direction, so nothing behind the block was visible — only its tint.
Now a transmissive surface whose specular sample is entirely zero (nothing
authored for it) is treated as smooth glass: roughness 0.02, f0 0.04, ior 1.5.
Resource packs that do author a specular map keep their own values. Applies to
both shader packs, and to ice and other see-through blocks. Verified A/B on the
same scene: with the fix the wall behind lime stained glass and red panes is
visible through them; before it was not.

## Crash when resource packs change — hazard fixed, crash not reproduced

Removing resource packs crashed the game a few seconds after the reload with
`IllegalStateException: 5s timeout reached when waiting for VK semaphore`
(a GPU hang, reported by Minecraft's own submit).

A resource reload destroys and recreates every atlas. `Textures::initializeTexture`
created the replacement image and bound it into the descriptor table right away,
but an image only reaches `VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL` when its
first upload transitions it, and the atlas contents are stitched back several
frames later. In between, the ray tracing shaders sampled images still in
`VK_IMAGE_LAYOUT_UNDEFINED` — undefined behaviour the driver may fault on, which
is what a hung queue looks like. New images are now cleared and transitioned in
the next upload flush, before anything can sample them.

Not confirmed as the cause: about twenty pack add/remove cycles never reproduced
the hang here (neither did a validation-layer run, which reports no errors across
a reload), so the fix is a removed hazard rather than a verified repro. A second
reload-window hazard is still open: texture descriptors are rewritten while
frames that may sample them are in flight, legal only under
`UPDATE_UNUSED_WHILE_PENDING` if the descriptor really is unused. Reusing the
existing image when size, format and mip count are unchanged would close both.

`RADIANTE_DEV_SCRIPT` gained `reload` and `packs=a,b` / `packs=none` for this.

## Hand and held items at other field of view settings — fixed

The hand grew as the FOV setting went down and shrank as it went up, and only
looked right at 70. Minecraft draws the first person hand in a pass of its own
with a fixed field of view - `Camera.calculateHudFov` is 70 degrees whatever the
setting, and `GameRenderer.render3dHud` builds the hand's projection from it -
so in vanilla the hand keeps its size while the world opens up or narrows. A
path tracer shoots one ray per pixel and had the hand sharing the world's
frustum, which is the whole of the difference.

`WorldUBO.handFovScale` (the last padding word, so the layout is unchanged)
carries `tan(hudFov/2) / tan(fov/2)`, and the primary rays that look for the
hand are widened or narrowed by it in both packs. It is 1 at 70 with no FOV
effects, and the hand measures the same on screen at 30, 70 and 110.

## Glowing mobs and the glowing effect — done

A glow squid was as dark as any other mob and the glowing effect did nothing.
Neither has geometry or a texture that says it glows: vanilla lights a glow
squid by overriding the block light it hands the renderer (`GlowSquidRenderer`
returns 15, as do the blaze and the magma cube) and draws the glowing effect as
an outline in a post effect this renderer never runs.

`EntityManager` now derives both as emission. Whatever an entity's own block
light exceeds the brightest block light around it (feet, middle, head) counts as
self-illumination, scaled to `SELF_LIT_EMISSION` at a full 15 and ignored below a
gap of 5 so a mob standing by a torch does not glow. `EntityRenderState
.appearsGlowing()` adds `GLOWING_EMISSION`. `EntityCollector` also used to drop
every submission that carried an outline colour, which is what made a glowing
player or mob vanish entirely: vanilla still draws the model and puts the
silhouette on top, so the models are now written as usual and the outline colour
is ignored.

### Still wanted: the real outline, in the team's colour, through walls

The glow is not what vanilla draws. Vanilla outlines the entity in its team's
colour and shows that outline through blocks. An attempt at it is reverted but
worth writing down, because most of the way is mapped:

- The packs already describe post render passes that rasterise entity geometry
  over the traced image (`content` is one of weather, particle, text, name_tag,
  star, selected by `EntityBuildData::postRenderFlag`). Nothing in this fork
  sets that flag - `EntityManager` writes zeros - so the path has never drawn
  anything here. Setting it does reach the draw loop; the draw calls are issued.
- Full screen post passes do reach the presented image (confirmed by tinting
  `out:ldr` from one). Render passes can only target `out:ldr` and
  `out:first_hit_depth`, so a silhouette mask of its own is not available;
  the outline has to be drawn as an expanded hull and the middle then put back
  from a copy of the image taken before it.
- A raster pass cannot use `WorldUBO.cameraEffectedViewMat` as it stands:
  `RadianteRenderer.toRendererView` negates x and z for the tracer's
  convention, and a triangle transformed with it lands behind the camera.
  Multiplying by `diag(-1, 1, -1, 1)` again fixes that, and with it the
  silhouette draws, takes the outline colour from the vertices and shows
  through walls.
- What is left is placement: the copy lands too far away and too high. Entity
  layers carry `coordinate = CAMERA` per vertex while the acceleration
  structure instance places them as `WORLD` (`world_prepare.cpp`), and
  `WorldUBO.cameraPos` reads as zero in the post pass, so neither `postBase`
  nor a position baked in on the Java side put the geometry where the traced
  entity is. Sorting out which space post render geometry is meant to be in is
  the next step. Emissive entity surfaces light the
world around them, so a glow squid now casts a pool of cyan light on the ground.
The effect is a glow on the mob rather than vanilla's silhouette through walls.

## Out of bounds texture uploads — fixed, pending confirmation

Sync validation while entering a world caught
`vkCmdCopyBufferToImage(): pRegions[0].imageOffset.x (0) + extent.width (64)
exceeds imageSubresource width extent (16)` — a copy writing past the end of a
16x16 image, which is memory corruption the driver may answer with a device
loss. Minecraft closes and creates textures constantly, and `TextureTracker`
hands a closed texture's id to the next one; uploads already queued for the old
image were then replayed into the new, smaller one. `Textures::initializeTexture`
now drops the queued regions and resets the staging cache for the id it replaces,
and `queueUpload` refuses (and logs) any copy that does not fit the mip level it
targets. Whether this is the `VK_ERROR_DEVICE_LOST` seen on joining a world is
not confirmed: that crash reproduced once in about a dozen scripted joins.

## Lit redstone lamps cast no light — fixed

A lit redstone lamp was dark with vanilla textures and correct with a PBR pack.
Radiante ships its own LabPBR specular maps for 154 vanilla textures, and
`EmissionTiles` treated "any authored map exists" as "the pack knows better",
dropping the emission it derives from the albedo for *every* block. Anything
without a built in map - `redstone_lamp_on`, `beacon` - then had no emission at
all: the glow the shader reads comes from the specular map's alpha, and the
lamp had none. A custom pack that covers the lamp supplied it, which is why it
only looked broken on vanilla.

The derived emission is now written first and authored maps are stitched over
it (`PbrAtlases.build` takes the buffer as a seed), so a pack that covers some
blocks and not others leaves the rest glowing. Measured in a sealed stone room
at midnight, mean frame brightness: lamp 3.4 -> 22.0, with glowstone at 34.4
and a sea lantern at 40.9 for scale.

## No world icon with ray tracing on — fixed

A world played with ray tracing on got no picture in the world list; with it
off the picture appeared. Minecraft takes that picture itself a second or so
after joining (`GameRenderer.takeAutoScreenshot`) and only once it has drawn
more than ten sections of terrain and its queue is empty. With ray tracing on it
draws none of them - Radiante has the terrain - so the count stayed at zero and
the picture was never taken.

`GameRendererMixin` now answers both of those from the renderer:
`ChunkManager.compiledSectionCount()` for the count and "no builds in flight"
for the queue, and only once the loading screen is gone and 120 frames of world
have been traced. Without that last part the picture was taken on the first
frame in the world and came out a flat grey square. Everything is unchanged with
ray tracing off.

## Light through coloured glass stayed white — fixed

A red pane threw an almost white patch on the floor. The shadow ray's any hit
blended the glass colour towards white by the texture's alpha
(`mix(vec3(1.0), tint, alpha)`), and vanilla stained glass is only about half
opaque, so half the colour was thrown away. How much light a pane lets past and
the colour it absorbs by are two different things: a surface that transmits now
takes its tint whole, everything else keeps the old blend. Both packs.
Measured on white concrete at noon, normalised to the brightest channel: red
glass (1.00, 0.38, 0.35), lime glass (0.66, 1.00, 0.17), clear glass
(1.00, 1.00, 0.96).

## The player casts no shadow in first person — done

Radiance showed the player's own shadow in first person; this renderer did not.
Minecraft extracts no model for the camera entity, so nothing of the player
reached the world: no shadow, and nothing of them in reflections either. The
avatar state is there all along (`LevelRenderState.playerRenderState
.avatarRenderState`), and the packs already skip the player mask on the camera's
first hit in first person while shadow rays and later bounces keep it, so
`EntityManager.collectPlayerShadow` submits the model under that mask. New
option "First Person Shadow" (`Options.firstPersonShadow`, on by default) turns
it off for anyone who would rather not have it.

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

- NRD — fixed. REBLUR converged to blotches when still, streaky noise in motion
  (~2.5x DLSS-RR's high-frequency noise) and lost ~25% of the light in dim
  bounce-lit rooms. Switched the module to RELAX on the same inputs: as clean as
  DLSS-RR in motion (measured 0.27 vs 0.65 high-pass noise), within ~10% of its
  brightness. Also: NRD now gets a +Z-forward view matching the positive viewZ,
  and hand pixels carry zero motion (they ghosted under both denoisers).
- `VK_ERROR_DEVICE_LOST` on join — root cause narrowed down: DLSS-RR at Ultra
  Performance hangs the GPU when its render size is tiny (854x480 window ->
  285x160). Same render size at Performance runs; Ultra Performance from 720p up
  runs. DLSSModule now drops to Performance below a 240-pixel render height;
  Ultra Performance is back in the menu.
- Held-item light (a torch in hand doesn't light its surroundings, only itself).
- Vanilla (non-volumetric) cloud rendering — needs decoding Minecraft's packed
  cloud face format.
- Block selection outline, enchantment glint, damage flash — not ported yet.
  (Block breaking cracks are in: blocks and block entities, drawn as a
  multiplicative decal via `ALPHA_MODE_DECAL` = 10, under the particle mask.) (Weather is in: rain/snow sheets from
  `WeatherRenderState`, weather mask, new `ALPHA_MODE_STOCHASTIC` = 9.)
