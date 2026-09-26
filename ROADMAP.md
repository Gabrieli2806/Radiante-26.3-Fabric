# Radiante — Roadmap

Post-0.1.0 backlog. Open work and items awaiting in-game verification come first;
completed work is collected at the bottom. Nothing here is scheduled; it's a
backlog, not a promise.

## Path out of alpha

What 0.2.0 still lacks before calling it beta, and then 1.0. Each item links to its own section below when it
has one.

**Beta** (feature complete, stable enough for everyday worlds):

- Visual smoke test suite running before every release, so regressions are caught by a run instead of by players.
- No known crashes: the intermittent native crash on exit (`core.dll` static destructors, 0xC0000005 while
  the process unloads) found and fixed.
- Frame generation and Reflex on NeoForge and Forge, or the options removed there for good.
- Distant Horizons: no large LOD blinking; memory and frame cost measured at DH's default 512 chunks.
- Every vanilla feature traced or deliberately replaced: check the list under "Other open work" and the
  ones marked "pending in-game check" (name tags, biome fog, texture uploads).
- Settings that do nothing under ray tracing identified; only switched off if it measurably helps.
- Performance baseline on a mid-range card (e.g. RTX 3060 / RX 6700): a documented preset that holds 60 fps at
  12 chunks with DLSS/FSR.
- Crash and error reports useful by default: renderer failures logged with GPU, driver and settings.

**1.0** (release):

- AMD and Intel GPUs tested, not only NVIDIA; FSR/XeSS noise brought close to DLSS.
- Tested against the most used mods (Distant Horizons, Not Enough Animations, EMI/JEI, Xaero's maps,
  Create-style block entities), results listed on the mod page.
- Settings screen reviewed: presets, tooltips, translations complete.
- Upgrade path: configs from older versions migrate without the player redoing their settings.
- Stable name (the "Radiante" working title settled).

**Jar size.** 0.2.0 dropped the unused XeSS frame generation and DX11 DLLs (about 30 MB). What is left is
mostly `libxess.dll` (~60 MB compressed of ~77 MB). Making XeSS optional like DLSS (downloaded or dropped in
by the player, or fetched on first use when chosen) would bring the jar near 15 MB.

## Open work and verification

### Distant Horizons far terrain — keep improving compatibility

Still seen in play: large LOD areas (coarse sections, up to 2048 blocks) blinking now and then as the quadtree
swaps a coarse section for its finer children or back. Ideas: only swap once the replacements are built (the
earlier `Retiring` approach, reworked for the "drop when built" selection), a short cross-fade or hysteresis on
the split distance, and checking whether DH's own section timestamps change under us during generation and
trigger rebuilds of whole coarse sections.

### Distant Horizons far terrain — implemented, pending in-game tuning

`client/compat/distanthorizons`: DH's sections are read (the only DH-touching class is `DhData`; its public API
only exposes full-detail columns, so three internal calls reach the coarse sections), meshed into one colour per
face boxes (`LodMesher`, caves filled, tops merged) and uploaded into `ChunkManager.EXTRA_SLOTS`, so they are
traced like sections. Sections are chosen as a quadtree around the camera (`LodTerrain`), split only where DH
has the finer sections complete (a coarse section keeps drawing the quadrants its children do not cover yet),
leaving out loaded chunks. DH's render-thread task queue is drained each frame, or its loading stalls, and the client level is
handed to DH (`DhData.loadClientLevel`) since DH only does that from its own renderer: without it, starting
with ray tracing on, DH neither loaded nor generated anything. Sections are read on Radiante's own threads
(`provider.get`, not `getAsync`) and probed by their date only, so DH's file threads stay free for generation. Rays and the render distance haze reach out to DH's distance (`WorldUBO.traceDistance`).
Verified in a fresh test world on Fabric and NeoForge: day, sunset, night, rain, clouds, underwater, from 70 to
4000 blocks up, toggling ray tracing in a running world, leaving and rejoining, teleporting 4000 blocks.
Quitting the game straight from a world while DH is still generating left DH's world generation threads
(non-daemon, waiting on chunks of a server that has stopped) holding the process open until Minecraft's shutdown
watchdog killed it with a crash report; it happens with ray tracing off too, so it is DH's. Worked around: once
the game has quit, if only `DH-` threads are left, Radiante ends the process
(`DistantHorizonsCompat.afterGameExit`). Still to look at: frame cost at DH's default 512 chunk distance on a fully
generated world, the seam where far terrain meets loaded chunks, textured detail for the nearest sections, and
NeoForge (same jar, untested).

