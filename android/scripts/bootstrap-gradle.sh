#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(dirname "$SCRIPT_DIR")"
WRAPPER_DIR="${ANDROID_DIR}/gradle/wrapper"
GRADLE_VERSION="8.10.2"
JAR_URL="https://github.com/gradle/gradle/raw/v${GRADLE_VERSION}/gradle/wrapper/gradle-wrapper.jar"
SHA256_EXPECTED="41c8aa7a337a44af18d8fc57bd16b095e0c84f2a50762413ab6ac6ed59a7c44a"

mkdir -p "${WRAPPER_DIR}"

JAR_PATH="${WRAPPER_DIR}/gradle-wrapper.jar"

if [[ -f "${JAR_PATH}" ]]; then
    echo "gradle-wrapper.jar already present"
    exit 0
fi

echo "Downloading gradle-wrapper.jar ${GRADLE_VERSION}..."
curl -fsSL -o "${JAR_PATH}" "${JAR_URL}"

ACTUAL_SHA=$(sha256sum "${JAR_PATH}" | awk '{print $1}')
if [[ "${ACTUAL_SHA}" != "${SHA256_EXPECTED}" ]]; then
    echo "error: SHA-256 mismatch for gradle-wrapper.jar"
    echo "  expected: ${SHA256_EXPECTED}"
    echo "  actual:   ${ACTUAL_SHA}"
    rm -f "${JAR_PATH}"
    exit 1
fi

echo "gradle-wrapper.jar downloaded and verified."
