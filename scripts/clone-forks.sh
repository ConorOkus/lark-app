#!/usr/bin/env bash
# Clone the sibling fork checkouts lark-ffi path-depends on, at the rust/fork-pins.toml SHAs.
#
# Layout (see rust/fork-pins.toml): both repos are siblings of this checkout, because
# rust/lark-ffi resolves ../../../bark/bark and bark resolves ../rust-lightning.
#
# Both remotes are public and clone unauthenticated — no credential or CI secret is needed.
# Idempotent: an existing checkout is fetched and moved onto the pinned SHA rather than re-cloned,
# so a warm CI cache or a developer machine both work.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PARENT="$(cd "$REPO_ROOT/.." && pwd)"
PINS="$REPO_ROOT/rust/fork-pins.toml"

# Reads one key out of a [section] in fork-pins.toml. Same parsing shape as build-rust.sh, which
# verifies these pins after the clone.
pin() {
  local section="$1" key="$2"
  awk -v s="[$section]" -v k="$key" '
    $0==s {f=1; next}
    /^\[/ {f=0}
    f && $1==k {gsub(/^[^=]*=[ \t]*/,""); gsub(/"/,""); print; exit}
  ' "$PINS"
}

clone_fork() {
  local name="$1" dir="$PARENT/$1"
  local repo branch sha
  repo="$(pin "$name" repo)"
  branch="$(pin "$name" branch)"
  sha="$(pin "$name" sha)"
  if [ -z "$repo" ] || [ -z "$sha" ]; then
    echo "ERROR: rust/fork-pins.toml has no repo/sha for [$name]" >&2
    exit 1
  fi

  if [ ! -d "$dir/.git" ]; then
    echo "==> cloning $name from $repo ($branch)"
    git clone --quiet --branch "$branch" "$repo" "$dir"
  elif git -C "$dir" cat-file -e "${sha}^{commit}" 2>/dev/null; then
    # Already have the pinned commit; nothing to fetch. This also covers a developer checkout
    # whose `origin` is a different fork remote than the pin names.
    echo "==> $name already has the pinned commit"
  else
    # Fetch from the pinned URL explicitly, never from whatever `origin` happens to be: a local
    # checkout may point at a different fork remote that does not carry this branch.
    echo "==> $name present but missing the pin; fetching $branch from $repo"
    git -C "$dir" fetch --quiet "$repo" "$branch"
  fi

  echo "==> $name -> $sha"
  git -C "$dir" checkout --quiet --detach "$sha"
}

clone_fork bark
clone_fork rust-lightning

echo "==> forks ready under $PARENT"
