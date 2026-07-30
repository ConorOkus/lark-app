#!/usr/bin/env bash
# Build lark-ffi as an XCFramework for iOS (device + simulator) and generate the
# Swift bindings (U3). Runs locally / on a manual/nightly lane — not on the
# per-PR gate (KTD-9). Requires Xcode and the iOS Rust targets:
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FFI_DIR="$REPO_ROOT/rust/lark-ffi"
BUILD="$FFI_DIR/target"
OUT="$REPO_ROOT/iosApp/Frameworks"
LIB=liblark_ffi.a

cd "$FFI_DIR"

echo "==> building iOS device + simulator static libs"
cargo build --release --target aarch64-apple-ios
cargo build --release --target aarch64-apple-ios-sim
cargo build --release --target x86_64-apple-ios

echo "==> lipo simulator archs (arm64 + x86_64) into one fat lib"
SIM_FAT="$BUILD/sim-universal"
mkdir -p "$SIM_FAT"
lipo -create \
  "$BUILD/aarch64-apple-ios-sim/release/$LIB" \
  "$BUILD/x86_64-apple-ios/release/$LIB" \
  -output "$SIM_FAT/$LIB"

echo "==> generating Swift bindings + headers"
HDR="$BUILD/swift-headers"
rm -rf "$HDR"; mkdir -p "$HDR"
cargo run --quiet --bin uniffi-bindgen -- generate \
  --library "$BUILD/aarch64-apple-ios/release/$LIB" \
  --language swift --out-dir "$HDR"
# UniFFI emits <name>FFI.modulemap; XCFramework expects module.modulemap.
cp "$HDR"/*FFI.modulemap "$HDR/module.modulemap"

echo "==> assembling XCFramework"
rm -rf "$OUT/lark_ffiFFI.xcframework"
mkdir -p "$OUT"
xcodebuild -create-xcframework \
  -library "$BUILD/aarch64-apple-ios/release/$LIB" -headers "$HDR" \
  -library "$SIM_FAT/$LIB" -headers "$HDR" \
  -output "$OUT/lark_ffiFFI.xcframework"

echo "==> XCFramework at $OUT/lark_ffiFFI.xcframework"
echo "    Swift glue: $HDR/lark_ffi.swift (copy into iosApp sources)"
