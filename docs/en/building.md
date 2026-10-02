[← Back to index](README.md)

# Building and releasing

For anyone building Radiante or publishing a version. Playing needs none of this.

The mod has two parts: the native C++ renderer (`native/`, built with CMake) and the Java mod
(Gradle, one jar per loader). The renderer is built per system: `core.dll` on Windows,
`libcore.so` on Linux.

## Common requirements

- Java 25.
- Vulkan SDK `1.4.341` or newer, with `VULKAN_SDK` set and `glslangValidator` on `PATH`.
- The NGX libraries from NVIDIA's DLSS SDK, which are not in the repository (DLSS runtimes are never
  shipped). Once, at the version the build expects:

```sh
git clone --depth 1 --branch v310.9.1 https://github.com/NVIDIA/DLSS.git /tmp/dlss
mkdir -p native/extern/DLSS/lib
cp -r /tmp/dlss/lib/Windows_x86_64 native/extern/DLSS/lib/   # or Linux_x86_64 on Linux
```

## Windows

Visual Studio 2026 (x64 toolchain).

```sh
cmake -S native -B build/native -G "Visual Studio 18 2026" -A x64 -DMCVR_ENABLE_NRD=ON -DUSE_AMD=ON
cmake --build build/native --config Release --target INSTALL -j 16
./gradlew.bat build
```

`--target INSTALL` copies `core.dll`, shaders and modules into
`common/src/main/resources/radiante-native/`. The jars land in `fabric/build/libs`,
`neoforge/build/libs` and `forge/build/libs`.

To test: `./gradlew.bat :fabric:runClient` (or `:neoforge:runClient`, `:forge:runClient`). All
three share the `run/` folder.

## Linux

cmake, ninja (or make), g++ 13 or newer, git and the Vulkan SDK.

```sh
native/build-linux.sh
./gradlew build
```

The script installs `libcore.so` into `radiante-native/linux-x64/`. If CMake cannot find Vulkan,
pass `-DVulkan_LIBRARY=/usr/lib/x86_64-linux-gnu/libvulkan.so.1`. For the result to load on
Ubuntu 22.04, build on a system with glibc 2.35 (or check with
`objdump -T libcore.so | grep -oE 'GLIBC_2\.[0-9]+' | sort -Vu | tail -1`).

## One jar for both systems

A jar built on one system carries only its renderer. For one that works on both, put `core.dll`
and `linux-x64/libcore.so` in `radiante-native/` before `gradlew build`. The libraries are packed
with xz next to `natives.sha256`; the mod unpacks and checks them on first start.

Check a jar: `unzip -l <jar> | grep -E 'core.dll|libcore.so'`.

## GitHub Actions

`.github/workflows/build.yml` does all of the above on every push to `main` or `multiloader` and on
every pull request:

| Job | What it does |
|---|---|
| `native-windows` | Builds `core.dll` on Windows with the Vulkan SDK and the DLSS SDK. |
| `native-linux` | Builds `libcore.so` on Ubuntu 22.04 with g++ 13 and checks the glibc version it needs. |
| `jars` | Brings both renderers together, builds the three jars and fails if any lacks either. |
| `release` | Only for a new version: a GitHub release. |
| `publish` | Only for a new version, when configured: Modrinth and CurseForge. |

Pushes that only touch Markdown, `docs/` or `screenshots/` do not build.

## Publishing a version

1. Add `## [x.y.z] - date` to `changelog.md`.
2. Bump `mod_version` in `gradle.properties` and push to `main`.
3. The workflow sees there is no `v<mod_version>+<minecraft_version>` tag yet (for example
   `v0.5.0+26.3`), builds, and publishes a GitHub release with the three jars. The notes are that
   version's changelog section.
4. When configured, it uploads each jar to Modrinth and CurseForge as beta. Once, in the
   repository settings:
   - Secrets: `MODRINTH_TOKEN`, `CURSEFORGE_TOKEN`.
   - Variables: `MODRINTH_ID`, `CURSEFORGE_ID`.

   A site without a token or ID is skipped; the GitHub release does not depend on either.

Pull requests build but never publish.
