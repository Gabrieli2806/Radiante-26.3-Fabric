[← Back to index](README.md)

# Linux

Radiante runs on Linux x86-64 since 0.5.0, with the same jar as Windows.

## What you need

- **glibc 2.35 or newer.** Ubuntu 22.04, Debian 12, Fedora 36, Arch, and any distribution from
  that time on. On an older one the renderer does not load (`GLIBC_2.xx not found` in the log).
- **Your GPU's real Vulkan driver**: NVIDIA's proprietary one, or Mesa (RADV for AMD, ANV for
  Intel) with ray tracing support. Check with `vulkaninfo --summary`: your GPU must show up, not
  `llvmpipe`.
- **Java 25** and a launcher that uses it.

DLSS works on Linux (downloaded as on Windows). XeSS does not: Intel ships no Linux runtime. See
[Upscalers](upscalers.md).

## Flatpak launchers

Prism Launcher and other launchers installed as Flatpak run in a sandbox. If the game falls back to
`llvmpipe` or does not see your GPU:

- On NVIDIA, the Flatpak needs the driver extension that **exactly** matches the system driver
  version (`org.freedesktop.Platform.GL.nvidia-<version>`). `flatpak update` usually installs it.
- Check inside the sandbox: `flatpak run --command=vulkaninfo <launcher-id> --summary`.

## Wayland and X11

Both work. If you see presentation problems (black screen, flicker when switching windows), try the
launcher on X11/XWayland to rule out the compositor.

## Checklist if it does not start

1. `latest.log` has `[native]` lines with the renderer's message: the first place to look.
2. `GLIBC_2.xx not found` → the distribution is too old.
3. `UnsatisfiedLinkError` → the renderer could not be unpacked or loaded. Delete `libcore.so` from
   the game's `radiante/` folder and start again: it is unpacked and checked again.
4. The log says there is no ray tracing or names `llvmpipe` → the game is not using your GPU's
   driver (see Flatpak above).
5. `FSR3 CreateContext` fails → a version before 0.5.0, which had that bug on Linux. Update.
6. Black world and nothing in the log → make sure no other game instance with another mod version
   is open.

More cases in [Common problems](problems.md).
