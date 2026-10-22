# ADR-0001: Android packaging without Tauri 2

* Status: Accepted
* Date: 2026-10-21

## Context

The project brief prefers Tauri 2 as the Android packaging shell for a
Rust-first application, with a Kotlin plugin for platform specific work.

The environment provides Rust 1.93.1 with the four Android targets, the
Android SDK (platform 36), NDK 27.1, JDK 21 and Node 24, so a Tauri 2 project
*would* have been possible.

## Decision

Zarc is packaged as a **native Android application**: a Gradle project with a
Kotlin front end, a thin JNI bridge (`crates/zarc-jni`) and the Rust core
(`crates/zarc-core`) compiled into `libzarc_jni.so` for the four Android ABIs.

Tauri 2 is not used.

## Rationale

1. **The security boundary stays small.** With a plain native app the only
   boundary between the Kotlin platform adapter and the Rust core is a JNI
   call that carries file paths, object identifiers and the vault master key.
   With Tauri 2 the same boundary would additionally be reachable from
   JavaScript running in a WebView. The brief explicitly warns against
   sending media through "JavaScript, WebView state, Base64 strings, or JSON
   commands"; a native app makes that structurally impossible rather than a
   matter of discipline.

2. **Fewer moving parts in the build.** `tauri android build` pins its own
   NDK and Gradle versions, drives `cargo ndk` over the ABIs and requires a
   Node toolchain plus a front end asset pipeline. Zarc needs none of that:
   `tools/build-android.ps1` drives `cargo build` per ABI with the NDK
   compiler and `gradlew assembleDebug` produces the APK. The build is
   reproducible with the toolchain that is already installed.

3. **The gallery is out of scope for this phase.** The brief explicitly
   excludes the One UI inspired gallery, animations and the media pipeline.
   A WebView shell would add a runtime, an asset pipeline and an IPC surface
   that have no consumer yet.

4. **The Android platform adapter stays honest.** The Keystore integration is
   a small Kotlin class with an auditable API. It is exercised by the
   instrumented tests and by the Security Lab screen, with no framework in
   between.

## Consequences

* The application has no JavaScript runtime and no network permission.
* A future gallery UI would be written in Kotlin (or with Jetpack Compose) and
  would call the same `VaultRepository` API that the Security Lab screen uses.
* If a WebView based UI is wanted later, it can be added as a separate
  activity that talks to the repository; the crypto core does not change.
* The cost of this decision is that the "one codebase for desktop and mobile"
  benefit of Tauri is not available. That benefit is not needed for this
  phase.
