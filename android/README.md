# qBittorrent for Android

This directory contains everything needed to build and run qBittorrent on Android. The approach compiles `qbittorrent-nox` (the headless, WebUI-only binary) as a position-independent executable for ARM64, packages it inside an Android app as `libqbittorrent_nox.so` (so Android's package manager extracts it to the executable native library directory), and runs it as a Foreground Service. The app wraps the WebUI in a WebView, giving a native Android experience backed by the real qBittorrent engine.

---

## Architecture

```
android/
├── app/                          Android Studio module
│   └── src/main/
│       ├── kotlin/               Kotlin app layer (service, UI, settings, boot receiver)
│       ├── jniLibs/arm64-v8a/    Compiled qbittorrent-nox binary (as .so)
│       └── res/                  Resources (layouts, strings, drawables)
├── cmake/                        CMake helpers for cross-compilation
├── deps/                         Built native dependencies (gitignored)
│   ├── openssl/
│   ├── boost/
│   └── libtorrent/
├── build/                        Intermediate build artefacts (gitignored)
├── scripts/
│   ├── build-deps.sh             Builds OpenSSL, Boost, libtorrent-rasterbar
│   ├── build-qbt.sh              Cross-compiles qBittorrent-nox
│   ├── build-all.sh              Top-level orchestration
│   └── bootstrap-gradle.sh       Downloads the Gradle wrapper JAR
└── gradle/                       Gradle wrapper config
```

The root `CMakeLists.txt` is used directly — no fork of the build system required. The Android folder only adds the tooling to cross-compile it, plus two small patches to `src/app/` (see [C++ patches](#c-patches-required) below).

---

## Requirements

| Tool | Version |
|------|---------|
| Android NDK | r27c (`27.2.12479018`) |
| Android SDK | API 35 (build tools 35.0.0) |
| Qt for Android (arm64-v8a) | 6.8.x |
| Qt host tools (gcc_64) | 6.8.x (same version) — **with `qttools` module** |
| JDK | 17+ |
| CMake | 3.25+ |
| Ninja | any recent |
| Python 3 + aqtinstall | for Qt installation |

---

## C++ patches required

Before building, apply two small patches to the upstream C++ source. Both add `&& !defined(Q_OS_ANDROID)` to guards that call `::daemon()` — a POSIX function not provided by Android's Bionic libc.

**`src/app/main.cpp`** — two occurrences of the same guard:
```diff
-#if defined(DISABLE_GUI) && !defined(Q_OS_WIN)
+#if defined(DISABLE_GUI) && !defined(Q_OS_WIN) && !defined(Q_OS_ANDROID)
```

**`src/app/cmdoptions.cpp`** — three occurrences:
```diff
-#elif !defined(Q_OS_WIN)
+#elif !defined(Q_OS_WIN) && !defined(Q_OS_ANDROID)
```

The patched files are provided in `android/patches/` and are applied automatically by `build-qbt.sh` if they haven't been applied already.

No other source changes are needed. `QLocalSocket`, `isatty()`, `setrlimit()`, and all other Unix APIs used by qbt-nox are available in Bionic. `QProcess` (used only for the search-engine plugin feature) compiles normally but is a no-op at runtime, which is fine since the search plugin isn't relevant to a headless server.

---

## First-time Setup

### 1. Install the Android NDK

Via Android Studio SDK Manager or:

```sh
sdkmanager "ndk;27.2.12479018"
```

Set the environment variable:

```sh
export ANDROID_NDK_ROOT=~/Android/Sdk/ndk/27.2.12479018
```

### 2. Install Qt for Android

Install [aqtinstall](https://github.com/miurahr/aqtinstall):

```sh
pip install aqtinstall
```

Install Qt — you need **both** the host tools (with `qttools`) and the Android target:

```sh
# qttools is required for Qt6::LinguistTools (used by CMake configure)
aqt install-qt linux desktop  6.8.1 gcc_64            -O ~/Qt -m qttools
aqt install-qt linux android  6.8.1 android_arm64_v8a  -O ~/Qt
```

Set the environment variables:

```sh
export QT_HOST_ROOT=~/Qt/6.8.1/gcc_64
export QT_ANDROID_ROOT=~/Qt/6.8.1/android_arm64_v8a
```

### 3. Bootstrap the Gradle wrapper JAR

```sh
bash android/scripts/bootstrap-gradle.sh
```

---

## Building

### Full build (dependencies + qBittorrent + APK)

```sh
bash android/scripts/build-all.sh --apk
```

### Build steps individually

```sh
# 1. Build OpenSSL, Boost, libtorrent-rasterbar
bash android/scripts/build-deps.sh

# 2. Build qBittorrent-nox and place it in jniLibs
bash android/scripts/build-qbt.sh

# 3. Assemble the APK
cd android && ./gradlew :app:assembleDebug
```

The debug APK will be at:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

### Build options

| Flag | Effect |
|------|--------|
| `--deps-only` | Build only OpenSSL/Boost/libtorrent |
| `--qbt-only` | Build only qBittorrent-nox (skip deps) |
| `--apk` | Also invoke Gradle to produce the APK |
| `--release` | Build APK in release mode |

---

## Dependency versions

| Variable | Default |
|----------|---------|
| `OPENSSL_VERSION` | `3.3.2` |
| `BOOST_VERSION` | `1.87.0` |
| `LIBTORRENT_VERSION` | `2.0.10` |
| `ANDROID_API` | `26` |

### Why `c++_static`?

Both build scripts use `-DANDROID_STL=c++_static`. With `c++_shared` the runtime linker requires `libc++_shared.so` to be present alongside the binary at launch. Forgetting to place that file in `jniLibs/` produces a silent crash with no useful error message. Since `libqbittorrent_nox.so` is the only C++ consumer in this process, static linkage is strictly cleaner — one self-contained file, no extra tracking.

---

## Runtime behaviour

- The Foreground Service writes a `qBittorrent.conf` to internal storage on first launch (not overwritten on subsequent launches, so user settings are preserved).
- Downloads go to the app's external files directory (`Android/data/org.qbittorrent.android/files/Downloads`).
- The WebUI listens on `127.0.0.1:8080` — not exposed to other devices on the network by default.
- `QT_QPA_PLATFORM=offscreen` is set so Qt does not attempt to open a display.
- Magnet links and `.torrent` files open in qBittorrent via Android intent filters. Both are POSTed directly to `/api/v2/torrents/add`.
- The service has a watchdog that restarts `qbittorrent-nox` on unexpected crashes, with exponential back-off (2 s → 4 s → … → 60 s), giving up after 5 restarts.

### Default credentials

qBittorrent's default WebUI username is `admin` and the default password is `adminadmin`. Change these immediately in the WebUI Settings → Web UI after first launch.

---

## Settings screen

The app includes a Settings screen (reachable from the loading overlay button or the notification "Settings" action) with:

- **Start on boot** toggle (defaults to on)
- **Restart / Stop** service buttons
- **WebUI address** display and "Open in browser" button
- **Battery optimization** exemption request (with live status)
- **All-files storage access** request on API 30+ (with live status)
- App version info

---

## How the binary is packaged

Android forbids executing arbitrary binaries from writable app directories on API 29+. The workaround is to name the `qbittorrent-nox` binary `libqbittorrent_nox.so` and place it in `jniLibs/arm64-v8a/`. The package manager installs all `.so` files from that directory to `nativeLibraryDir`, which is a system-managed path where execution is permitted. The service then calls:

```
<nativeLibraryDir>/libqbittorrent_nox.so --profile=<filesDir>/qbt-profile
```

---

## CI

The GitHub Actions workflow at `.github/workflows/ci_android.yaml` runs on every push that touches `android/**`, `src/**`, `CMakeLists.txt`, or `cmake/**`. It:

1. Sets up JDK 17 and Android SDK
2. Installs NDK r27c via `sdkmanager`
3. Caches Qt (host + Android, including `qttools`) by version
4. Caches native dependencies by version + STL type
5. Builds dependencies on cache miss
6. Cross-compiles `qbittorrent-nox`
7. Verifies the binary has no `libc++_shared.so` dependency
8. Assembles the debug APK
9. Uploads both debug and release APKs as artefacts

---

## Signing for release

Create a keystore and set these secrets in your GitHub repository:

```
KEYSTORE_BASE64     base64-encoded .jks file
KEY_ALIAS           key alias
KEYSTORE_PASSWORD   store password
KEY_PASSWORD        key password
```

Then add a signing config to `app/build.gradle.kts`:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("KEYSTORE_PATH") ?: "")
        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        keyAlias = System.getenv("KEY_ALIAS") ?: ""
        keyPassword = System.getenv("KEY_PASSWORD") ?: ""
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
    }
}
```

---

## Minimum supported Android version

Android 8.0 (API 26 / Oreo). Required for `startForegroundService` and the `FOREGROUND_SERVICE` permission model.
