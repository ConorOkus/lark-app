#!/usr/bin/env bash
# Reference CI: builds/tests everything a hosted runner should.
# Requires JAVA_HOME + Android SDK; the iOS leg needs macOS with Xcode and xcodegen.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew detekt \
    :composeApp:testDebugUnitTest \
    :composeApp:assembleDebug \
    :composeApp:linkDebugFrameworkIosSimulatorArm64

if [ "$(uname)" = "Darwin" ] && command -v xcodegen >/dev/null 2>&1; then
    (
        cd iosApp
        xcodegen generate
        xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
            -destination 'generic/platform=iOS Simulator' build
    )
else
    echo "Skipping iOS app build (needs macOS + xcodegen)"
fi
