[← Back to index](README.md)

# Radiante settings

Opened with `F6` in a world, or from Radiante's button in Options → Video Settings (title screen
included). Changes add up and are applied together when you press "Done", so the renderer rebuilds
once. While it does, the **Applying settings...** screen shows for a few seconds.

![Radiante settings: Quality](../images/ui/settings-quality.png)

## Search

The **Search...** box filters settings across every category by name. The quickest way to find
something without knowing its category.

## Impact labels

The settings that cost the most show **Performance impact: Low / Medium / High / Varies** in their
tooltip. Varies means it depends on the scene (for example, on how many lights there are).

## Quality and Reset to Defaults

**Quality** (Low / Medium / High / Ultra / Custom) sets the costliest controls at once: upscaler
mode, light bounces, volumetric fog, clouds, render distance and more. Changing one of those by
hand turns it into Custom. Details in
[styles-and-quality.md](styles-and-quality.md#quality-levels).

**Reset to Defaults** returns everything to a fresh install, including pipeline and shader pack
settings that have no control here. The pipeline goes back to RT-DLSS when DLSS is loaded, FSR
otherwise.

## Settings that need a restart

Some (HDR Output, installing or deleting an upscaler) only apply after a restart. They are marked
**(restart)** and, when you leave the screen, a notice lists the pending changes with **Close the
game now** or **Later**.

## Categories

| Section | Contents |
|---|---|
| [Quality & Upscaling](settings/quality-and-upscaling.md) | Pipeline, DLSS or upscaling mode, frame generation, Reflex. |
| [Image](settings/image.md) | Picture style, tone mapping, exposure, HDR, motion blur, depth of field. |
| [Lighting](settings/lighting.md) | Brightness, bounce light, block light sampling, ReSTIR, SER, item light. |
| [Sky, Fog & Water](settings/sky-fog-water.md) | Atmosphere, clouds and their shadows, fog and its style, rain, water, glass. |
| [Performance](settings/performance.md) | Light bounces, far bounces, cache, parallax, chunk building. |
| [Other](settings/other.md) | Block outline, parallax edges, debug logging. |

A setting only shows when the pipeline, GPU or shader pack supports it: DLSS Mode only with
RT-DLSS, Reflex only on NVIDIA, SER only on RTX 40 or newer. If something in these docs is missing
from your screen, that is why.

## The "Advanced..." button

Disabled: reserved for a future screen with every pipeline parameter. See
[ROADMAP.md](../../ROADMAP.md#advanced-settings-menu).
