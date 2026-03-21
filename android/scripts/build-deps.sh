#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$ANDROID_DIR")"
DEPS_DIR="${ANDROID_DIR}/deps"
BUILD_DIR="${ANDROID_DIR}/build/deps"

OPENSSL_VERSION="${OPENSSL_VERSION:-3.3.2}"
BOOST_VERSION="${BOOST_VERSION:-1.87.0}"
BOOST_VERSION_UNDERSCORE="${BOOST_VERSION//./_}"
LIBTORRENT_VERSION="${LIBTORRENT_VERSION:-2.0.10}"

ANDROID_API="${ANDROID_API:-26}"
ABI="${ABI:-arm64-v8a}"
ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK:-}}"

JOBS="${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}"

check_ndk() {
    if [[ -z "${ANDROID_NDK_ROOT}" ]]; then
        echo "error: ANDROID_NDK_ROOT is not set"
        exit 1
    fi
    if [[ ! -d "${ANDROID_NDK_ROOT}" ]]; then
        echo "error: NDK directory not found: ${ANDROID_NDK_ROOT}"
        exit 1
    fi
    echo "NDK: ${ANDROID_NDK_ROOT}"
}

ndk_toolchain_dir() {
    local host
    case "$(uname -s)" in
        Linux)  host="linux-x86_64" ;;
        Darwin) host="darwin-x86_64" ;;
        *)      echo "error: unsupported host OS"; exit 1 ;;
    esac
    echo "${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${host}"
}

build_openssl() {
    local prefix="${DEPS_DIR}/openssl"
    local stamp="${BUILD_DIR}/openssl/.built_${OPENSSL_VERSION}"
    [[ -f "${stamp}" ]] && { echo "openssl: already built"; return; }

    echo "==> Building OpenSSL ${OPENSSL_VERSION}"
    mkdir -p "${BUILD_DIR}/openssl"
    cd "${BUILD_DIR}/openssl"

    local src_archive="openssl-${OPENSSL_VERSION}.tar.gz"
    if [[ ! -f "${src_archive}" ]]; then
        curl -fsSL -o "${src_archive}" \
            "https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz"
    fi
    tar -xzf "${src_archive}" --strip-components=1

    local toolchain
    toolchain="$(ndk_toolchain_dir)"
    local compiler="${toolchain}/bin/aarch64-linux-android${ANDROID_API}-clang"
    local ar="${toolchain}/bin/llvm-ar"
    local ranlib="${toolchain}/bin/llvm-ranlib"

    export CC="${compiler}"
    export AR="${ar}"
    export RANLIB="${ranlib}"

    ./Configure android-arm64 \
        -D__ANDROID_API__="${ANDROID_API}" \
        no-shared \
        no-tests \
        no-engine \
        "--prefix=${prefix}" \
        "--openssldir=${prefix}"

    make -j"${JOBS}"
    make install_sw

    touch "${stamp}"
    echo "openssl: done → ${prefix}"
    cd -
}

build_boost() {
    local prefix="${DEPS_DIR}/boost"
    local stamp="${BUILD_DIR}/boost/.built_${BOOST_VERSION}"
    [[ -f "${stamp}" ]] && { echo "boost: already built"; return; }

    echo "==> Building Boost ${BOOST_VERSION}"
    mkdir -p "${BUILD_DIR}/boost"
    cd "${BUILD_DIR}/boost"

    local src_archive="boost_${BOOST_VERSION_UNDERSCORE}.tar.gz"
    if [[ ! -f "${src_archive}" ]]; then
        curl -fsSL -o "${src_archive}" \
            "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}.tar.gz"
    fi
    tar -xzf "${src_archive}" --strip-components=1

    local toolchain
    toolchain="$(ndk_toolchain_dir)"
    local clang="${toolchain}/bin/aarch64-linux-android${ANDROID_API}-clang"
    local clangxx="${toolchain}/bin/aarch64-linux-android${ANDROID_API}-clang++"

    ./bootstrap.sh --with-libraries=system,filesystem,date_time

    cat > user-config.jam <<EOF
using clang : android :
    "${clangxx}"
    : <compileflags>"-target aarch64-linux-android${ANDROID_API} -fPIC"
      <linkflags>"-target aarch64-linux-android${ANDROID_API}"
    ;
EOF

    ./b2 \
        toolset=clang-android \
        target-os=android \
        architecture=arm \
        address-model=64 \
        variant=release \
        link=static \
        threading=multi \
        runtime-link=static \
        -j"${JOBS}" \
        --user-config=user-config.jam \
        "--prefix=${prefix}" \
        install

    mkdir -p "${prefix}/include"
    cp -r boost "${prefix}/include/" 2>/dev/null || true

    touch "${stamp}"
    echo "boost: done → ${prefix}"
    cd -
}

build_libtorrent() {
    local prefix="${DEPS_DIR}/libtorrent"
    local stamp="${BUILD_DIR}/libtorrent/.built_${LIBTORRENT_VERSION}"
    [[ -f "${stamp}" ]] && { echo "libtorrent: already built"; return; }

    echo "==> Building libtorrent-rasterbar ${LIBTORRENT_VERSION}"
    mkdir -p "${BUILD_DIR}/libtorrent"
    cd "${BUILD_DIR}/libtorrent"

    local src_archive="libtorrent-rasterbar-${LIBTORRENT_VERSION}.tar.gz"
    if [[ ! -f "${src_archive}" ]]; then
        curl -fsSL -o "${src_archive}" \
            "https://github.com/arvidn/libtorrent/releases/download/v${LIBTORRENT_VERSION}/libtorrent-rasterbar-${LIBTORRENT_VERSION}.tar.gz"
    fi
    tar -xzf "${src_archive}" --strip-components=1

    local toolchain_file="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake"

    cmake -S . -B build \
        -DCMAKE_TOOLCHAIN_FILE="${toolchain_file}" \
        -DANDROID_ABI="${ABI}" \
        -DANDROID_PLATFORM="android-${ANDROID_API}" \
        -DANDROID_STL="c++_shared" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="${prefix}" \
        -DBUILD_SHARED_LIBS=OFF \
        -Dstatic_runtime=ON \
        -Dencryption=ON \
        -Ddeprecated-functions=OFF \
        -DBoost_ROOT="${DEPS_DIR}/boost" \
        -DBoost_NO_SYSTEM_PATHS=ON \
        -DOPENSSL_ROOT_DIR="${DEPS_DIR}/openssl" \
        -DOPENSSL_USE_STATIC_LIBS=TRUE \
        -Dpython-bindings=OFF \
        -Dexamples=OFF \
        -Dtests=OFF

    cmake --build build -j"${JOBS}"
    cmake --install build

    touch "${stamp}"
    echo "libtorrent: done → ${prefix}"
    cd -
}

main() {
    check_ndk
    mkdir -p "${DEPS_DIR}" "${BUILD_DIR}"

    build_openssl
    build_boost
    build_libtorrent

    echo ""
    echo "All dependencies built successfully."
    echo "  OpenSSL   : ${DEPS_DIR}/openssl"
    echo "  Boost     : ${DEPS_DIR}/boost"
    echo "  libtorrent: ${DEPS_DIR}/libtorrent"
}

main "$@"
