[← Settings](../settings.md) · [Index](../README.md)

# Quality & Upscaling

![Quality & Upscaling category](../../images/opciones-calidad.png)
<!-- TODO: the full category: Pipeline, DLSS Mode, Frame Generation, Reflex -->

| Control | Values | Default | What it does |
|---|---|---|---|
| **Pipeline** | RT-DLSS (Ray Reconstruction) · RT-NRD · RT-NRD-FSR · RT-NRD-XeSS — only the ones your GPU supports are listed | RT-DLSS if your GPU supports it; otherwise the best one available | Which denoiser/upscaler combination the engine uses. Detail on each below. |
| **DLSS Mode** | Quality · Balanced · Performance · Ultra Performance | Ultra Performance | Only with Pipeline = RT-DLSS. Internal resolution it renders at before upscaling. Quality looks sharpest and costs the most; Performance and Ultra Performance run faster but look softer and thicken thin details. |
| **Frame Generation** | Off · 2x · 3x … up to whatever maximum your GPU reports | Off | Only with Pipeline = RT-DLSS, and only on Fabric. DLSS generates extra frames between rendered ones. Full detail in [frame-generation-reflex.md](../frame-generation-reflex.md). |
| **NVIDIA Reflex** | On/Off | Off | NVIDIA GPUs only. Low-latency mode. Full detail in [frame-generation-reflex.md](../frame-generation-reflex.md). |

## The four pipelines

- **RT-DLSS (Ray Reconstruction)** — DLSS does denoising and upscaling together ("Ray
  Reconstruction"). It is the recommended path on NVIDIA GPUs: less noise than the NRD-based
  presets, and unlocks DLSS Mode, Frame Generation and Reflex.
- **RT-NRD** — the NRD (RELAX) denoiser with no upscaler at all: renders at the window's native
  resolution. Works on any GPU with ray tracing, not just NVIDIA.
- **RT-NRD-FSR** — NRD plus AMD's FSR 3 upscaler. FSR works on any vendor, you do not need an AMD
  GPU to pick it.
- **RT-NRD-XeSS** — NRD plus Intel's XeSS upscaler. Same as FSR, it works on any vendor.

Only the pipelines your GPU and driver can actually run are offered — if one is missing from the
list, it is not an install step you are missing (see
[requirements.md](../requirements.md#what-you-do-not-need-to-download-separately)).

## Why DLSS and the others do not mix

DLSS is one vendor's denoiser and upscaler bundled together; offering it alongside FSR or XeSS would
suggest they can be combined, and they cannot. That is why DLSS Mode, Frame Generation and Reflex
only appear when the chosen Pipeline is RT-DLSS.