### Frame generation and Reflex on Forge / NeoForge — investigate

With Streamline loaded, Forge and NeoForge crash in the native `createDevice`
(Fabric is fine). Streamline is now loaded from the first point LWJGL could load
Vulkan (`VulkanBackend.checkBackendAvailable` / `loadLibrary`), so the timing
matches Fabric's pre-launch, yet it still crashes, which points at the loaders
loading the Vulkan library some other way first. Until that is understood both
opt out (`RadiantePlatform.supportsStreamline`) and hide the two options.
Also still to check: the Forge jar installed in a real Forge client (the dev run
works; the hand-nested SnakeYAML only matters outside dev).

### Visual smoke test suite — planned

A repeatable, one-command check to run after every Minecraft version bump (and
before releases), built from scratch rather than grown out of the ad-hoc
`RADIANTE_DEV_SCRIPT` runs used so far.

- **Scenes as data.** Each case is a small file: setup commands (blocks,
  entities, effects, time, weather), a camera pose, settings overrides, and
  what to check. One case per regression we have already fixed: stained/tinted
  glass and panes, doors/trapdoors with windows, powder snow top, enchantment
  glint on items and armor, F3+B/F3+G gizmos, glowing outline through a wall
  (plus a team colour), name tags, particles, rain/snow, lava/powder snow
  submersion, sun path and sun/moon rotation options, night vision, block
  emission (torch, glowstone, lava), paintings/item frames, signs.
- **Runner.** A dev-only mode that launches the client, creates a fresh
  superflat world (no copying the user's saves), runs every case, takes a
  screenshot after a fixed settle time, and quits. Never touches the user's
  `options.txt`, `options.properties` or `debug-profile.json`: it runs with
  its own game directory.
- **Checks, not just pictures.** Cheap automatic ones first: the game started,
  every shader compiled, no exceptions or validation errors in the log, no
  frame is all black / all one colour, a region expected to show a feature
  differs from the same scene with the feature off. Then golden images per GPU
  vendor with a tolerant perceptual diff, updated deliberately.
- **Report.** One HTML page per run with each case's screenshot next to its
  golden image and diff, pass/fail, and the log excerpt; failures first.

### Inside lava / powder snow looks transparent — revisit

With the camera in lava (or powder snow) the world stays visible instead of
vanilla's opaque dark red (lava fog `0x991A00`, opaque by `fogEnd` = 1 block
without fire resistance). Cause found: the fog pass in `world.rgen` (both
packs) has a dense-fog branch only for water (`cameraSubmersionType == 1`);
lava (0) and powder snow (2) fall through to the normal distance haze.

A fix was tried and reverted because it did not look right in game: a
submerged branch with exponential fog in the vanilla fog colour, plus fading
the DLSS-RR albedo guides into the fog (RR otherwise rebuilds block texture
through it). It went opaque but toned bright orange instead of dark red -
auto exposure brightens an all-fog screen and the tonemapper desaturates it.
A better attempt probably needs the colour applied after tonemapping, or
exposure held while submerged. Reference: commit e6c92e6.

### Black corners with PBR / 3D resource packs — investigate

With resource packs that use PBR and 3D block effects, parts of some blocks,
usually the corners, render black. Those areas may be intended to be transparent;
check the affected materials and transparency handling before choosing a fix.

### Investigate: backport to 26.1

Check whether the render pearl Vulkan backend (`com.mojang.renderpearl`) exists in
26.1, and if the mixin targets (`VulkanInstance`, `VulkanBackend`, `VulkanDevice`,
`FrontendCommandEncoder`, the `LevelRenderState`/`SubmitNodeCollector` extraction
path) are stable enough between 26.1 and 26.3 to share a codebase, or whether it
needs its own mixin set behind a version-specific module. This is exploratory:
the answer could be "not worth it" if the two versions diverge too much.

### NameTag support — implemented, pending in-game check

`EntityCollector.submitNameTag` was empty. It now places the tag like vanilla
(attachment point + 0.5, rotated by `camera.orientation`, scaled 0.025) and
writes it through the sign text path in the `POLYGON_OFFSET` layer, so the
text any-hit shader cuts the letters out. The letters are mildly emissive so
they read in the dark. The translucent backing plate is vanilla's colour; the
text any-hit shader accepts partly transparent text surfaces stochastically
(hit with probability = alpha), and name tag layers are submitted as their own
instance under the particle mask, which shadow rays skip — so neither the
plate nor the letters cast shadows. Vanilla's see-through copy is not drawn.

