#!/usr/bin/env bash
# Builds the native renderer and installs linux-x64/libcore.so + shaders into src/main/resources/radiante-native.
# Requires cmake, ninja (or make), g++ 13+, git and the Vulkan SDK (VULKAN_SDK set, glslangValidator on PATH).
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${RADIANTE_BUILD_DIR:-$SRC/build/linux-release}"

if [[ -z "${VULKAN_SDK:-}" ]]; then
    echo "VULKAN_SDK is not set (source the SDK's setup-env.sh first)" >&2
    exit 1
fi

GENERATOR=()
command -v ninja >/dev/null && GENERATOR=(-G Ninja)

cmake -S "$SRC" -B "$BUILD_DIR" "${GENERATOR[@]}" -DCMAKE_BUILD_TYPE=Release \
    -DMCVR_ENABLE_NRD=ON -DUSE_AMD=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5 "$@"
cmake --build "$BUILD_DIR" --config Release -j "$(nproc)"
cmake --install "$BUILD_DIR" --config Release
