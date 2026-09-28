[← Back to index](README.md)

# Common problems

## The game is running on OpenGL

Radiante needs the Vulkan renderer; ray tracing does not exist on OpenGL in any form. If the game
starts on OpenGL, you will see a message asking you to switch the Graphics API to "Prefer Vulkan" or
"Default" in Minecraft's video options, then restart.

If Minecraft switched to OpenGL by itself (without you asking for it) after a launch that did not
finish cleanly, that is Minecraft's own safety net: after a failed start, it switches the preferred
API to OpenGL for the next launch. Radiante does not save that automatic switch as if it were your
own choice — it only covers the session it protects — so a single crash does not leave you with ray
tracing off forever; just try again.

## My GPU does not support ray tracing

You will see the "Radiante: ray tracing unavailable" screen. It means your graphics card or driver
does not report support for `VK_KHR_ray_tracing_pipeline` — check
[requirements.md](requirements.md#system) for the list of GPUs that do. You have two options on that
screen:

- **Continue without ray tracing** — the game keeps running with Minecraft's own renderer, as if
  Radiante were not installed.
- **Switch to OpenGL** — changes nothing about ray tracing, since it does not exist there either; in
  practice it is the same option as the one above.

Updating your GPU driver to your vendor's latest release is the first thing to try if you believe
your card should support this.

## A preset (DLSS, FSR or XeSS) is missing from the Pipeline selector

It is not an install step you are missing — DLSS, FSR and XeSS ship inside the mod, there is nothing
to download separately. If a preset is missing, your GPU or driver do not meet what that specific
preset needs to initialise: DLSS only on NVIDIA GPUs with a recent driver; FSR and XeSS work on any
vendor, but also need their module to load successfully. See
[requirements.md](requirements.md#what-you-do-not-need-to-download-separately).

## Frame Generation or NVIDIA Reflex are missing

Two possible reasons, and only one has a fix:

- **You are on NeoForge or Forge.** These two features only work on Fabric for now — see
  [frame-generation-reflex.md](frame-generation-reflex.md). There is no way to enable them on the
  other two loaders yet.
- **Your GPU is not NVIDIA.** Both are NVIDIA-exclusive features (Streamline/DLSS); there is no
  AMD or Intel equivalent in Radiante.

## I turned on HDR but see no change

Check, in order:

1. Did you restart the game after turning on "HDR Output"? The change does not apply without a
   restart.
2. Is HDR actually turned on in Windows' display settings for that monitor, not just supported by
   it?
3. After restarting, check the "HDR Output" tooltip on Radiante's settings screen — if it still says
   "on, but not running", the problem is in step 2, not in Radiante.

More detail in [hdr.md](hdr.md).

## Distant Horizons: seams or flicker in far terrain

This is a known, actively-worked-on issue, not a fault in your install — see
[distant-horizons.md](distant-horizons.md#what-to-expect-honestly). Lowering Distant Horizons' own
render distance (not Minecraft's) usually makes it less noticeable while work continues.

## The game runs slow / low FPS

See the full [performance guide](performance.md). Quick summary: try the Quality selector on Low or
Medium first; if it is still slow, DLSS Mode (or the equivalent upscaler) is the single control with
the biggest impact.

## I want a setting back to how it was on install

The **Reset to Defaults** button, next to the Quality selector on the settings screen, puts
absolutely everything back — including pipeline and shader pack settings that do not have their own
visible control yet — to a fresh install's values. More detail in
[settings.md#the-top-row-quality-and-reset-to-defaults](settings.md#the-top-row-quality-and-reset-to-defaults).

## None of this fixed my problem

- Check whether it is already reported or being worked on in [ROADMAP.md](../../ROADMAP.md), at the
  repository root — it covers quite a few known cases with more technical detail than fits here.
- If not, ask on the [Discord](https://discord.gg/DhBbAzugZ9) or open an
  [issue on GitHub](https://github.com/Gabrieli2806/Radiante-26.3-Fabric/issues). Turn on
  **Debug Logging** (category [Other](settings/other.md)) before reproducing the problem — the log
  with that setting on usually has the detail needed to diagnose it.
