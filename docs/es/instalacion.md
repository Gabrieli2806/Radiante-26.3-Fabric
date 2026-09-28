[← Volver al índice](README.md)

# Instalación

Revisa antes los [requisitos](requisitos.md) — en particular la GPU y la versión mínima del loader.
Radiante es solo de cliente: no hace falta instalarlo en el servidor, y unirte a un servidor sin él
funciona igual, solo que sin trazado de rayos.

## Fabric

1. Instala [Fabric Loader](https://fabricmc.net/use/) `0.19.5` o superior para Minecraft 26.3.
2. Descarga **Fabric API** `0.160.5+26.3` o superior y colócala en la carpeta `mods`.
3. Descarga Radiante y colócalo también en `mods`.
4. Inicia el juego con el perfil de Fabric.

## NeoForge

1. Instala [NeoForge](https://neoforged.net/) `26.3.0.10-beta` o superior.
2. Descarga Radiante y colócalo en `mods`.
3. Inicia el juego con el perfil de NeoForge.

NeoForge no necesita un equivalente a Fabric API; NeoForge ya trae lo que Radiante necesita.

## Forge

1. Instala [Forge](https://files.minecraftforge.net/) `26.3-66.0.3` o superior.
2. Descarga Radiante y colócalo en `mods`.
3. Inicia el juego con el perfil de Forge.

## Qué versión elegir

Todas corren el mismo mod y se ven igual. La única diferencia funcional hoy: **Generación de
Fotogramas y NVIDIA Reflex solo están disponibles en Fabric** (ver
[frame-generacion-reflex.md](frame-generacion-reflex.md)). Si esas dos funciones te importan y tu
modpack no te obliga a NeoForge o Forge, Fabric es la opción más completa por ahora.

## Primer inicio

### El juego tiene que estar en Vulkan

Minecraft 26.3 deja elegir la API gráfica (OpenGL o Vulkan). Radiante necesita Vulkan — el trazado
de rayos no existe en OpenGL. Si el juego arranca en OpenGL, verás una pantalla explicándolo con la
opción de cambiar a Vulkan y reiniciar. Ver [problemas.md](problemas.md#el-juego-está-en-opengl)
para más detalle.

### Si tu GPU no soporta trazado de rayos

Si el juego arranca en Vulkan pero tu GPU o driver no reportan `VK_KHR_ray_tracing_pipeline`, verás
la pantalla "Radiante: trazado de rayos no disponible" con dos opciones: continuar sin trazado de
rayos (el renderizador propio de Minecraft, como si Radiante no estuviera) o cambiar a OpenGL — donde
tampoco está disponible, así que en la práctica es la misma opción. Ver
[problemas.md](problemas.md#mi-gpu-no-soporta-trazado-de-rayos).

![Pantalla de trazado de rayos no disponible](../images/pantalla-rt-no-disponible.png)
<!-- TODO: la pantalla "Radiante: trazado de rayos no disponible" completa -->

### Si todo va bien

Con una GPU compatible y el juego en Vulkan, Radiante se activa solo al entrar a un mundo. La
primera vez usa sus valores por defecto: preset **RT-DLSS** si tu GPU es NVIDIA y lo soporta (si no,
el mejor preset disponible entre RT-NRD-FSR, RT-NRD-XeSS o RT-NRD, en ese orden), calidad de imagen
sin ajustar a ningún nivel concreto todavía.

### Atajos de teclado

| Tecla | Acción |
|---|---|
| `F6` | Abre los ajustes de Radiante. |
| `F7` | Alterna el trazado de rayos encendido/apagado sin abrir el menú — vuelve al renderizador de Minecraft al instante. |

Ninguno de los dos tiene un ajuste in-game todavía para remapearse desde la pantalla de Radiante;
usa el menú de controles estándar de Minecraft ("Abrir Ajustes de Radiante" / "Alternar Trazado de
Rayos", bajo Varios) si quieren otra tecla.

## Después de instalar

- La pantalla de ajustes está explicada en [opciones.md](opciones.md).
- Si quieres más definición visual, mira [bedrock-rtx.md](bedrock-rtx.md) para un resource pack, o
  revisa qué mapas LabPBR trae Radiante ya incluidos para texturas vanilla.
- Si algo no arranca o se ve mal, [problemas.md](problemas.md) cubre los casos más comunes.
