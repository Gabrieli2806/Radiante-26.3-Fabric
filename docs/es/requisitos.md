[← Volver al índice](README.md)

# Requisitos

## Sistema

| | |
|---|---|
| Sistema operativo | Windows x64. Es la única plataforma soportada por ahora (ver [ROADMAP.md](../../ROADMAP.md), sección "Investigate: backport a 26.1" para el estado de otras versiones; Linux/macOS no están planeados a corto plazo). |
| Minecraft | 26.3 |
| Java | 25 |
| GPU | Una tarjeta con soporte de trazado de rayos por hardware en Vulkan: `VK_KHR_ray_tracing_pipeline` y `VK_KHR_acceleration_structure`. En la práctica, NVIDIA RTX serie 20 o superior, AMD RX 6000 o superior, o Intel Arc. |
| API gráfica | Vulkan. El juego tiene que estar usando el backend Vulkan de `com.mojang.renderpearl`, no OpenGL — ver [instalación](instalacion.md#primer-inicio) y [problemas comunes](problemas.md#el-juego-está-en-opengl). |

Si tu GPU no reporta soporte de trazado de rayos, el juego sigue funcionando con el renderizador
propio de Minecraft; Radiante simplemente no se activa. No hay una ruta de reserva por software.

## Un loader, con su propia versión mínima

Necesitas Minecraft 26.3 con **uno** de estos tres:

| Loader | Versión mínima |
|---|---|
| Fabric | Fabric Loader `0.19.5+` y Fabric API `0.160.5+26.3` |
| NeoForge | `26.3.0.10-beta+` |
| Forge | `26.3-66.0.3+` |

Las tres compilaciones vienen del mismo código y se comportan igual visualmente. La diferencia
principal entre ellas hoy es que **Generación de Fotogramas y NVIDIA Reflex solo funcionan en
Fabric** por un problema de carga de Streamline en NeoForge y Forge — ver
[frame-generacion-reflex.md](frame-generacion-reflex.md) y
[ROADMAP.md](../../ROADMAP.md#frame-generation-and-reflex-on-forge--neoforge--investigate).

## Lo que no necesitas descargar aparte

DLSS, FSR 3, XeSS y NRD vienen incluidos dentro del propio mod — no hay que bajar DLLs de NVIDIA,
AMD o Intel por separado ni copiarlas a mano. Qué opciones aparecen disponibles en la pantalla de
ajustes depende de tu GPU y tu driver, no de si instalaste algo extra:

- El modo **DLSS** (el preset "RT-DLSS") solo aparece en GPUs NVIDIA con un driver que lo soporte.
- **FSR 3** y **XeSS** funcionan en cualquier fabricante — no necesitas una GPU AMD para usar FSR ni
  una Intel para usar XeSS.
- **NVIDIA Reflex** y la **Generación de Fotogramas de DLSS** son, como su nombre indica, exclusivos
  de GPUs NVIDIA.

Si un preset no aparece en el desplegable de Pipeline, tu GPU o driver no cumplen lo que ese preset
necesita — no es un paso de instalación que te falte.

## Recomendado, no obligatorio

- Un **resource pack con mapas LabPBR** (`_n`/`_s`) o un `.mcpack` de Bedrock RTX convertido por el
  propio Radiante, para aprovechar relieve y materiales — ver [bedrock-rtx.md](bedrock-rtx.md). Sin
  uno, el mod sigue funcionando con los mapas que trae para 154 texturas vanilla.
- [Distant Horizons](https://modrinth.com/mod/distanthorizons) si quieres terreno lejano trazado
  también — soporte experimental, ver [distant-horizons.md](distant-horizons.md).
- Una pantalla con **HDR** si quieres usar la salida HDR — ver [hdr.md](hdr.md).
