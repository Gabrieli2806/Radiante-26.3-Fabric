# Radiante 26.3 — Plan de port

Fork de [Radiance](https://github.com/Minecraft-Radiance/Radiance) (Java, Fabric 1.21.4) + [MCVR](https://github.com/Minecraft-Radiance/MCVR) (C++ Vulkan, RT/DLSS/FSR/XeSS/NRD) a Minecraft **26.3** usando el backend Vulkan nativo del juego.

Código de referencia clonado en `upstream/Radiance` y `upstream/MCVR` (con submódulos).

## 1. Estado de partida (upstream, v0.1.5, abril 2026)

| Parte | Tamaño | Qué hace |
|---|---|---|
| Radiance (Java) | ~18k líneas, 86 mixins, Yarn 1.21.4 | Reemplaza OpenGL: intercepta GlStateManager/ShaderProgram/BufferRenderer, extrae chunks/entidades, 80 métodos JNI |
| MCVR core (C++) | ~36k líneas | Crea su **propio** VkInstance/VkDevice/swapchain sobre la ventana GLFW del juego, emula el pipeline GL de la UI, render de mundo por RT |
| MCVR shaders | ~28k líneas GLSL | Packs `vanilla-pt` y `advanced` (ReSTIR, nubes volumétricas, Disney BSDF) |
| Módulos | yaml en `resources/modules` | ray_tracing, nrd, dlss, fsr_upscaler, xess_sr, temporal_accumulation, tone_mapping, post_render |

## 2. Qué cambió en 26.3 (verificado sobre el client.jar 26.3)

1. **Código sin ofuscar, Yarn muerto.** Fabric usa nombres Mojang. Plugin `net.fabricmc.fabric-loom` (sin remap), Loom 1.18.x, Loader 0.19.5, Fabric API `0.160.5+26.3`, **Java 25**. Todos los mixins hay que reescribirlos con nombres Mojang (`WorldRenderer`→`LevelRenderer`, `MinecraftClient`→`Minecraft`, etc.).
2. **GLFW eliminado → SDL3** (`lwjgl-sdl 3.4.3`, `Window` usa `SDL_Event`, superficie con `SDL_Vulkan_CreateSurface`). `MCVR/src/core/glfw_bind.cpp` y el `WindowMixins` de Radiance quedan inservibles.
3. **Nueva capa de render `com.mojang.renderpearl`** con backends `opengl` y `vulkan` (`api.device.GpuBackend` / `GpuDevice`). Los mixins sobre `GlStateManager`, `GLX`, `ShaderProgram`, `BufferRenderer`, `RenderPhase*` ya no tienen objetivo.
4. **El backend Vulkan expone sus handles** (métodos públicos):
   - `VulkanDevice.vkDevice()`, `.vma()`, `.graphicsQueue()/computeQueue()/transferQueue()`, `.instance().vkInstance()`
   - `VulkanPhysicalDevice.vkPhysicalDevice()`, `VulkanGpuTexture.vkImage()`
   - `VulkanCommandEncoder.allocateAndBeginTransientCommandBuffer()`, `waitSemaphore/signalSemaphore` (sincronizar con otro renderer)
5. **Pero el device vanilla NO tiene lo necesario para RT/DLSS.** Extensiones que activa (`VulkanFeatureSets`): `swapchain, dynamic_rendering, push_descriptor, synchronization2, multi_draw, vertex_attribute_divisor, calibrated_timestamps` (+ checkpoints de debug). Faltan: `KHR_acceleration_structure`, `KHR_ray_tracing_pipeline`, `KHR_deferred_host_operations`, `KHR_spirv_1_4`, `EXT_extended_dynamic_state2/3`, features de 1.2 (`bufferDeviceAddress`, `descriptorIndexing`, `runtimeDescriptorArray`…) y las extensiones que pide NGX para DLSS.

## 3. Arquitectura propuesta: device compartido

```
 Minecraft 26.3 (renderpearl, backend Vulkan)
   ├─ VulkanInstance  ← mixin: + extensiones de instancia de NGX/XeSS, apiVersion ≥ 1.3
   ├─ VulkanBackend.createDevice ← mixin: + extensiones RT + features pNext
   ├─ UI / texto / GUI / swapchain / present → vanilla sin tocar
   └─ LevelRenderer (mundo) → cancelado, delegado a:
         MCVR (JNI) adopta VkInstance/VkPhysicalDevice/VkDevice/queue del juego
           ├─ TLAS/BLAS desde secciones de chunk + entidades
           ├─ RT → NRD / DLSS-RR / FSR3 / XeSS → tone mapping
           └─ escribe en la VkImage del render target principal (VulkanGpuTexture.vkImage())
```

**Por qué así (y no port directo):**
- Elimina todo el emulador GL de MCVR y Radiance: `PipelineStateProxy`, `DrawCommandProxy`, `ShaderProxy`, overlay pipeline, swapchain/window propios, ~30 mixins de GL. Es la "simplificación" real.
- La UI, mods de HUD y resource packs funcionan tal cual porque los pinta vanilla.
- Un solo VkDevice → sin copias entre devices, sin conflicto de ventana SDL.

**Riesgos a resolver:**
- **Submits en la misma cola**: `VkQueue` no es thread-safe. MCVR debe usar la `VulkanQueue.Submission` del juego o un lock común (mixin en `VulkanQueue.beginSubmit`).
- **Orden de frame**: MCVR renderiza tras la preparación del frame y antes de la UI; sincronizar con los semáforos de `VulkanCommandEncoder`.
- **Dos VMA** en el mismo device: válido; MCVR mantiene el suyo (requiere `bufferDeviceAddress`).
- **OpenGL**: 26.3 aún permite elegir OpenGL. El mod forzará backend Vulkan (opción `graphicsApi`) y mostrará error si el GPU no soporta RT.
- **Hardware**: solo GPUs con RT (RTX 20+, RX 6000+, Arc). Sin fallback raster.

## 4. Qué se conserva / reescribe / borra

| Componente | Acción |
|---|---|
| Shaders GLSL, módulos ray_tracing/nrd/dlss/fsr/xess/tone_mapping/post_render | **Conservar** casi intactos |
| `core/vulkan/instance, device, physical_device` | **Reescribir**: modo "adoptar handles externos" |
| `core/vulkan/window, swapchain, framebuffer, render_pass`, `glfw_bind.cpp` | **Borrar** |
| Middleware JNI de overlay/pipeline-state/shader/draw | **Borrar** |
| `chunks`, `entities`, `textures`, `emission`, `world` (C++) | Conservar, ajustar entrada de datos |
| `ChunkProxy`, `EntityProxy`, `PBRVertexConsumer`, `EmissionRecorder`, `AuxiliaryTextures` (Java) | **Portar** a Mojmap + `SectionRenderDispatcher` de 26.x |
| Mixins `vanilla_resource_tracker` (texturas/atlas/fuentes) | Portar; las fuentes quizá sobran (UI vanilla) |
| GUI `RenderPipelineScreen`, `ShaderPackScreen`, options | Portar a la API de pantallas de 26.3 |
| `temporal_accumulation`, `svgf` | Evaluar quitar (NRD/DLSS-RR los cubren) |
| Assets `minecraft/models`, `textures` de emisión | Conservar |

## 5. Fases

| Fase | Entregable | Criterio |
|---|---|---|
| 0 | Proyecto Fabric 26.3 vacío (`com.g2806.radiante`, Java 25, Loom 1.18) | `runClient` abre el juego |
| 1 | Mixins en `VulkanInstance`/`VulkanBackend` que añaden extensiones RT; log de handles | Juego vanilla corre con device extendido |
| 2 | MCVR refactor: CMake sin GLFW/swapchain, init con handles externos, JNI mínimo | Native carga, dibuja un triángulo RT en la imagen principal |
| 3 | Chunks → BLAS/TLAS, texturas/atlas, cámara | Terreno path-traced visible |
| 4 | Entidades, partículas, mano, fluidos, cielo/nubes | Paridad visual con Radiance 0.1.5 |
| 5 | DLSS-RR / NRD / FSR3 / XeSS + GUI de pipeline | Selección de upscaler en juego |
| 6 | CI GitHub Actions (Windows + Linux natives), empaquetado | Jar único con natives |

Fase 3 y 4 son el grueso del trabajo.

## 6. Licencia (bloqueante)

Radiance y MCVR son **GPL-3.0** (partes Apache-2.0/MIT listadas en `MCVR/LICENSE.md`). Un fork es obra derivada: **debe seguir siendo GPL-3.0**, publicar el código fuente y mantener los avisos de copyright originales. No se puede poner MIT. Los binarios DLSS (`nvngx_dlss*.dll`) no se pueden redistribuir; el usuario los descarga, igual que upstream.
