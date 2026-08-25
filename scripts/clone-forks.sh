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

  # Refuse to move someone's in-progress fork work out from under them — but only when a move is
  # actually needed, and only for TRACKED modifications. Untracked files survive a checkout, and a
  # checkout already sitting on the pin has nothing to move; treating either as a blocker would
  # break a working developer setup for no reason.
  if [ -d "$dir/.git" ]; then
    local head_now
    head_now="$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)"
    if [ "$head_now" != "$sha" ] &&
       [ -n "$(git -C "$dir" status --porcelain --untracked-files=no)" ]; then
      echo "ERROR: $dir has uncommitted tracked changes and is not on the pinned commit;" >&2
      echo "       refusing to move it. Commit/stash them, or check out $sha there yourself." >&2
      exit 1
    fi
  fi

  if [ ! -d "$dir/.git" ]; then
    echo "==> cloning $name from $repo ($branch)"
    git clone --quiet --branch "$branch" "$repo" "$dir"
  elif git -C "$dir" cat-file -e "${sha}^{commit}" 2>/dev/null; then
    # Already have the pinned commit; nothing to fetch. This also covers a developer checkout
    # whose `origin` is a different fork remote than the pin names.
    echo "==> $name already has the pinned commit"

    # ...but "I have it" is not "the remote has it", and this is the one branch where those can
    # differ. CI clones fresh, so a pin naming a commit that only ever existed on this disk passes
    # every local check and fails the runner with an unreadable tree.
    #
    # That is not hypothetical: this script ends by DETACHING HEAD at the pin, so a commit made
    # afterwards sits on a detached HEAD while the branch stays put — and `git push <remote>
    # <branch>` then pushes the unmoved branch, reports success, and sends nothing.
    #
    # Non-fatal, because working offline is legitimate and this is the only step that needs the
    # network when the checkout is already correct.
    if remote_head="$(git ls-remote "$repo" "$branch" 2>/dev/null | cut -f1)" && [ -n "$remote_head" ]; then
      if ! git -C "$dir" cat-file -e "${remote_head}^{commit}" 2>/dev/null ||
         ! git -C "$dir" merge-base --is-ancestor "$sha" "$remote_head" 2>/dev/null; then
        echo "ERROR: $repo $branch is at ${remote_head:0:12}, which does not contain the pinned" >&2
        echo "       $sha." >&2
        echo "       The pin names a commit the remote does not have. CI clones fresh and will" >&2
        echo "       fail. Push the fork branch before bumping the pin — and check you are not on" >&2
        echo "       a detached HEAD, which makes 'git push <remote> <branch>' a silent no-op." >&2
        exit 1
      fi
    else
      echo "==> (could not reach $repo to confirm the pin is pushed; skipping that check)"
    fi
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
