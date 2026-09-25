# Radiante

Renderizado con trazado de rayos para Minecraft 26.3 (Fabric, NeoForge y Forge), construido sobre el backend
Vulkan propio de Minecraft.

[GitHub](https://github.com/Gabrieli2806/Radiante-26.3-Fabric) · [Modrinth](https://modrinth.com/project/radiante) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/radiante) · [Discord](https://discord.gg/DhBbAzugZ9)

Idiomas: [EN](README.md) · **ES**

Radiante es un fork de [Radiance](https://github.com/Minecraft-Radiance/Radiance) y su renderizador nativo
[MCVR](https://github.com/Minecraft-Radiance/MCVR), reescrito para el backend Vulkan "render pearl"
(`com.mojang.renderpearl`) introducido en Minecraft 26.3. En lugar de crear su propio dispositivo Vulkan junto
al del juego, comparte el que Minecraft ya creó, así que el trazador de rayos y la GUI vanilla dibujan en el
mismo frame.

## Características

- Trazado de rayos por hardware (`VK_KHR_ray_tracing_pipeline`) para el terreno, con iluminación directa
  path-traced, sombras e iluminación global.
- Cielo, sol y dispersión atmosférica físicamente basados.
- Un shader pack incluido, `vanilla-pt`, con muestreo directo de luces de bloque (antorchas, lava, lámparas),
  niebla volumétrica y nubes, lluvia/nieve con vectores de movimiento correctos, e iluminación pixelada para
  entidades y bloques.
- Soporte LabPBR (mapas `_s`/`_n`) para bloques, atlases y entidades/ítems en mano, con emisión por entidad
  (marcos de ítem brillantes, cristales de END, mobs autoluminosos). Se recomienda usar un resource pack
  personalizado con mapas PBR (o un `.mcpack` de Bedrock) para aprovechar al máximo estas funciones.
- Escalado mediante DLSS, FSR 3 y XeSS, más denoising con NRD.
- Motion blur y profundidad de campo, ambos activables/desactivables.
- Soporte experimental de [Distant Horizons](https://modrinth.com/mod/distanthorizons): su terreno lejano se
  traza con path tracing junto con el resto del mundo, con un color por cara de bloque (opcional; sin el mod no
  cambia nada). Aún en ajuste: puede haber alguna costura o terreno que aparece de golpe, y el costo por frame
  crece con la distancia de render de DH.
- Soporte de resource packs `.mcpack` de Bedrock (incluyendo niebla y agua), detectados directamente en la
  lista de packs.
- Funciona sobre el dispositivo que Minecraft crea: sin segunda instancia de Vulkan, sin swapchain duplicado.

## Requisitos

- Windows x64 (única plataforma soportada por ahora).
- Una GPU con soporte de ray tracing en Vulkan (`VK_KHR_ray_tracing_pipeline` y
  `VK_KHR_acceleration_structure`).
- Minecraft 26.3 con uno de:
  - Fabric Loader 0.19.5+ y Fabric API 0.160.5+26.3,
  - NeoForge 26.3.0.10-beta+,
  - Forge 26.3-66.0.3+.
- Frame generation y NVIDIA Reflex están disponibles solo en Fabric por ahora (ver [ROADMAP.md](ROADMAP.md)).
- Java 25.

## Compilación

El mod incluye una librería nativa (`core.dll`) compilada desde `native/`.

```sh
# 1. renderizador nativo (toolchain de Visual Studio 2026, x64)
cmake -S native -B build/native -G "Visual Studio 18 2026" -A x64 -DMCVR_ENABLE_NRD=ON -DUSE_AMD=ON
cmake --build build/native --config Release -j 16

# 2. el mod, un jar por loader en fabric/, neoforge/ y forge/ build/libs
./gradlew.bat build
```

El paso de instalación de CMake copia los shaders y módulos a `common/src/main/resources/radiante-native/`;
el `core.dll` compilado va a la misma carpeta. `./gradlew.bat :fabric:runClient`, `:neoforge:runClient` o
`:forge:runClient` lanza un cliente de desarrollo; los tres comparten la carpeta `run/`.

### Estructura del proyecto

- `common/` - todo lo que es Minecraft puro: el renderizador, mixins, ajustes, assets, archivos nativos. Se
  compila solo contra Minecraft vanilla, para que no se cuele código específico de un loader.
- `fabric/`, `neoforge/`, `forge/` - cada uno compila las fuentes comunes junto con su propio pegamento: el
  entrypoint (keybinds, tick de cliente, pantalla de ajustes) y una implementación de `RadiantePlatform`
  (carpeta del juego, envío de meshes de Fabric, soporte de Streamline), registrada bajo `META-INF/services`.
- `native/` - el renderizador Vulkan en C++ (pipelines de ray tracing, shaders bajo `native/src/shader/`,
  integración de upscalers y denoiser) compilado con CMake, instalado en los recursos de `common/`.
- Las versiones de todo esto viven en el `gradle.properties` de la raíz.

Flags útiles al ejecutar (Fabric):

- `-PquickPlay="<nombre del mundo>"` — entra directo a un mundo.
- `-PvulkanValidation` — activa las capas de validación de Vulkan y etiquetas de depuración de render.

## Cómo funciona

- La creación de `VulkanInstance` / `VulkanDevice` se redirige a los puntos de entrada nativos `createMerged`,
  que añaden las extensiones de ray tracing, descriptor indexing y upscalers a lo que Minecraft haya pedido,
  y fusionan las cadenas de features.
- El acceso a la cola gráfica se serializa entre Minecraft y el renderizador mediante hooks instalados sobre
  los punteros de función de volk.
- Cada frame el renderizador graba sus command buffers de subida / mundo / composición y se los entrega a
  `VulkanCommandEncoder.execute` de Minecraft, luego espera en el timeline semaphore de Minecraft.
- El terreno se compila al formato de vértices PBR propio del renderizador desde `ModelBlockRenderer` /
  `FluidRenderer`, siguiendo el almacenamiento por secciones de Minecraft.
- Los atlases se cosen en la GPU en 26.3, así que la copia muestreada se reconstruye desde las imágenes de
  sprite, respetando el padding por sprite del stitcher.

## Cómo contribuir

Reportes de errores, ajustes de shaders y pull requests son bienvenidos.

### Preparar el entorno

1. Haz fork del repo y clona tu fork.
2. Sigue la sección [Compilación](#compilación) de arriba para tener una build nativa y un cliente de
   desarrollo funcionando.
3. Usuarios de VS Code: `.vscode/` trae tareas para el cliente de cada loader (build y debug), más
   configuraciones de attach en el puerto 5005. `File > Open Workspace` sobre la raíz del repo las detecta
   automáticamente.

### Al hacer cambios

- Crea una rama a partir de `main`; dale un nombre corto y descriptivo (`fix/rain-motion-vectors`,
  `feat/end-crystal-tint`).
- Mantén `common/` independiente del loader. Si un cambio necesita comportamiento específico de un loader,
  agrégalo detrás de `RadiantePlatform` e impleméntalo en `fabric/`, `neoforge/` y `forge/`, no con
  `instanceof` contra clases de un loader dentro de `common/`.
- Los cambios de shaders viven en `native/src/shader/`; los cambios del renderizador nativo en C++ en
  `native/src/core/` y `native/src/common/`. Iguala la densidad de comentarios y el estilo de nombres del
  código alrededor — los comentarios aquí explican *por qué* existe un valor o una comprobación, no qué hace
  la línea.
- Los arreglos de compatibilidad con mods deben ser generales, no atados a un solo mod. Si el mod X falla
  porque Radiante omite o reemplaza algo que vanilla hace (p. ej. `LevelRenderer.render`, cuyos hooks al
  inicio siguen ejecutándose gracias a `LevelRendererSkipMixin`), restaura ese comportamiento vanilla para que
  cualquier mod que dependa de él se beneficie, en vez de tratar a X por nombre. Solo recurre a un parche
  específico cuando no exista una solución general, y mantenlo opcional (reflexión, sin dependencia dura).
- Prueba en un mundo con prefijo `Radiante*` dentro de `run/saves/` (un superflat nuevo suele bastar) — nunca
  apuntes una ejecución de prueba/desarrollo a un mundo que realmente juegas. `-PquickPlay="<mundo>"` entra
  directo a uno.
- Antes de abrir un PR: recompila los nativos (`cmake --build ... --target INSTALL`), corre
  `./gradlew.bat build` para los tres loaders, y verifica el cambio en el juego (con captura de pantalla si es
  visual).
- Sin atribución de IA (líneas de co-autor, trailers tipo "Generated with...", etc.) en mensajes de commit ni
  descripciones de PR — escríbelos como propios.

### Pull requests

- Un cambio lógico por PR; mantén fuera del diff el formateo o reflow que no tenga que ver.
- Describe qué cambió y por qué, y cómo lo probaste (las capturas de pantalla son especialmente útiles para
  cambios visuales).
- Referencia el ítem correspondiente de [ROADMAP.md](ROADMAP.md) si el PR lo cierra o lo avanza.
- Los cambios grandes o arquitectónicos (nuevos render passes, nuevos backends de upscaler, trabajo de
  paridad entre loaders) son más fáciles de aceptar si se discuten antes en un issue o en
  [Discord](https://discord.gg/DhBbAzugZ9).

## Licencia

GPL-3.0, heredada de los proyectos originales. Radiance y MCVR son de LJIONG e Interstellarss; este fork
mantiene su licencia y créditos.
