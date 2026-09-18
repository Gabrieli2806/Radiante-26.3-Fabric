# Third party notices

Radiante itself is licensed under the GNU General Public License v3.0, as is Radiance,
the mod it derives from. This file covers the third party components redistributed
inside the mod jar, which are **not** under the GPL and carry their own terms.

This software contains source code provided by NVIDIA Corporation.

## Binaries shipped in `radiante-native/`

| File | Component | Owner | License |
| --- | --- | --- | --- |
| `streamline/sl.interposer.dll`, `sl.common.dll`, `sl.pcl.dll` | Streamline SDK | NVIDIA | [MIT](NVIDIA-Streamline-LICENSE.txt) |
| `streamline/sl.dlss_g.dll`, `streamline/nvngx_dlssg.dll` | DLSS Frame Generation | NVIDIA | [NVIDIA RTX SDK](NVIDIA-RTX-SDK-LICENSE.txt) |
| `streamline/sl.reflex.dll`, `streamline/NvLowLatencyVk.dll` | Reflex | NVIDIA | [NVIDIA RTX SDK](NVIDIA-RTX-SDK-LICENSE.txt) |
| `libxess.dll`, `libxess_dx11.dll`, `libxess_fg.dll` | XeSS | Intel | [Intel XeSS](Intel-XeSS-LICENSE.txt) |

`core.dll` is Radiante's own renderer and is GPL-3.0. It links statically against the
components below.

## Statically linked components

| Component | Owner | License |
| --- | --- | --- |
| DLSS / NGX SDK (Super Resolution, Ray Reconstruction) | NVIDIA | [NVIDIA RTX SDK](NVIDIA-RTX-SDK-LICENSE.txt) |
| NRD (NVIDIA Real-time Denoisers) | NVIDIA | [NVIDIA RTX SDK](NVIDIA-NRD-LICENSE.txt) |
| FidelityFX SDK (FSR) | AMD | [MIT](AMD-FidelityFX-LICENSE.txt) |

## DLSS feature DLLs

The DLSS Super Resolution and Ray Reconstruction feature libraries (`nvngx_dlss.dll`,
`nvngx_dlssd.dll`) are **not** redistributed in this jar. They are loaded from the
installed NVIDIA display driver at runtime.

## Note on the NVIDIA RTX SDK terms

Section 2 of the NVIDIA RTX SDK license sets conditions on redistributing its binaries:
the application must add material functionality beyond the SDK, the notice "This
software contains source code provided by NVIDIA Corporation" must accompany derived
source, and the SDK may not be distributed as a standalone product. Radiante is a
Minecraft renderer that uses these components as one part of a larger work, and the
notice appears above.
