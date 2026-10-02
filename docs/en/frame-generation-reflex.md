[← Back to index](README.md)

# Frame Generation and NVIDIA Reflex

Both are in [Quality & Upscaling](settings/quality-and-upscaling.md). Since 0.5.0 neither uses
Streamline: frame generation runs natively on its own present thread and Reflex goes straight
through `VK_NV_low_latency2`. Neither depends on the loader or needs a restart.

## Frame Generation

| | |
|---|---|
| Type | Auto · DLSS · FSR |
| Values | Off · 2x · 3x · 4x (up to what the type and GPU allow) |
| Default | Off |
| Applies | Immediately |
| Not available | With HDR Output on |

Generates extra frames between the ones the engine renders. Smoother motion on a high refresh rate
monitor, at the cost of some latency: generated frames do not react sooner to your mouse.

**Frame Generation Type:**

- **DLSS**: NVIDIA only, with DLSS installed. Up to 4x on RTX 50; less on older series.
- **FSR**: any GPU, with any upscaler (DLSS included). 2x only.
- **Auto**: DLSS when the GPU supports it, FSR otherwise.

A present thread spreads the generated frames over each real frame. It does not force V-Sync:
that stays your choice (turning generation on makes Minecraft rebuild the swapchain once).

Tested on Fabric on Windows. NeoForge and Forge run the same code, not tested yet.

<!-- Screenshot pending: the F3 overlay showing real vs. generated FPS -->

## NVIDIA Reflex

| | |
|---|---|
| Requires | NVIDIA GPU with `VK_NV_low_latency2` and `VK_KHR_present_id` (recent driver) |
| Values | On / Off |
| Default | Off |
| Applies | Immediately |

Holds the CPU back so each frame starts as late as the GPU allows, instead of queuing frames up.
Your clicks reach the screen sooner. Most noticeable with frame generation on. Works on Windows and
Linux, with any pipeline. On GPUs without those extensions the setting does not show.

Frame generation and Reflex together is the recommended combination.
