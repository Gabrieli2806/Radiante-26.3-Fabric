[← Volver al índice](README.md)

# Compilar y publicar

Para quien quiere compilar Radiante o publicar una versión. Jugar no necesita nada de esto.

El mod tiene dos partes: el renderizador nativo en C++ (`native/`, compilado con CMake) y el mod en
Java (Gradle, un jar por loader). El renderizador se compila por sistema: `core.dll` en Windows,
`libcore.so` en Linux.

## Requisitos comunes

- Java 25.
- Vulkan SDK `1.4.341` o más reciente, con `VULKAN_SDK` definido y `glslangValidator` en el `PATH`.
- Las librerías NGX del SDK de DLSS, que no están en el repositorio (los runtimes de DLSS nunca se
  distribuyen). Una vez, en la versión que espera la compilación:

```sh
git clone --depth 1 --branch v310.9.1 https://github.com/NVIDIA/DLSS.git /tmp/dlss
mkdir -p native/extern/DLSS/lib
cp -r /tmp/dlss/lib/Windows_x86_64 native/extern/DLSS/lib/   # o Linux_x86_64 en Linux
```

## Windows

Visual Studio 2026 (toolchain x64).

```sh
cmake -S native -B build/native -G "Visual Studio 18 2026" -A x64 -DMCVR_ENABLE_NRD=ON -DUSE_AMD=ON
cmake --build build/native --config Release --target INSTALL -j 16
./gradlew.bat build
```

`--target INSTALL` copia `core.dll`, shaders y módulos a
`common/src/main/resources/radiante-native/`. Los jars quedan en `fabric/build/libs`,
`neoforge/build/libs` y `forge/build/libs`.

Para probar: `./gradlew.bat :fabric:runClient` (o `:neoforge:runClient`, `:forge:runClient`). Los
tres comparten la carpeta `run/`.

## Linux

cmake, ninja (o make), g++ 13 o más reciente, git y el Vulkan SDK.

```sh
native/build-linux.sh
./gradlew build
```

El script instala `libcore.so` en `radiante-native/linux-x64/`. Si CMake no encuentra Vulkan, pásale
`-DVulkan_LIBRARY=/usr/lib/x86_64-linux-gnu/libvulkan.so.1`. Para que el resultado cargue en
Ubuntu 22.04 compila en un sistema con glibc 2.35 (o comprueba con
`objdump -T libcore.so | grep -oE 'GLIBC_2\.[0-9]+' | sort -Vu | tail -1`).

## Un jar para los dos sistemas

Un jar compilado en un solo sistema trae solo su renderizador. Para uno que funcione en ambos, pon
`core.dll` y `linux-x64/libcore.so` en `radiante-native/` antes de `gradlew build`. En las versiones
publicadas las librerías van comprimidas con xz junto a `natives.sha256`; el mod las desempaqueta y
comprueba al primer inicio.

Comprobar un jar: `unzip -l <jar> | grep -E 'core.dll|libcore.so'`.

## GitHub Actions

`.github/workflows/build.yml` hace todo lo anterior en cada push a `main` o `multiloader` y en cada
pull request:

| Job | Qué hace |
|---|---|
| `native-windows` | Compila `core.dll` en Windows con el Vulkan SDK y el SDK de DLSS. |
| `native-linux` | Compila `libcore.so` en Ubuntu 22.04 con g++ 13 y comprueba la versión de glibc que pide. |
| `jars` | Junta los dos renderizadores, compila los tres jars y falla si alguno no trae ambos. |
| `release` | Solo si la versión es nueva: release en GitHub. |
| `publish` | Solo si la versión es nueva y está configurado: Modrinth y CurseForge. |

Los push que solo tocan Markdown, `docs/` o `screenshots/` no compilan.

## Publicar una versión

1. Añade `## [x.y.z] - fecha` a `changelog.md`.
2. Sube `mod_version` en `gradle.properties` y haz push a `main`.
3. El workflow ve que no existe la etiqueta `v<mod_version>+<minecraft_version>` (por ejemplo
   `v0.5.0+26.3`), compila y publica un release en GitHub con los tres jars. Las notas son la
   sección del changelog de esa versión.
4. Si están configurados, sube cada jar a Modrinth y CurseForge como beta. Una vez, en los ajustes
   del repositorio:
   - Secrets: `MODRINTH_TOKEN`, `CURSEFORGE_TOKEN`.
   - Variables: `MODRINTH_ID`, `CURSEFORGE_ID`.

   Un sitio sin token o sin ID se omite; el release de GitHub no depende de ellos.

Los pull requests compilan pero nunca publican.
