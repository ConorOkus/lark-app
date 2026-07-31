#!/usr/bin/env bash
# Reference CI: builds/tests everything a hosted runner should.
# Requires JAVA_HOME + Android SDK; the iOS leg needs macOS with Xcode and xcodegen.
set -euo pipefail
cd "$(dirname "$0")/.."

# --- Rust leg -----------------------------------------------------------------
# Must run BEFORE the Gradle leg: the FFI unit-test lane loads the host library that
# build-rust.sh produces, and silently skips itself if it is missing.
#
# SKIP_RUST is a local-development convenience only. Honoring it on the required lane would let a
# green required run mean "the in-process wallet core went unverified", which is the one thing the
# escape hatch must never be able to say (plan R7).
if [ "${SKIP_RUST:-}" = "1" ]; then
    if [ "${CI:-}" = "true" ]; then
        echo "error: SKIP_RUST is not honored on the required CI lane — a required run always" >&2
        echo "       builds and tests the Rust core. Drop SKIP_RUST, or move this work to a" >&2
        echo "       non-required lane." >&2
        exit 1
    fi
    echo "Skipping Rust leg (SKIP_RUST=1) — this run is NON-VERIFYING for the in-process core."
else
    bash scripts/build-rust.sh

    # The committed UniFFI bindings must match the crate they were generated from (plan R8).
    echo "==> checking committed bindings against the crate"
    SKIP_BUILD=1 bash scripts/generate-bindings.sh >/dev/null
    if ! git diff --quiet -- composeApp/src/androidMain/kotlin/uniffi; then
        echo "error: the committed UniFFI bindings differ from the crate." >&2
        echo "       run scripts/generate-bindings.sh and commit the result." >&2
        git --no-pager diff --stat -- composeApp/src/androidMain/kotlin/uniffi >&2
        exit 1
    fi
fi

./gradlew detekt \
    :composeApp:testDebugUnitTest \
    :composeApp:assembleDebug \
    :composeApp:linkDebugFrameworkIosSimulatorArm64

if [ "$(uname)" = "Darwin" ]; then
    if [ "${SKIP_IOS:-}" = "1" ]; then
        echo "Skipping iOS app build (SKIP_IOS=1)"
    elif ! command -v xcodegen >/dev/null 2>&1; then
        echo "error: xcodegen is required on macOS ('brew install xcodegen'), or set SKIP_IOS=1." >&2
        exit 1
    else
        (
            cd iosApp
            xcodegen generate
            xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
                -destination 'generic/platform=iOS Simulator' build
        )
    fi
else
    echo "Skipping iOS app build (needs macOS)"
fi
