# lark-app

Cross-platform client for **lark** — an Ark wallet (mutinynet for now).

## Architecture

- **Shared logic:** Kotlin Multiplatform (`composeApp/src/commonMain`) — state machines and business logic live here, Bitkey-style.
- **UI:** Compose Multiplatform — one UI codebase rendering natively on Android and iOS.
- **Core (planned):** a `lark-ffi` Rust crate (UniFFI) over the bark/LDK forks. Until then the app will run as a thin client against the hosted barkd REST gateway, behind a `LarkCore` interface so the in-process core can be swapped in later without touching UI or state machines.

Milestones and locked decisions: `PLANS/LARK_CLIENT_KMP_CMP.md` in the team workspace.

## Prerequisites

- JDK 17+ (this repo is built with 21) — set `JAVA_HOME` if it is not on your path
- Android SDK (compileSdk 35) — `ANDROID_HOME` or a `local.properties` with `sdk.dir`
- Xcode 16+ (iOS)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) — the Xcode project is generated, not committed

## Building

### Android

```sh
./gradlew :composeApp:assembleDebug
# APK at composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### iOS

```sh
cd iosApp
xcodegen generate
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'generic/platform=iOS Simulator' build
```

Or open the generated `iosApp.xcodeproj` in Xcode and run. The Kotlin framework is compiled by a pre-build phase (`embedAndSignAppleFrameworkForXcode`).

### Tests and lint

```sh
./gradlew :composeApp:testDebugUnitTest   # shared tests on JVM
./gradlew detekt                          # static analysis
```

`scripts/ci.sh` runs the full check set (both platforms) and is the reference for CI once the repo has hosted runners.
