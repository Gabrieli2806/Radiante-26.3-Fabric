# Port a Minecraft 26.4

Rama: `port/26.4-snapshots`, creada desde `main` (`5b223a2`).
Objetivo inicial: `26.4-snapshot-3` (6 de octubre de 2026), Fabric Loader 0.19.5,
Fabric API 0.162.2+26.4, Java 25 y Loom 1.18.1.

## Arquitectura y puntos sensibles

- `common/`: renderizador Java, opciones, conversor de packs Bedrock, compatibilidad con Distant Horizons
  y mixins de Minecraft. Los módulos de cada loader compilan estas fuentes directamente.
- `fabric/`, `neoforge/`, `forge/`: entrada del loader y adaptadores de plataforma/eventos/modelos.
- `native/`: renderizador C++/Vulkan y shaders. Adopta el dispositivo de Minecraft; no crea otro.
  JNI conecta geometría, texturas y uniformes con Java. El juego conserva GUI y presentación.
- Mixins críticos: creación del dispositivo Vulkan y sus extensiones RT, sincronización de colas,
  presentación HDR/frame generation, extracción del mundo y seguimiento de texturas/atlas.
- Los nativos se instalan en `common/src/main/resources/radiante-native/`, carpeta ignorada por Git.
  El empaquetado los comprime con XZ; las ejecuciones de desarrollo usan las bibliotecas instaladas.

## Configuración de la rama

`enabled_loaders=fabric` evita configurar loaders sin una publicación compatible con la snapshot.
`common` usa NeoForm `26.4-snapshot-3-1`. Las versiones 26.3 de Forge y NeoForge se conservan
como referencia, pero esos módulos están deshabilitados. No habilitarlos sin actualizar sus versiones.
Distant Horizons sigue siendo una dependencia **solo de compilación** de 26.3; su ejecución en 26.4
requiere una versión compatible y una prueba independiente.

Las pruebas usan `run-26.4/`, separada de `run/`. Fabric Loader normaliza el nombre de la snapshot
a `26.4-alpha.3`; ese valor se usa en `fabric.mod.json`, mientras Gradle descarga el ID original.

## Adaptaciones iniciales

- `AbstractTexture` fue reemplazada por `TextureHandle`; la imagen GPU se obtiene desde su vista.
- Los atlas ahora devuelven `TextureResources`; la imagen GPU está en `texture()`.
- F3 usa grupos de depuración en lugar de `addLine`.
- `Math.clamp` sustituye las llamadas eliminadas de `Mth.clamp`.
- El constructor de `SkyRenderer` ya no recibe el render target.
- `LevelRenderer.render` eliminó argumentos; el callback que inicia el trazado ya no captura argumentos sin usar.
  La decisión del cielo se intercepta en `shouldRenderSky` y el terreno transparente en `renderLayers`.
- El pase principal ahora limpia color y profundidad **después** del pase trazado. Se conservan ambos adjuntos
  durante el overlay de Radiante; de otro modo se veía únicamente el color del fondo aunque JNI renderizara.
- `SkyRenderState` incorpora un oclusor de cielo en lugar del antiguo disco oscuro.
  Falta verificar su equivalencia visual y los ángulos nuevos en el renderizador nativo.
- El conversor Bedrock genera packs con formato de recursos 100, verificado en `version.json` del cliente.

## Comandos

```powershell
.\gradlew.bat :fabric:compileJava
.\gradlew.bat :fabric:build
.\gradlew.bat :fabric:runClient
```

## Validación inicial completada

La compilación inicial encontró 36 errores de API. Tras las adaptaciones:

- `:common:compileJava :fabric:build`: **BUILD SUCCESSFUL**.
- `:fabric:runClient`: **BUILD SUCCESSFUL**, con cierre automático sin crash.
- Windows, RTX 5070, backend Vulkan y pipeline RT-DLSS; se reutilizaron los nativos locales existentes.
- Mundo nuevo `Radiante26.4Terrain`, carga de terreno, atlas/PBR y entidades.
- Alternancia RT → vanilla → RT, con revisión visual de las capturas antes y después de volver a RT.
- Capturas locales: `run-26.4/screenshots/26.4-rt-fixed.png`, `26.4-vanilla-fixed.png`
  y `26.4-rt-return-fixed.png`.
- JAR local: `fabric/build/libs/radiante-fabric-0.7.0+26.4-snapshot-3.jar` (~14.8 MB).
- El cliente de desarrollo usa una sesión local sin autenticación; los errores de servicios Realms/401
  del registro no impidieron la prueba en un jugador.

Prueba reproducible (solo mundo de pruebas, en la carpeta de ejecución separada):

```powershell
$env:RADIANTE_DEV_PRESCRIPT='100:testworld=Radiante26.4Terrain'
$env:RADIANTE_DEV_SCRIPT='60:cmd=/time set noon;180:shot=26.4-rt;200:fps;220:rt=false;280:shot=26.4-vanilla;300:rt=true;400:shot=26.4-rt-return;440:quit'
.\gradlew.bat :fabric:runClient --console=plain
Remove-Item Env:RADIANTE_DEV_PRESCRIPT, Env:RADIANTE_DEV_SCRIPT
```

En `run-26.4/options.txt`, `pauseOnLostFocus:false` permite capturar sin el menú de pausa al perder el foco.

## Corrección de entidades e ítems negros

En 26.4, `VulkanGpuTexture.close()` libera la referencia del propietario, pero las vistas pueden mantener
viva la imagen. `OverlayTexture` cierra esa referencia inmediatamente tras cargar sus píxeles.
Radiante retiraba y reciclaba su identificador demasiado pronto, sustituyendo el overlay neutro por otra
textura. El registro ahora se retira en `destroy()`, cuando la imagen realmente se destruye.

Validado en el mundo separado `Radiante26.4Textures`: vaca, cerdo, espada en mano, diamante arrojado y
skin del jugador en tercera persona tienen texturas visibles con RT-DLSS, incluso tras alternar
RT → vanilla → RT. El cliente cerró correctamente (`BUILD SUCCESSFUL`). Capturas locales:
`entities-textures-fixed.png`, `player-texture-fixed.png` y `entities-textures-rt-return.png`
en `run-26.4/screenshots/`.

## Pendiente antes de una publicación

- HDR, Reflex y frame generation en juego, además de FSR/XeSS, otras GPUs y Linux.
- Nuevo oclusor del cielo y cambios de niebla de 26.4, dimensiones, agua, vidrio, animaciones y packs Bedrock.
- Compatibilidad con otros mods, especialmente Distant Horizons y overlays.
- Instalación del JAR fuera del entorno de desarrollo y reconstrucción de nativos en CI.
- Adaptar CI a la selección de loaders: el workflow actual de 26.3 todavía espera los tres JARs.
- Ports de Forge/NeoForge cuando existan versiones de loader adecuadas.

Esto es una base de port con prueba de funcionamiento inicial, no una validación de todas las funciones.

## Fuentes

- https://meta.fabricmc.net/v2/versions/game
- https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml
- https://maven.neoforged.net/releases/net/neoforged/neoform/maven-metadata.xml
- Clases y `version.json` del cliente oficial `26.4-snapshot-3`.
