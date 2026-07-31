#!/usr/bin/env bash
# Build the lark-ffi crate and (optionally) generate/emit the mobile artifacts.
#
# KTD-9: the host build + JVM contract tests run on every PR. Android (cargo-ndk)
# and iOS (xcframework) packaging run locally / on a manual/nightly lane.
#
# Fork provenance is pinned in rust/fork-pins.toml (KTD-3). This script verifies
# the sibling checkouts match those pins before building, so "works on my
# machine" fork drift fails loudly instead of silently.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FFI_DIR="$REPO_ROOT/rust/lark-ffi"
PINS="$REPO_ROOT/rust/fork-pins.toml"

# --- verify the sibling forks match fork-pins.toml --------------------------
verify_fork() {
  local name="$1" dir="$2"
  if [ ! -d "$dir/.git" ]; then
    echo "ERROR: fork '$name' not checked out at $dir (see rust/fork-pins.toml layout note)" >&2
    exit 1
  fi
  local want have
  want="$(awk -v s="[$name]" '$0==s{f=1;next} /^\[/{f=0} f&&/^sha/{gsub(/[ "=]/,"");sub(/^sha/,"");print;exit}' "$PINS")"
  have="$(git -C "$dir" rev-parse HEAD)"
  if [ "${have#"$want"}" = "$have" ] && [ "${want#"$have"}" = "$want" ]; then
    echo "ERROR: fork '$name' at $have does not match pinned $want (rust/fork-pins.toml)" >&2
    echo "       check out the pinned SHA, or update fork-pins.toml deliberately." >&2
    exit 1
  fi
}

PARENT="$(cd "$REPO_ROOT/.." && pwd)"
verify_fork bark "$PARENT/bark"
verify_fork rust-lightning "$PARENT/rust-lightning"

# --- verify build prerequisites --------------------------------------------
# bark-server-rpc's build script compiles the captaind protos with prost-build, which shells out
# to protoc. Without it cargo fails deep inside a build script with a panic that reads like a
# crate bug, so check up front and say what to install.
if [ -z "${PROTOC:-}" ] && ! command -v protoc >/dev/null 2>&1; then
  echo "ERROR: protoc not found, but bark-server-rpc needs it to compile the captaind protos." >&2
  echo "       macOS: 'brew install protobuf'; Debian/Ubuntu: 'apt-get install -y protobuf-compiler'." >&2
  echo "       Or point PROTOC at an existing binary." >&2
  exit 1
fi

# --- host build + tests (the every-PR gate) ---------------------------------
echo "==> cargo build (host) + test"
( cd "$FFI_DIR" && cargo build && cargo test )

# --- optional: Android via cargo-ndk (SKIP unless explicitly requested) -----
if [ "${BUILD_ANDROID:-0}" = "1" ]; then
  echo "==> cargo-ndk (android)"
  ( cd "$FFI_DIR" && cargo ndk -t arm64-v8a -t x86_64 -o "$REPO_ROOT/composeApp/src/androidMain/jniLibs" build --release )
fi

# --- optional: iOS xcframework (SKIP unless explicitly requested) -----------
if [ "${BUILD_IOS:-0}" = "1" ]; then
  echo "==> xcframework (ios)"
  "$REPO_ROOT/scripts/build-xcframework.sh"
fi

echo "==> lark-ffi build OK"
