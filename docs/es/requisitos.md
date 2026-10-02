[← Volver al índice](README.md)

# Requisitos

## Sistema

| | Windows | Linux |
|---|---|---|
| Arquitectura | x64 | x86-64 |
| Versión mínima | Windows 10/11 | glibc 2.35 o más reciente: Ubuntu 22.04, Debian 12, Fedora 36 o posteriores |
| API gráfica | Vulkan | Vulkan, sobre el driver real de la GPU (no `llvmpipe`) |
| Java | 25 | 25 |
| Minecraft | 26.3 | 26.3 |

El mismo jar sirve para los dos sistemas: trae el renderizador de Windows (`core.dll`) y el de Linux
(`libcore.so`) y elige el correcto al arrancar. macOS no está soportado.

En Linux hay detalles propios (launchers en Flatpak, Wayland, glibc): ver [Linux](linux.md).

## GPU

Hace falta trazado de rayos por hardware en Vulkan: `VK_KHR_ray_tracing_pipeline` y
`VK_KHR_acceleration_structure`. No hay modo por software; sin eso el juego sigue con el renderizador de Minecraft.

| | NVIDIA | AMD | Intel |
|---|---|---|---|
| Tarjetas | RTX 20 o superior | RX 6000 o superior | Arc |
| FSR (escalado y generación de fotogramas) | Sí, incluido | Sí, incluido | Sí, incluido |
| DLSS (escalado, Ray Reconstruction, generación de fotogramas) | Sí, descarga en el juego (~115 MB), Windows y Linux | No | No |
| XeSS (~73 MB, solo Windows) | Se puede instalar; funciona si el driver pasa el chequeo de XeSS | Igual que NVIDIA | Recomendado; se ofrece al primer inicio |
| NVIDIA Reflex | Sí, si el driver tiene `VK_NV_low_latency2` y `VK_KHR_present_id` | No | No |
| Shader Execution Reordering | RTX 40 o superior | No | No |

NVIDIA es el objetivo principal hoy: DLSS Ray Reconstruction es el camino con menos ruido. AMD e Intel funcionan con
FSR (y XeSS), pero todavía no están probados con la misma profundidad.

Cómo se descargan DLSS y XeSS: [Escaladores](escaladores.md).

## Loader

Minecraft 26.3 con **uno** de estos:

| Loader | Versión mínima |
|---|---|
| Fabric | Fabric Loader `0.19.5+` y Fabric API `0.160.5+26.3` |
| NeoForge | `26.3.0.10-beta+` |
| Forge | `26.3-66.0.3+` |

Los tres jars salen del mismo código y se ven igual. La generación de fotogramas y Reflex ya no dependen de
Streamline ni de ningún loader: son el mismo código en los tres. Están probados en Fabric (Windows); en NeoForge y
Forge comparten el código pero todavía no se han probado.

## Recomendado, no obligatorio

- Un resource pack con mapas LabPBR (`_n`/`_s`) o un `.mcpack` de Bedrock RTX: ver [Bedrock RTX](bedrock-rtx.md).
  Sin uno, Radiante usa los mapas que trae para 154 texturas vanilla.
- [Distant Horizons](https://modrinth.com/mod/distanthorizons) para trazar también el terreno lejano: ver
  [Distant Horizons](distant-horizons.md).
- Una pantalla HDR para la salida HDR: ver [HDR](hdr.md).