### Per-biome fog/ambiance — implemented, pending in-game tuning

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

### Crash when resource packs change — hazard fixed, crash not reproduced

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

### Glowing effect outline — done (vanilla-pt)

Done in the tracer instead of a raster pass. `EntityManager` submits a glowing
entity a second time with every vertex painted its outline colour
(`EntityRenderState.outlineColor`, the team colour) under mask bit 4, which
was `FISHING_BOBBER_MASK` - nothing ever set it, so it is now defined as 0 and
the bit is `GLOW_OUTLINE_MASK`; no existing ray sees the copy. When
`WorldUBO.hasGlowOutline` is set, `world.rgen` traces a centre ray plus eight
around it (radius ~resolution/540 px) against that mask only, through the
shadow hit group with `ShadowRay.pad0 = GLOW_OUTLINE_QUERY`: `shadow.rahit`
returns the copy's vertex colour and stops, `shadow.rmiss` does nothing. A
pixel whose centre misses but a neighbour hits is rim: it is written as flat
colour into the RR output and the NRD clear channel. Walls are not in the
mask, so the rim shows through them. The advanced pack has no ShadowRay
payload in its `world.rgen` yet and draws no outline. The old stand-in (the
mob itself emitting light) is gone. Verified: a glowing cow behind a stone
wall shows as a white rim through it, a pig on a red team gets a red rim.

Earlier attempt, reverted, kept for reference:

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

### Out of bounds texture uploads — fixed, pending confirmation

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

### Advanced settings menu

Bring back something like Radiance's fuller customization screen — the current
`RadianteOptionsScreen` only exposes a handful of high-level toggles (preset,
shader pack, chunk building, debug logging). Radiance's had per-module
attribute editing exposed directly. Needs a design decision: expose the raw
module/attribute graph, or a curated set of the ones that actually matter to
players (ray bounces, denoiser strength, etc).

### Other open work from earlier sessions

- `VK_ERROR_DEVICE_LOST` on join — root cause narrowed down: DLSS-RR at Ultra
  Performance hangs the GPU when its render size is tiny (854x480 window ->
  285x160). Same render size at Performance runs; Ultra Performance from 720p up
  runs. DLSSModule now drops to Performance below a 240-pixel render height;
  Ultra Performance is back in the menu.

## Completed

### Block outline, held-item light, placed light strength, vanilla clouds — done

