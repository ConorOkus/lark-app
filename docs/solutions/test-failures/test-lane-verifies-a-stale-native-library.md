---
title: A test lane that runs every assertion can still be verifying the previous build
date: 2026-08-18
category: test-failures
module: lark-ffi
problem_type: test_failure
component: testing_framework
symptoms:
  - Local FFI lane reports 8 tests, 0 skipped, 0 failures while CI fails 6 of the same 8
  - "The failing tests all error at wallet creation with LarkException$Wallet"
  - Re-running with --rerun-tasks still passes locally
  - A crate change is committed and pushed having never been executed by any test
root_cause: config_error
resolution_type: documentation_update
severity: high
related_components:
  - tooling
  - development_workflow
  - testing_framework
tags:
  - ci
  - gradle
  - jna
  - rust-ffi
  - verification-fidelity
  - false-pass
  - stale-artifact
  - build-profile
---

# A test lane that runs every assertion can still be verifying the previous build

## Problem

The FFI test lane loads the Rust core through JNA at a path Gradle cannot see, so it exercises whatever `liblark_ffi.dylib` happens to be on disk. A change to the crate was written, tested locally to a full green — 8 tests, 0 skipped — committed, and pushed, having never been executed by anything. The lane had been loading a library built seven and a half hours earlier.

This is the sibling of [a lane that skips itself](silently-skipped-test-lane-passes-ci.md), and it defeats that doc's fix. All three of its layers reported success here: the tests ran, the count was positive, the skip count was zero. Those layers answer "did this lane run?" — which was yes. The unasked question was "did it run against the code in the working tree?"

## Symptoms

- `./gradlew detekt :composeApp:testDebugUnitTest` green locally; the same commit fails 6 of 8 FFI tests on CI.
- Every CI failure is at wallet creation, surfacing as `uniffi.lark_ffi.LarkException$Wallet` — a crate-level error the local run never saw.
- Re-running locally with `--rerun-tasks` still passes. So does re-running with `LARK_REQUIRE_FFI=1`.
- The results XML shows exactly what a real pass shows: `tests="8" skipped="0" failures="0"`.
- The only visible discrepancy is a timestamp: the crate source is newer than the library the lane loads.

## What Didn't Work

**Trusting `--rerun-tasks`.** This is the obvious lever when a Gradle result looks stale, and it is the wrong one. It forces the *test task* to re-execute; it does not invoke the Rust compiler, which Gradle does not know about. The lane genuinely re-ran, genuinely passed, and genuinely proved nothing. Reaching for it and being reassured is the trap.

**Believing "I rebuilt the crate."** This felt true, and was — `cargo build --release` had been run twice, for the drill binary and for the iOS XCFramework. But the lane's path is pinned to the **debug** profile:

```kotlin
// composeApp/build.gradle.kts:121-122
val larkFfiHostLibDir: String =
    rootProject.layout.projectDirectory.dir("rust/lark-ffi/target/debug").asFile.absolutePath
```

A release build leaves Cargo's `target/debug/` output untouched (untracked — `rust/lark-ffi/.gitignore` ignores `/target`). So the developer's honest recollection of having rebuilt is compatible with the lane loading a library from before the change — and the two profiles give no sign of each other.

**The `LARK_REQUIRE_FFI=1` gate.** Setting it makes an *unloadable* library a failure rather than a skip. A stale library loads perfectly well.

## Solution

There is no mechanism that closes this. The honest fix is procedural, and it is recorded where the next person will hit it (`AGENTS.md:36`):

> **A green FFI lane does not mean it tested your Rust change.** [...] Run `bash scripts/build-rust.sh` first, as `scripts/ci.sh` does, or trust nothing the lane tells you.

`scripts/ci.sh` is immune because it builds before it tests — the ordering that makes CI trustworthy is the same ordering a local run has to reproduce by hand.

Reproducing the failure once the library was current took 90 seconds, and the underlying break was straightforward: wallet creation had grown a full esplora scan, which needs endpoints the hermetic stub did not serve. The stub gained them (`StubEsplora.kt:75-88`), and the test asserting a sync *needs* an unstubbed endpoint was rewritten, since the lane can no longer create a wallet without one.

**A candidate mechanical fix, untested.** Making the test task *depend on* a Gradle task that shells out to `cargo build` would rebuild the library before the lane loads it. This is not the same as declaring the library an input — [the sibling doc](silently-skipped-test-lane-passes-ci.md) measured that input declaration and found it did not invalidate the task. A dependency that actually runs the compiler is a different mechanism and might work. It has not been tried, and per that doc's own rule it must be measured rather than reasoned about before anyone relies on it.

## Why This Works

Only in the sense that a documented trap beats an undocumented one. The structural problem is unchanged: the library is a build artifact reached through a runtime path, so it is outside the build system's model of the world. Gradle cannot rebuild what it cannot see, and cannot invalidate on what it cannot see either.

What the sibling doc got right was that an exit code is a proxy for verification. What this case adds is that **the test count is also a proxy.** "This lane ran and asserted things" is a strictly weaker claim than "this lane exercised the code you are about to commit," and the gap between them is exactly the size of a stale artifact.

CI caught it only because CI has no stale state to inherit: it clones, builds, then tests. The local loop reverses that ordering by default, and the failure is invisible precisely where iteration is fastest.

## Prevention

- **When a test loads an artifact the build system did not produce, rebuild it explicitly before believing the result.** For this repo that is `bash scripts/build-rust.sh`, which is also what `scripts/ci.sh` runs first.
- **A passing test count is not evidence the test ran against your change.** Distinguish "the lane executed" from "the lane executed the current tree." The first is cheap to assert and was already asserted here; the second is the one that matters.
- **`--rerun-tasks` re-runs tasks, not compilers.** It cannot refresh anything outside the build graph, and its reassurance is misleading in proportion to how convincing it looks.
- **Watch for profile-split staleness.** A path pinned to the debug profile is untouched by `cargo build --release`. Any project that builds one profile for shipping and loads another for testing can have a developer correctly believe they rebuilt while the tested artifact is old.
- **When a lane's inputs are invisible to the build system, compare timestamps before trusting a green.** `ls -la <source> <artifact>` is the whole diagnostic; here it was the only signal that separated a real pass from a meaningless one.
- **Treat a local/CI disagreement as information about the harness, not just the code.** The reflex is to debug the failing tests. The question worth asking first is what CI does that the local run does not — which here was a single build step.

## Related Issues

- [A test lane that skips itself can make a required CI lane green without verifying anything](silently-skipped-test-lane-passes-ci.md) — the same lane, the same invisible-to-Gradle root fact, an adjacent failure mode. Its three layers are all still correct and all still insufficient against this one. These two are candidates for consolidation into one doc about the lane's verification fidelity.
- PR #45 — the unilateral exit branch this surfaced on. The crate change that went untested was the restore-path full scan; the lane repair landed in the same PR.
- `AGENTS.md` — carries the standing warning, beside the analogous note that a green Kotlin suite does not mean `iosMain` compiles.
