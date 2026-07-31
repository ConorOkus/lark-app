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

    # We just built the library, so the FFI lane has no excuse to skip: make an unloadable library
    # a test failure instead of a silent skip. Without this a runner where the build succeeds but
    # JNA cannot load the result would report green with the whole FFI lane skipped (plan R7).
    export LARK_REQUIRE_FFI=1

    # The committed UniFFI bindings must match the crate they were generated from (plan R8).
    echo "==> checking committed bindings against the crate"
    SKIP_BUILD=1 bash scripts/generate-bindings.sh >/dev/null
    # Against HEAD, not the index: `git diff -- <path>` alone compares the index to the working
    # tree, so drift that was already `git add`ed would slip through.
    if ! git diff --quiet HEAD -- composeApp/src/androidMain/kotlin/uniffi; then
        echo "error: the committed UniFFI bindings differ from the crate." >&2
        echo "       run scripts/generate-bindings.sh and commit the result." >&2
        git --no-pager diff --stat HEAD -- composeApp/src/androidMain/kotlin/uniffi >&2
        exit 1
    fi
fi

./gradlew detekt \
    :composeApp:testDebugUnitTest \
    :composeApp:assembleDebug \
    :composeApp:linkDebugFrameworkIosSimulatorArm64

# Assert the FFI lane actually verified the Rust core, rather than trusting that it did.
#
# The lane skips itself when the host library will not load (plan R6), and Gradle can serve a
# CACHED result from a run where it skipped — so "the Rust leg built" and "gradlew exited 0"
# together still do not prove the in-process wallet was exercised. This checks the outcome
# directly, which is the only claim the required lane actually needs (plan R7).
if [ "${LARK_REQUIRE_FFI:-}" = "1" ]; then
    ffi_xml="composeApp/build/test-results/testDebugUnitTest/TEST-xyz.lark.app.core.ffi.FfiHostLibraryTest.xml"
    if [ ! -f "$ffi_xml" ]; then
        echo "error: the FFI lane produced no results at $ffi_xml — it did not run." >&2
        exit 1
    fi
    ffi_attrs="$(tr '>' '\n' <"$ffi_xml" | grep -m1 '<testsuite ')"
    ffi_tests="$(printf '%s' "$ffi_attrs" | sed -n 's/.*[^-]tests="\([0-9]*\)".*/\1/p')"
    ffi_skipped="$(printf '%s' "$ffi_attrs" | sed -n 's/.*skipped="\([0-9]*\)".*/\1/p')"
    if [ "${ffi_tests:-0}" -eq 0 ] || [ "${ffi_skipped:-1}" -ne 0 ]; then
        echo "error: the FFI lane did not verify the Rust core" >&2
        echo "       (tests=${ffi_tests:-?}, skipped=${ffi_skipped:-?}); a green run here would be" >&2
        echo "       meaningless. Check that the host library loads and re-run without the build" >&2
        echo "       cache (--rerun-tasks) if a cached skip was served." >&2
        exit 1
    fi
    echo "==> FFI lane verified the Rust core ($ffi_tests tests, 0 skipped)"
fi

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