- **Block selection outline.** `LevelRenderer.render` submits it and that method is replaced, so it never
  showed. `EntityManager.collectBlockOutline` turns each edge of the targeted block's outline shape into a thin
  camera-facing quad, opaque black (the high contrast option's colour, slightly emissive, with that option),
  about 3 pixels at 720p at any distance (sized from the projection so it does not break into dots at the
  upscaler's lower render resolution), pushed a hair off the block so its faces cannot hide it, under the
  particle mask so it casts no shadow.
- **Damage flash** already worked (the overlay texture reaches the shaders; a hurt mob takes vanilla's 30 % red).
- **Held-item light.** `HeldLight` turns a lit item in either hand (block items by their block's light level,
  lava bucket 15) into a point light a little in front of the player, tinted by item (warm torch, cyan soul,
  red redstone, cool sea lantern/end rod/froglight, orange lava). It reaches the shaders through two new
  `WorldUBO` vec4s and `sampleHeldLight` in `block_light.glsl`: a 0.1-block sphere, world-mask shadow ray (the
  player's own model does not block it), smooth fade to its reach. Option "Held Item Light", on by default.
- **Placed lights as bright as held ones.** A block's light radiance was proportional to the average glow of its
  texture, so a torch (a few flame texels) lit its room about a hundred times less than a held torch.
  `EmissionTiles` now divides each sprite's radiance by the share of the sprite that glows, so a block's light
  depends on its light level, not on how big its glowing part is; `LIGHT_PER_FACE` is tuned so a placed torch
  matches the held one.
- **Vanilla clouds.** `CloudGeometry` builds Minecraft's 12x4x12 cells from the cloud renderer's own cells
  (clouds.png), with vanilla's drift, height, colour and Fast/Fancy, and only rebuilds when the camera enters
  another cell. Cells are closed boxes (reflections and shadow rays see them from any angle), submitted as
  `WORLD_CLOUD` geometry in the `clouds` hit group. Styled after Bedrock: translucent (`clouds.rchit` adds the
  cloud on the face a ray enters by, opacity 0.6, and lets the ray continue) and lit by the whole sky as well as
  the sun, so they stay white. `VPT_PRIMARY_TRACE_STEP_LIMIT` went from 2 to 4 for the extra pass-through
  steps. "Vanilla" is now in the cloud menu; it was the pack's default but missing from the list.

### Graphics API fallback after a failed start — done

After a start that never finished, Minecraft switches the preferred graphics API
to OpenGL for safety, and that value was then saved with the other options on
exit. The mod reads a saved OpenGL as the player's own choice, so a single crash
left ray tracing off for good. The fallback still covers the session it protects,
but is no longer saved.

### Fabric, NeoForge and Forge support — done

The project is split into `common/` (plain Minecraft, compiled against vanilla
through ModDevGradle's NeoForm mode) and one thin module per loader (Fabric Loom,
ModDevGradle, ForgeGradle 7) that compiles the common sources with its own glue.
Minecraft 26.x is unobfuscated, so the same mixins apply unchanged everywhere.
Loader-specific pieces sit behind `RadiantePlatform` (ServiceLoader): game
directory, the collector (Fabric's rendering API adds a mesh submission method,
handled in `FabricEntityCollector`), and whether Streamline can load. Key
bindings (`RadianteKeys`), the client tick (`RadianteClient.onEndClientTick`) and
the settings screen are registered by each loader's entrypoint. Differences found
while porting: NeoForge's resource file system resolves files but not folders
(native extraction now locates folders from `core.dll`); NeoForge ticks atlas
animations before the atlas texture exists (`TextureTracker.gpuTextureOrNull`);
Forge's Mixin 0.8.7 only knows compatibility levels up to `JAVA_21`; ForgeGradle 7
has no jar-in-jar, so SnakeYAML is nested by hand in the format Forge reads.
Verified: all three boot, apply every mixin, and render the same test scene the
same way.

### Ice, wither glow, charged creeper light — done

- Ice and frosted ice: a frosted specular map alone left ice far clearer than
  packed/blue ice. A first fix (stochastic alpha: each ray hit or missed the
  ice with 75% probability) matched vanilla's blend but was grainy up close,
  because even the denoiser's depth/normal guides flickered. Now Radiante's
  ice maps (`ice_s.png`, `frosted_ice_0..3_s.png`) author subsurface
  scattering, and `convertLabPBRMaterial` treats a see-through surface with
  subsurface as a translucent solid: always hit and shaded, with transmission
  `1 - alpha` (25%) instead of full glass. `world.rgen`'s deterministic glass
  split now only applies to fully transmissive surfaces, so the frames that
  happen to pass through are not recomposed as glass. Verified close up: ice
  and frosted ice flicker no more than packed ice, and the three read as one
  family.
- Wither: glowed at all times. Its renderer reports block light 15 (vanilla
  draws it full bright), which `selfLitEmission` took for a mob giving off
  light; the wither and its skulls (same override) are now excluded. Verified
  dark at night at full health, and a floating skull no longer reads white.
- Charged creeper / wither armor shell: emission raised to 1.2 so it reads as
  a glow; the wither's shield texture is much darker, so it gets 4.0. Its light on the surroundings is weak: entity emission only reaches
  other surfaces through random bounces (block light sampling covers blocks).

### Charged creeper / wither armor swirl — done

The swirling shell of a charged creeper and of a wither below half health was
frozen and read as plain dark stripes. Vanilla draws it with
`RenderTypes.energySwirl(texture, u, v)`: an `OffsetTextureTransform` scrolls
the texture every frame (a new render type per frame) and the layer is added
on top of the mob. `RenderTypeInfo` now reads that offset
(`RenderSetup.textureTransform`) and `PBRVertexWriter.uvOffset` adds it to the
texture coordinates (textures wrap with repeat), the layer is mildly emissive
(`ENERGY_SWIRL_EMISSION` 0.6), and these per-frame render types are no longer
put in the `RenderTypeInfo` cache, which they grew without bound. Verified:
the shell animates between frames on a charged creeper.

### Block light sampling, Advanced pack removed — done

The `advanced` pack was a second full copy of every shader plus a ReSTIR
direct-light chain (~8 passes). Every visual fix had to be written twice and
the glowing outline never reached it, while the only visible gain was less
noise from block lights. It is removed (still in git history; install deletes
a stale `advanced.zip`). With one pack left, the Shader Pack selector is gone
from the settings screen; `vanilla-pt` is always used.

`vanilla-pt` gets that gain from a new option, "Block Light Sampling"
(`Options.blockLightSampling`, on by default, needs Block Emission; sent as
`WorldUBO.blockLightSampling`). `common/block_light.glsl`: each surface looks
up the light lists (set 1 binding 9, one emissive triangle per entry, built
natively from chunk emission) of the 27 sections around it, picks among 4
candidate lights by unshadowed diffuse contribution (RIS), and traces one
shadow ray (`BLOCK_LIGHT_QUERY`: the miss shader reports "visible"). Only the
diffuse lobe is lit this way; a bounce that left a sampled surface through the
diffuse lobe (`rayBlockLightSampledBit`, kept across bounces) skips the
emission of a light it hits within that reach, so nothing is counted twice.
No temporal/spatial reuse. Verified in a sealed room at night: torches and
glowstone visibly light walls and ceiling with it on; with it off the room
stays much darker, as random bounces rarely find small lights.

### Sun and moon positioning mode — done

New option "Vanilla Sun Path" (`Options.vanillaSunPath`, off by default,
saved in options.properties). The custom inclination was a fixed 10 degree
southward tilt applied in both packs' `celestial.glsl`; it now lives in
`RadianteRenderer` (a rotateX before vanilla's own rotateY(-90)/rotateX(sun
angle)), skipped when the option is on, so the shaders take the direction
as-is. Vanilla mode matches vanilla's sky transform, sun straight overhead
at noon. Verified at time 6000: vanilla mode throws a pillar's shadow
straight down, custom mode throws it north.

Second option "Vanilla Sun/Moon Rotation" (`Options.vanillaCelestialOrientation`,
off by default). The sprites were laid on a free basis around their direction
(`makeBasis`), so the squares turned as they crossed the sky and read as
diamonds. With the option on, `celestialBasis` in both packs' miss shader uses
the axis the sky turns around (sent as `SkyUBO.celestialAxis`, the celestial
transform's local x) as one edge, like vanilla's quads. Verified: sun and moon
show as upright squares with it on, diamonds with it off.

### Hollow-looking door and trapdoor cutouts — done

Door and trapdoor models are two thin sheets a few pixels apart with windows
cut out of the texture; nothing joins the sheets around a window, so traced,
a window showed the empty gap and the shadowed inside of the far sheet.
`CutoutWalls` (called from `ChunkManager` for `BlockTags.DOORS`/`TRAPDOORS`)
finds each pair of opposite big faces sharing a sprite, takes the gap between
them as the panel thickness, and for every edge between a solid and a
transparent texel of the face adds a wall across that thickness, coloured by
the solid texel - the same idea as vanilla's extruded item models. Works for
any door/trapdoor texture, resource packs included. Verified on oak and jungle
doors and an iron trapdoor: windows read as carved through solid material.

### Powder snow block appearance — done

The top of powder snow broke up into large triangles of streaked, mismatched
texture. Powder snow's model is not a cube: it is six paper-thin boxes, each
drawing an outward face plus an inward one 0.002 of a block behind it with
mirrored UVs, so the block can be seen from inside. Traced, those near-coplanar
pairs tore the surface into triangles. It was not the translucent layer (routing
the block through the solid layer changed nothing) nor the denoiser (same with it
off). `ChunkManager` now drops powder snow's inward-facing quads (`facesInward`);
from inside the block the powder snow fog covers the view anyway. Verified on a
17x10 field: smooth surface, matching a snow block field under the same light.

### Glass visibility and transparency — done

Tinted glass, stained glass, and stained-glass panes were hard to see through.
The view ray passing a transmissive surface was filtered by the texture's full
colour (`sqrt(albedo)` per face, so the full colour across a block's two
faces). Vanilla stained glass is a saturated colour at roughly half alpha and
vanilla blends it by that alpha, so half the scene behind still shows through
untinted; filtering by the whole colour instead cut most of the light (a red
pane blocks nearly all green and blue) and the glass read almost opaque.

The see-through filter is now `mix(1, colour, alpha)` - set as the transmissive
albedo in `convertLabPBRMaterial` (used by `DisneySample`'s glass lobe) and in
the deterministic transparent-split branch in both packs' `world.rgen` and
`advanced`'s `primary.rgen`. Coloured light cast through glass (`shadow.rahit`)
keeps the full tint on purpose, so coloured shadows are unchanged.

Verified in a test world: a yellow wall is clearly visible through red,
light-blue and tinted glass and a green pane, each keeping its tint.

### Debug hitboxes and chunk borders — done

F3+B entity hitboxes and F3+G chunk borders did nothing with the mod on.
Neither is entity or block geometry - vanilla draws them through a separate
"gizmo" system (`Gizmos.line`/`.cuboid`/`.point`/`.arrow`, collected into a
`SimpleGizmoCollector` on `LevelRenderer`) that only gets drained and turned
into drawable primitives from inside `LevelRenderer.render`, right before it
submits them onward as `submitGizmoPrimitives` calls. The ray tracer replaces
that whole method with its own submission pipeline (`EntityManager`), so the
collector was never drained - it just grew, frame after frame, forever - and
`EntityCollector.submitGizmoPrimitives` (existed already, to satisfy the
`SubmitNodeCollector` interface) was an empty stub nothing ever reached.

`LevelRendererGizmoAccess` (a mixin on `LevelRenderer`) exposes the collector;
`EntityManager.collectDebugGizmos` drains it and finalizes each instance the
same way `LevelRenderer` would have (`gizmo.emit(primitives, alphaMultiplier)`),
then submits the result into the renderer's own collector to reach
`submitGizmoPrimitives` for real. Every gizmo primitive that matters for these
two shortcuts - `Gizmos.cuboid`'s stroke style, `.line`, `.arrow` - resolves to
plain lines, so only `Group.lines()` is handled; points, quads, triangle fans
and text gizmos (used by rarer debug views, not by these two) are not yet.
Each line becomes a thin, camera-facing quad (the same trick vanilla's own
line rasteriser uses to fake width on a GPU that draws triangles), coloured
from the line, emissive enough to read regardless of the scene's lighting, and
traced under the particle mask so it neither casts a shadow nor feeds light
back into the world. Width is vanilla's screen pixel count turned into a small
fixed world thickness rather than reprojected every frame - correct enough to
read clearly, not literally constant in screen space.

Verified in a test world: F3+G's coloured chunk grid (the red/yellow/cyan lines
`ChunkBorderRenderer` draws) appeared exactly where expected, and F3+B put a
tight white wireframe box and a blue view-direction arrow around a cow and a
pig - vanilla's actual hitbox colour is white, not the green some other tools
use, which is why it was invisible in an earlier attempt with a white floor.

### Enchantment glint — done

Enchanted items and armor rendered with no glint at all. The shaders had full
support for it already - `hasGlint`/`glintUV`/`glintTexture` in the vertex
format, `WorldUBO.textureMat` carrying the same scrolling matrix vanilla's own
`glint.vsh` animates `UV0` through - but nothing on the Java side ever set a
vertex's glint bit or picked a glint texture, so every writer used
`glintTextureId(0)` (the 1x1 fallback) and glint always sampled as nothing.

Two different vanilla mechanisms feed the same gap:
- Armor and other entity models carry their foil-ness *in the `RenderType`
  itself* - `HumanoidArmorLayer`/`EquipmentLayerRenderer` submit with
  `RenderTypes.armorCutoutNoCullGlint(...)` or `.trimmedArmorGlint()`, whose
  `RenderSetup` binds a second texture under the name `"GlintSampler"`
  alongside the ordinary `"Sampler0"`. `RenderTypeInfo` now looks that binding
  up the same way it already looks up `Sampler0`, so `isGlint()`/
  `glintTextureId()` just read whether and what a `RenderType` glints, no name
  matching. `EntityCollector.writer()` feeds that straight into the writer.
- Items carry it as an explicit `ItemStackRenderState.FoilType` parameter on
  `submitItem`, separate from the quads. `BakedQuad.MaterialInfo` already
  carries the right `RenderType` for each case -
  `itemRenderType()`/`itemGlintRenderType()`/`itemGlintSpecialRenderType()` -
  which `submitItem` now picks by `foilType`, same as vanilla's own
  `ItemFeatureRenderer` does; from there it is the same writer path as armor.

The one thing left for `PBRVertexWriter` to do was tag the vertices: a writer
in `glintEnabled(true)` state now also writes the glint bit and mirrors the
regular texture UV into the glint UV slot every time `setUv` is called -
matching `glint.vsh`, which scrolls a block or item's own UV rather than
reading a second one. `VertexConsumer.setUv3` already wrote to that same slot
for the crumbling-decal path (`SheetedDecalTextureGenerator`, unrelated), so no
new vertex layout or shader change was needed on either side.

Vanilla's screen-locked "special" foil decal (a handful of models, drawn via
`putBakedQuadWithGlint` with its own projected UV) is approximated with the
same UV-riding glint rather than reprojected - it still glints, just not
locked to the screen.

Verified: a sharpness-enchanted diamond sword in hand shows a clear scrolling
purple/blue sheen on the blade, confirmed moving between two screenshots a few
seconds apart. Diamond armor with protection worked the same way (confirmed via
the resolved `armor_cutout_no_cull_glint` render type carrying a valid,
distinct glint texture id from the item one), though it is hard to see by eye
against diamond's own near-identical light blue.

### Far render distance performance — done

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

### Frosted glass (inherited from Radiance) — fixed

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

### Hand and held items at other field of view settings — fixed

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

### Glowing mobs and the glowing effect — done

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

### Lit redstone lamps cast no light — fixed

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

### No world icon with ray tracing on — fixed

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

### Light through coloured glass stayed white — fixed

A red pane threw an almost white patch on the floor. The shadow ray's any hit
blended the glass colour towards white by the texture's alpha
(`mix(vec3(1.0), tint, alpha)`), and vanilla stained glass is only about half
opaque, so half the colour was thrown away. How much light a pane lets past and
the colour it absorbs by are two different things: a surface that transmits now
takes its tint whole, everything else keeps the old blend. Both packs.
Measured on white concrete at noon, normalised to the brightest channel: red
glass (1.00, 0.38, 0.35), lime glass (0.66, 1.00, 0.17), clear glass
(1.00, 1.00, 0.96).

### The player casts no shadow in first person — done

Radiance showed the player's own shadow in first person; this renderer did not.
Minecraft extracts no model for the camera entity, so nothing of the player
reached the world: no shadow, and nothing of them in reflections either. The
avatar state is there all along (`LevelRenderState.playerRenderState
.avatarRenderState`), and the packs already skip the player mask on the camera's
first hit in first person while shadow rays and later bounces keep it, so
`EntityManager.collectPlayerShadow` submits the model under that mask. New
option "First Person Shadow" (`Options.firstPersonShadow`, on by default) turns
it off for anyone who would rather not have it.

### Night vision did nothing — fixed

The effect only brightens Minecraft's lightmap, and a path tracer never reads
one, so a sealed room stayed exactly as black with night vision as without it.
The surface the camera sees now gets a flat term of its own colour,
`SkyUBO.nightVision` (the intensity vanilla already works out in
`LightmapRenderState.nightVisionEffectIntensity`, fade at the end included)
times `NIGHT_VISION_AMBIENT`, which reads the way the lightmap's floor does in
vanilla: everything visible, washed out, no shadows of its own.

It is added in two places because the two denoisers read different images: the
emission channel, which the NRD compose takes and leaves undenoised, and the
radiance image, which is what DLSS Ray Reconstruction is handed. Neither pass
reads both, so nothing is counted twice. Both packs. Measured in a sealed stone
room at midnight, mean frame brightness 5.0 -> 77.8.

### Sun and moon color — DONE

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

### NRD denoising — fixed

REBLUR converged to blotches when still, streaky noise in motion
(~2.5x DLSS-RR's high-frequency noise) and lost ~25% of the light in dim
bounce-lit rooms. Switched the module to RELAX on the same inputs: as clean as
DLSS-RR in motion (measured 0.27 vs 0.65 high-pass noise), within ~10% of its
brightness. Also: NRD now gets a +Z-forward view matching the positive viewZ,
and hand pixels carry zero motion (they ghosted under both denoisers).

### Moving block geometry — fixed

Primed TNT already worked (it submits a block model). Falling blocks and
piston-moved blocks (including the extending head) go through
`SubmitNodeCollector.submitMovingBlock`, which `EntityCollector` left empty on
the wrong assumption that the terrain pass covered them — the section only holds
an invisible `moving_piston` (or air) while they move. It now tesselates them
with a `ModelBlockRenderer` the way vanilla's `MovingBlockFeatureRenderer` does
and writes the quads into the solid/cutout/translucent moving-block layers.
