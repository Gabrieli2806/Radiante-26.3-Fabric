[← Back to index](README.md)

# Radiante settings

Opens with `F6` in game. Changes pile up while the screen is open and are all applied together when
you close it with "Done" (or when you switch category/preset, which reopens the screen with the
same choices) — so the pipeline rebuilds at most once per settings session, not once per click.

![Radiante's settings screen](../images/opciones-pantalla-completa.png)
<!-- TODO: the full screen, Quality & Upscaling category, with Quality and Reset visible -->

## The top row: Quality and Reset to Defaults

**Quality** is a quick selector — Low / Medium / High / Ultra / Custom — that sets, all at once, the
controls measured to cost the most frames per second: DLSS mode, block light sampling, volumetric
fog, clouds and render distance. If you then touch any of those by hand, the selector switches to
"Custom" by itself — there is no "halfway" quality level. The exact detail of what each level sets
is in [styles-and-quality.md#quality-levels](styles-and-quality.md#quality-levels).

**Reset to Defaults** (the button next to Quality) puts *everything* on this screen back to how a
fresh install has it: not just the sliders you can see, but also stored pipeline and shader pack
settings that do not have their own control here yet (see
[#the-advanced-button-and-what-you-cannot-see-yet](#the-advanced-button-and-what-you-cannot-see-yet)).
It is a full reset, not just of the category you happen to be looking at.

## Categories

Below that row is a **Section** selector that switches which category is shown; the game remembers
which one you had open last, even across play sessions. Each has its own page with the full table of
controls, their range and default value:

| Section | Contents, in short |
|---|---|
| [Quality & Upscaling](settings/quality-and-upscaling.md) | The pipeline (DLSS/NRD/FSR/XeSS), DLSS mode, Frame Generation, Reflex. |
| [Image](settings/image.md) | Picture Style, Tone Mapping, saturation, exposure, HDR, motion blur, depth of field. |
| [Lighting](settings/lighting.md) | Day/night/emission/held-item brightness, bounced light, sky light, block light sampling. |
| [Sky, Fog & Water](settings/sky-fog-water.md) | Java/Bedrock atmosphere, clouds, biome fog, volumetric fog, water waves and murkiness. |
| [Performance](settings/performance.md) | Ray bounces, carved surfaces (parallax), volumetric fog quality, chunk building threads and batches. |
| [Other](settings/other.md) | Block outline, see-through parallax edges, debug logging. |

A control only shows up if the active pipeline or shader pack has it — for instance, DLSS Mode and
Frame Generation only appear with the RT-DLSS preset set, and "Carved Surfaces" (parallax) only if
the shader pack supports it. It is not a bug if something in this documentation is missing from your
screen; it likely depends on the preset or shader pack you have active.

## The "Advanced..." button and what you cannot see yet

It is disabled on purpose: for now it is a placeholder for a future screen with every pipeline and
shader pack setting, for fine tuning beyond what these six categories cover. Those parameters
already exist and are saved, they just do not have a control here today — see
[ROADMAP.md](../../ROADMAP.md#advanced-settings-menu). In the meantime, **Reset to Defaults** does
touch all of them even though you cannot see them, because it clears the whole stored pipeline, not
just what is on screen.
