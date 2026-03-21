#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$ANDROID_DIR")"
DEPS_DIR="${ANDROID_DIR}/deps"
BUILD_DIR="${ANDROID_DIR}/build/qbt"
OUTPUT_JNI="${ANDROID_DIR}/app/src/main/jniLibs/arm64-v8a"

ANDROID_API="${ANDROID_API:-26}"
ABI="${ABI:-arm64-v8a}"
ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK:-}}"
QT_ANDROID_ROOT="${QT_ANDROID_ROOT:-}"
QT_HOST_ROOT="${QT_HOST_ROOT:-}"

JOBS="${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}"

check_env() {
    if [[ -z "${ANDROID_NDK_ROOT}" ]]; then
        echo "error: ANDROID_NDK_ROOT is not set"
        exit 1
    fi
    if [[ -z "${QT_ANDROID_ROOT}" ]]; then
        echo "error: QT_ANDROID_ROOT is not set (path to Qt for Android, e.g. ~/Qt/6.8.0/android_arm64_v8a)"
        exit 1
    fi
    if [[ -z "${QT_HOST_ROOT}" ]]; then
        echo "error: QT_HOST_ROOT is not set (path to Qt host tools, e.g. ~/Qt/6.8.0/gcc_64)"
        exit 1
    fi
    if [[ ! -d "${DEPS_DIR}/libtorrent" ]]; then
        echo "error: dependencies not found. Run build-deps.sh first."
        exit 1
    fi
}

build_qbt() {
    echo "==> Cross-compiling qBittorrent-nox for Android ${ABI}"

    local toolchain_file="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake"

    mkdir -p "${BUILD_DIR}"

    cmake -S "${ROOT_DIR}" -B "${BUILD_DIR}" \
        -DCMAKE_TOOLCHAIN_FILE="${toolchain_file}" \
        -DANDROID_ABI="${ABI}" \
        -DANDROID_PLATFORM="android-${ANDROID_API}" \
        -DANDROID_STL="c++_shared" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_FIND_ROOT_PATH="${DEPS_DIR}/openssl;${DEPS_DIR}/boost;${DEPS_DIR}/libtorrent;${QT_ANDROID_ROOT}" \
        -DQT_HOST_PATH="${QT_HOST_ROOT}" \
        -DQt6_DIR="${QT_ANDROID_ROOT}/lib/cmake/Qt6" \
        -DOPENSSL_ROOT_DIR="${DEPS_DIR}/openssl" \
        -DOPENSSL_USE_STATIC_LIBS=TRUE \
        -DBoost_ROOT="${DEPS_DIR}/boost" \
        -DBoost_NO_SYSTEM_PATHS=ON \
        -DLibtorrentRasterbar_DIR="${DEPS_DIR}/libtorrent/lib/cmake/LibtorrentRasterbar" \
        -DGUI=OFF \
        -DWEBUI=ON \
        -DDBUS=OFF \
        -DSTACKTRACE=OFF \
        -DVERBOSE_CONFIGURE=ON \
        -DCMAKE_VERBOSE_MAKEFILE=OFF \
        -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=OFF \
        -DCMAKE_EXE_LINKER_FLAGS="-fPIE -pie" \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON

    cmake --build "${BUILD_DIR}" -j"${JOBS}" --target qbittorrent-nox

    local binary="${BUILD_DIR}/qbittorrent-nox"
    if [[ ! -f "${binary}" ]]; then
        echo "error: build succeeded but binary not found at ${binary}"
        exit 1
    fi

    mkdir -p "${OUTPUT_JNI}"
    cp "${binary}" "${OUTPUT_JNI}/libqbittorrent_nox.so"

    local llvm_strip
    llvm_strip="$(find "${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt" -name "llvm-strip" -type f | head -1)"
    if [[ -n "${llvm_strip}" ]]; then
        "${llvm_strip}" --strip-unneeded "${OUTPUT_JNI}/libqbittorrent_nox.so"
    fi

    local size_human
    size_human=$(du -sh "${OUTPUT_JNI}/libqbittorrent_nox.so" | cut -f1)
    echo ""
    echo "qBittorrent-nox built successfully."
    echo "  Output : ${OUTPUT_JNI}/libqbittorrent_nox.so (${size_human})"
}

main() {
    check_env
    build_qbt
}

main "$@"
