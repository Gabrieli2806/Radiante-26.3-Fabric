[← Volver al índice](README.md)

# Instalación

Revisa antes los [requisitos](requisitos.md): GPU, sistema y versión del loader. Radiante es solo
de cliente: no hace falta en el servidor, y entrar a un servidor sin él funciona igual.

Hay un jar por loader (`radiante-fabric-…`, `radiante-neoforge-…`, `radiante-forge-…`), y cada uno
sirve para Windows y Linux. Descárgalo de [Modrinth](https://modrinth.com/project/radiante),
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/radiante) o
[GitHub](https://github.com/Gabrieli2806/Radiante-26.3-Fabric/releases).

## Fabric

1. Instala [Fabric Loader](https://fabricmc.net/use/) `0.19.5` o superior para Minecraft 26.3.
2. Pon **Fabric API** `0.160.5+26.3` o superior en `mods`.
3. Pon el jar de Radiante para Fabric en `mods`.
4. Inicia el perfil de Fabric.

## NeoForge

1. Instala [NeoForge](https://neoforged.net/) `26.3.0.10-beta` o superior.
2. Pon el jar de Radiante para NeoForge en `mods`.
3. Inicia el perfil de NeoForge.

## Forge

1. Instala [Forge](https://files.minecraftforge.net/) `26.3-66.0.3` o superior.
2. Pon el jar de Radiante para Forge en `mods`.
3. Inicia el perfil de Forge.

## Qué loader elegir

El que use tu modpack. Los tres corren el mismo código, generación de fotogramas y Reflex incluidos.
Fabric es el más probado; NeoForge y Forge comparten el código pero se han probado menos.

## En Linux

Mismo jar, mismos pasos. Si usas un launcher en Flatpak, mira antes [Linux](linux.md): el sandbox
tiene que ver el driver de tu GPU.

## Primer inicio

### Se desempaqueta el renderizador

La primera vez, Radiante desempaqueta sus librerías nativas en la carpeta `radiante/` del juego y
comprueba cada una contra su SHA-256. Tarda un momento; en los inicios siguientes solo se repite si
cambió la versión.

### Se ofrece DLSS o XeSS

FSR viene incluido y es el escalador por defecto. En el menú principal, una GPU NVIDIA verá una
ventana ofreciendo DLSS (~115 MB) y una Intel en Windows, XeSS (~73 MB). Puedes descargarlo,
dejarlo para después o no volver a verlo. Detalle en [Escaladores](escaladores.md).

### El juego tiene que estar en Vulkan

Minecraft 26.3 deja elegir la API gráfica. Radiante necesita Vulkan. Si el juego arranca en OpenGL,
una pantalla lo explica y ofrece cambiar y reiniciar. Ver
[Problemas comunes](problemas.md#el-juego-está-en-opengl).

### Si tu GPU no tiene trazado de rayos

Verás "Radiante: trazado de rayos no disponible" y el juego sigue con el renderizador de Minecraft.
Pasa también con Vulkan por software (`llvmpipe`). Ver
[Problemas comunes](problemas.md#mi-gpu-no-soporta-trazado-de-rayos).

<!-- Captura pendiente: la pantalla "Radiante: trazado de rayos no disponible" completa -->

### Si todo va bien

Radiante se activa al entrar a un mundo. Pipeline por defecto: RT-DLSS si DLSS ya está instalado,
si no RT-NRD-FSR.

### Teclas

| Tecla | Acción |
|---|---|
| `F6` | Abre los ajustes de Radiante. |
| `F7` | Enciende o apaga el trazado de rayos al instante. |

Se pueden cambiar en los controles de Minecraft, en la categoría Varios.

## Después de instalar

- [Ajustes](opciones.md): la pantalla de ajustes.
- [Bedrock RTX](bedrock-rtx.md): resource packs con más detalle.
- [Problemas comunes](problemas.md) si algo no arranca o se ve mal.
