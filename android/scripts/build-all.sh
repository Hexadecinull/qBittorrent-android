#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
    cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --deps-only     Build only native dependencies (OpenSSL, Boost, libtorrent)
  --qbt-only      Build only qBittorrent-nox (dependencies must already exist)
  --apk           After building native code, also build the Android APK
  --release       Build APK in release mode (requires signing config)
  -h, --help      Show this help message

Required environment variables:
  ANDROID_NDK_ROOT      Path to Android NDK (e.g. ~/Android/Sdk/ndk/27.2.12479018)
  QT_ANDROID_ROOT       Path to Qt for Android arm64 (e.g. ~/Qt/6.8.0/android_arm64_v8a)
  QT_HOST_ROOT          Path to Qt host tools    (e.g. ~/Qt/6.8.0/gcc_64)

Optional:
  OPENSSL_VERSION       Default: 3.3.2
  BOOST_VERSION         Default: 1.87.0
  LIBTORRENT_VERSION    Default: 2.0.10
  ANDROID_API           Default: 26
  JOBS                  Parallel jobs (default: nproc)
EOF
}

DEPS_ONLY=false
QBT_ONLY=false
BUILD_APK=false
RELEASE=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --deps-only)  DEPS_ONLY=true ;;
        --qbt-only)   QBT_ONLY=true ;;
        --apk)        BUILD_APK=true ;;
        --release)    RELEASE=true ;;
        -h|--help)    usage; exit 0 ;;
        *) echo "Unknown option: $1"; usage; exit 1 ;;
    esac
    shift
done

if ! "${QBT_ONLY}"; then
    echo "### Step 1/2: Building native dependencies"
    "${SCRIPT_DIR}/build-deps.sh"
    echo ""
fi

if ! "${DEPS_ONLY}"; then
    echo "### Step 2/2: Building qBittorrent-nox"
    "${SCRIPT_DIR}/build-qbt.sh"
    echo ""
fi

if "${BUILD_APK}"; then
    ANDROID_DIR="$(dirname "$SCRIPT_DIR")"
    cd "${ANDROID_DIR}"

    GRADLE_CMD="./gradlew"
    if ! [[ -f "${GRADLE_CMD}" ]]; then
        GRADLE_CMD="gradle"
    fi

    if "${RELEASE}"; then
        echo "### Building release APK"
        ${GRADLE_CMD} :app:assembleRelease
        echo "APK: ${ANDROID_DIR}/app/build/outputs/apk/release/app-release.apk"
    else
        echo "### Building debug APK"
        ${GRADLE_CMD} :app:assembleDebug
        echo "APK: ${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug.apk"
    fi
fi

echo ""
echo "Build complete."
