![Radiante logo](PASTE_LOGO_IMAGE_URL_HERE)

---

**is a client-side mod that brings real-time hardware ray tracing to Minecraft 26.3, running natively on the game's own Vulkan renderer — no second renderer, no second window, no OpenGL bridge.**

> ⚠️ **Alpha software.** Radiante is an early, work-in-progress fork. Core lighting works, but several vanilla features are not ported yet (see *Known Issues* below), and things will change between versions. Back up your worlds and configs before installing. Not recommended as your only shader/graphics mod on a world you care about — yet.

![Gameplay](PASTE_SCREENSHOT_OR_GIF_URL_HERE)

---

**What makes Radiante different**

*Built on 26.3's native Vulkan, not a second renderer bolted on*
* No dual-renderer bridge - Minecraft 26.3 ships its own Vulkan backend (`com.mojang.renderpearl`); Radiante shares that same device and swapchain instead of standing up a separate OpenGL↔Vulkan translation layer like older ray tracing mods had to.
* Fewer moving parts - one Vulkan instance, one device, one frame timeline. Less surface area for the sync bugs and interop crashes that come with juggling two graphics APIs at once.
* Built to be maintainable - the goal is a codebase future Minecraft versions can be ported onto without a rewrite, not a mod frozen to one version forever.

![In-Game](PASTE_SCREENSHOT_URL_HERE)

---

**Features**

*Lighting*
* Path-traced direct light, soft shadows, and global illumination - two shader pipelines to choose from (see below).
* Emissive blocks light their surroundings - torches, lava, glowstone, redstone ore, magma, nether portals, beacons.
* Burning mobs, glowing mob eyes, lightning, and beacon beams all cast real light.
* Physically based sky, sun, moon, and atmospheric scattering.
* Optional ray-marched volumetric clouds.

*Two shader pipelines*
* **Vanilla PT** - lighter weight, closer to vanilla's look, better performance.
* **Advanced** - ReSTIR-based direct lighting for cleaner results in scenes with many light sources, LabPBR support, parallax, FFT water, volumetric clouds on by default.

*Upscaling & frame generation*
* **DLSS** Ray Reconstruction (Quality / Balanced / Performance / Ultra Performance).
* **DLSS Frame Generation**, including multi-frame generation on supported GPUs - an in-game F3 counter shows the real frames hitting your screen, so you can see it working.
* **FSR 3** and **XeSS** upscaling, plus NRD denoising.

*Resource pack support*
* Reads LabPBR `_n` (normal) and `_s` (specular/roughness/metal/emission) maps from any resource pack that ships them.

![Screenshot](PASTE_SCREENSHOT_URL_HERE)

---

**System Requirements**

* Operating System: Windows 10/11 (64-bit) - **the only platform supported right now**.
* Graphics API: Vulkan-capable GPU with `VK_KHR_ray_tracing_pipeline` and `VK_KHR_acceleration_structure` (NVIDIA RTX 20-series or newer, AMD RX 6000-series or newer, Intel Arc).
* Minecraft 26.3, Fabric Loader 0.19.5+, Java 25.

**Enabling DLSS**

DLSS's own libraries aren't bundled (NVIDIA's license doesn't allow redistributing them). Drop `nvngx_dlss.dll` and `nvngx_dlssd.dll` into `.minecraft/radiante/dlss/` and restart the game — DLSS will then show up in the Radiante settings screen (**F6**).

**Enabling Frame Generation**

Needs an NVIDIA RTX GPU. Turn it on in F6, then restart the game once (it has to load before Minecraft opens Vulkan). Changing the multiplier afterward doesn't need another restart.

---

**Known Issues (Alpha)**

Being upfront about what isn't done yet:
* Sign text isn't rendered yet.
* End portals are visible but don't look like vanilla's yet.
* Held light sources (a torch in your hand) glow but don't light up their surroundings.
* Volumetric clouds can flicker.
* Not ported yet: weather, vanilla (non-volumetric) clouds, block selection outline, block-breaking animation, name tags, enchantment glint, damage flash.
* Windows only - no Linux/macOS support planned for now.

Found something else broken? Reports with your GPU, driver version, and `latest.log` are the most useful kind.

---

**Credits**

Radiante is a fork of **[Radiance](https://modrinth.com/mod/radiance-mod-windows)** and its native renderer **MCVR**, originally by **LJIONG** and **Interstellarss** ([upstream project](https://github.com/Minecraft-Radiance/Radiance)). This fork rebuilds the renderer on top of Minecraft 26.3's own Vulkan backend and keeps the original GPL-3.0 license and credits.

This project uses Vulkan. See [vulkan.org](https://www.vulkan.org/) for more information.

This project uses NVIDIA's DLSS (Deep Learning Super Sampling). See [NVIDIA's DLSS page](https://www.nvidia.com/en-us/geforce/technologies/dlss/) and the [DLSS GitHub](https://github.com/NVIDIA/DLSS).

This project uses AMD's FSR 3. See [FidelityFX Super Resolution 3](https://gpuopen.com/fidelityfx-super-resolution-3/).

This project uses Intel's XeSS. See the [XeSS developer guide](https://www.intel.com/content/www/us/en/developer/articles/technical/xess-sr-developer-guide.html).

Thanks to the open-source libraries this renderer depends on, including [NRD](https://github.com/NVIDIA-RTX/NRD), [GLM](https://github.com/icaven/glm), [STB Image](https://github.com/nothings/stb), and [Vulkan Memory Allocator](https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator).

**Dependencies:**
* Fabric API (required)

**Client-Side** - Radiante only changes how the world renders on your machine; it does not need to be installed on a server. It's also **not compatible** with other rendering mods (Sodium, Iris, etc.) since it replaces the renderer itself.

---

**Disclaimer**

* This is a community-made, third-party project and is **not affiliated with, authorized, sponsored, endorsed by, or otherwise officially connected to Mojang Studios or Microsoft**. NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
* **Minecraft** and related names, logos, and assets are trademarks and/or intellectual property of Mojang Studios and/or Microsoft.
* This project is also **not affiliated with, sponsored by, or endorsed by NVIDIA, AMD, or Intel**. NVIDIA/GeForce/RTX/DLSS, AMD/FSR, and Intel/XeSS are trademarks of their respective owners.
* This mod is provided **"AS IS"**, without warranties, as alpha software. You assume all risks from its use (crashes, visual glitches, incompatibilities with other mods). **Back up your worlds and configs before installing.**
