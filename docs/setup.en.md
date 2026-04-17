# Codex Mobile Setup

The primary repository experience is Chinese-first. For the main setup guide, see [setup.md](./setup.md).

Codex Mobile currently targets Android 9+ `arm64-v8a` devices and runs as a single-APK experience backed by a build-time runtime payload.

## Prerequisites

- Android Studio and Android SDK
- Android 9+ `arm64-v8a` device
- a Codex arm64 runtime directory or archive that can be packaged into the APK
- an on-device browser for OAuth or device-code verification
- optional root access if you want keepalive and whitelist hardening

## What This Repository Does Not Include

This repository contains the Android app project itself, but not:

- your app-private auth state or `auth.json`
- your local Codex session history
- your proxy setup
- local `local.properties`
- a full exported phone runtime image

## High-level Boot Flow

1. Clone the repository and open it in Android Studio.
2. Sync Gradle and install any missing Android SDK components.
3. Provide a runtime input before building, for example:

   ```bash
   export CODEX_MOBILE_RUNTIME_ARCHIVE=/absolute/path/to/@mmmbuto/codex-cli-termux/package
   ```

   You can also use the Gradle property `codexMobile.runtime.archive=/absolute/path/...`.

4. Build and install the app with Android Studio, `./gradlew assembleLegacyDebug`, or `./gradlew assembleOssDebug`.
5. On first launch, the app unpacks the embedded runtime into its private storage and initializes a private `CODEX_HOME`.
6. Use `Login` or `Device Code` from the command page, then verify backend startup, reconnect behavior, and thread loading.

## Runtime Notes

- the runtime is packaged into APK assets at build time, then unpacked into app-private storage on first launch
- auth files, config, session index, and backend logs live under the app-private `CODEX_HOME`
- the app expects the local websocket endpoint `ws://127.0.0.1:8765`
- root is not required for basic startup, but helps with Doze whitelist, background whitelist, and standby bucket hardening
- if you use device-code auth, enable device-code login in your ChatGPT security settings first

## Current Constraints

- first release supports `arm64-v8a` only
- the runtime payload must be injected at build time and is not committed to the repository
- backend behavior depends on the packaged community Codex runtime version
- real deployments still include power management, root tooling, and proxy decisions
- the app UI and repository docs are currently Chinese-first

## Quick Troubleshooting Checklist

- confirm the device ABI is `arm64-v8a`
- confirm `CODEX_MOBILE_RUNTIME_ARCHIVE` or `codexMobile.runtime.archive` was provided at build time
- confirm the embedded runtime successfully unpacked on first launch
- confirm the app can obtain root when testing keepalive
- confirm login or device-code auth completed successfully
- confirm the local app-server is listening on `8765`
- collect screenshots or logs before filing issues
