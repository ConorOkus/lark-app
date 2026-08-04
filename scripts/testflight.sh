#!/usr/bin/env bash
# Archive the iOS app and upload it to TestFlight.
#
# Run from a machine with Xcode, the Apple Developer team's signing set up, and the Rust
# XCFramework already built (scripts/build-xcframework.sh) — the app links it, so a missing
# XCFramework fails the archive rather than shipping a broken binary.
#
# Two steps, either usable alone:
#   scripts/testflight.sh archive          # → build/lark.xcarchive + build/export/lark.ipa
#   scripts/testflight.sh upload           # → App Store Connect (needs API-key env vars)
#   scripts/testflight.sh                  # both
#
# Uploading needs an App Store Connect API key, passed as environment variables so no secret
# lands in the repo or in shell history:
#   ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH (path to the .p8)
# Create one at App Store Connect → Users and Access → Integrations → App Store Connect API.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IOS_DIR="$REPO_ROOT/iosApp"
BUILD_DIR="$REPO_ROOT/build/testflight"
ARCHIVE="$BUILD_DIR/lark.xcarchive"
EXPORT_DIR="$BUILD_DIR/export"
XCFRAMEWORK="$IOS_DIR/Frameworks/lark_ffiFFI.xcframework"

step="${1:-all}"

require_xcframework() {
  if [ ! -d "$XCFRAMEWORK" ]; then
    echo "error: $XCFRAMEWORK is missing — run scripts/build-xcframework.sh first." >&2
    exit 1
  fi
  # The device slice specifically: a simulator-only XCFramework links fine for the simulator and
  # then fails the archive deep into the build, which is a slow way to learn this.
  if [ ! -d "$XCFRAMEWORK/ios-arm64" ]; then
    echo "error: $XCFRAMEWORK has no ios-arm64 (device) slice — rebuild it." >&2
    exit 1
  fi
}

do_archive() {
  require_xcframework
  if [ "${JAVA_HOME:-}" = "" ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21+ 2>/dev/null || echo /opt/homebrew/opt/openjdk@21)"
    export JAVA_HOME
  fi

  echo "==> xcodegen"
  ( cd "$IOS_DIR" && xcodegen generate )

  echo "==> archiving (Release, generic/platform=iOS)"
  rm -rf "$ARCHIVE"
  mkdir -p "$BUILD_DIR"
  ( cd "$IOS_DIR" && xcodebuild \
      -project iosApp.xcodeproj \
      -scheme iosApp \
      -configuration Release \
      -destination 'generic/platform=iOS' \
      -archivePath "$ARCHIVE" \
      archive )

  echo "==> exporting an App Store ipa"
  rm -rf "$EXPORT_DIR"
  # Written per run rather than committed: it holds the team id, which lives in project.yml as the
  # single source of truth.
  local team
  team="$(awk '/DEVELOPMENT_TEAM:/ {print $2; exit}' "$IOS_DIR/project.yml")"
  local options="$BUILD_DIR/ExportOptions.plist"
  cat > "$options" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key>
  <string>app-store-connect</string>
  <key>teamID</key>
  <string>$team</string>
  <key>uploadSymbols</key>
  <true/>
  <!-- Uploading is a separate, explicit step below, so a mistake stops at a local artifact. -->
  <key>destination</key>
  <string>export</string>
</dict>
</plist>
PLIST
  xcodebuild -exportArchive \
    -archivePath "$ARCHIVE" \
    -exportPath "$EXPORT_DIR" \
    -exportOptionsPlist "$options"

  echo "==> ipa at $EXPORT_DIR"
  ls -lh "$EXPORT_DIR"/*.ipa
}

do_upload() {
  local ipa
  ipa="$(ls "$EXPORT_DIR"/*.ipa 2>/dev/null | head -1 || true)"
  if [ -z "$ipa" ]; then
    echo "error: no ipa in $EXPORT_DIR — run '$0 archive' first." >&2
    exit 1
  fi
  for var in ASC_KEY_ID ASC_ISSUER_ID ASC_KEY_PATH; do
    if [ "${!var:-}" = "" ]; then
      echo "error: $var is unset. See the header of this script." >&2
      exit 1
    fi
  done

  # Validate first: it catches the whole class of rejections (missing icon, duplicate build
  # number, bad entitlements) without consuming a build number.
  echo "==> validating $ipa"
  xcrun altool --validate-app -f "$ipa" -t ios \
    --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"

  echo "==> uploading $ipa"
  xcrun altool --upload-app -f "$ipa" -t ios \
    --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"

  echo "==> uploaded. Processing takes a few minutes before the build appears in TestFlight."
}

case "$step" in
  archive) do_archive ;;
  upload) do_upload ;;
  all) do_archive && do_upload ;;
  *) echo "usage: $0 [archive|upload]" >&2; exit 2 ;;
esac
