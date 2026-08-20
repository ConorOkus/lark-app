---
title: A new server-dependent export lands outside the one test that says what needs a server
date: 2026-08-19
category: test-failures
module: lark-ffi
problem_type: test_failure
component: testing_framework
symptoms:
  - Two new Ark-dependent FFI exports shipped with no test calling either one at any layer
  - The hermetic lane reported 8 tests, 0 skipped, 0 failures and asserted nothing about them
  - A code review caught the gap, not the suite that exists to catch it
  - The lane's Ark-dependent/local split is a hand-written enumeration, so a new export is uncovered by default
root_cause: missing_workflow_step
resolution_type: test_fix
severity: medium
related_components:
  - tooling
  - development_workflow
  - testing_framework
tags:
  - ci
  - rust-ffi
  - uniffi
  - verification-fidelity
  - coverage-gap
  - characterization-test
  - ark-server
---

# A new server-dependent export lands outside the one test that says what needs a server

## Problem

Two new exports were added to the `lark-ffi` crate — `next_round_time` and `reconnect_ark`, both of which require a reachable Ark server — and neither was called by any test at any layer. The suite stayed green throughout, because the test that would have covered them enumerates its subjects by hand.

## Symptoms

- `FfiHostLibraryTest` reports `tests="8" skipped="0" failures="0"` while asserting nothing about either new export.
- A repo-wide search for the new names (`next_round_time`, `reconnect_ark`, `nextRoundTime`, `reconnectArk`) returns only production files and generated bindings — no test file.
- The only Rust test added alongside the feature covers a pure helper (`epoch_seconds`), not the exported method that calls it.
- The gap surfaces in code review rather than from any lane.

## What Didn't Work

Nothing was tried and rejected here — the point is what *passed*. Every gate the change went through was green while the gap existed:

- The full `scripts/ci.sh` run, including its assertion that the FFI lane really ran (`tests > 0`, `skipped == 0`).
- The committed-bindings drift checks for both Kotlin and Swift, which prove the *shape* of a new export is committed but say nothing about its *behavior*.
- A live manual verification against a running captaind, which exercised the happy path and therefore could not notice that the failure path was unpinned.
- The author's own reading of the diff, which treated "the FFI lane is green" as coverage of the FFI change.

The last one is the trap: the lane's name suggests it covers the FFI surface, but it covers exactly the operations someone once wrote down.

## Solution

Extend the existing characterization test rather than adding a new one. `onlyArkDependentOperationsFailWithoutAnArkServer` opens a real wallet against an unreachable Ark server and asserts the local/Ark split; the fix is two more assertions in that same test, mirroring the `mintAddress` one already there:

```kotlin
// Ark-backed: minting a receive address is a captaind round-trip.
assertTrue(
    runCatching { wallet.mintAddress() }.isFailure,
    "mint_address must not appear to succeed without an Ark server",
)

// The round schedule is the server's own, not the chain's.
assertTrue(
    runCatching { wallet.nextRoundTime() }.isFailure,
    "next_round_time must not appear to succeed without an Ark server",
)

// Reconnecting has nothing to reconnect to, and must say so.
assertTrue(
    runCatching { wallet.reconnectArk() }.isFailure,
    "reconnect_ark must not report success without a reachable Ark server",
)
```

Cost: across runs measured while making this change the lane went from ~92s to ~107s, because each new assertion waits out a connection failure against the unreachable server. That is the price of the coverage and it is charged on every PR.

## Why This Works

The test's value is not that it exercises an export — it is that it pins a *classification*. Each assertion states "this operation needs a server, and must fail rather than appear to succeed when there isn't one." An export that starts answering from somewhere else — a cache, a default, a local fallback added for convenience — breaks that assertion loudly, on a lane that needs no infrastructure.

That matters most where the honest-unknown discipline lives. The row `next_round_time` feeds renders an em-dash when the call fails, so an export that silently started succeeding without a server would convert an admitted unknown into a confident wrong number, on a screen whose whole purpose is telling the user the truth about protocol state. Nothing else in the hermetic lane would notice.

Asserting the behavior also beats tracing the code, because the two exports do not enforce the requirement the same way. `next_round_time` reaches bark's `require_server()` and fails when no connection was ever established; `reconnect_ark` goes through `refresh_server`, which either connects (and fails if it cannot) or health-checks the connection it already has. A reviewer checking "does this call `require_server()`?" would classify the second one wrong. The assertion asks the only question that matters to a caller — does it refuse to look successful without a server — and gets the same answer for both.

Extending the existing test rather than writing a parallel one also keeps the enumeration in one place, which is the only thing that makes it reviewable as a list.

## Prevention

**The rule: adding a server-dependent export is not done until the split test names it.** Treat the enumeration as part of the export's definition, not as optional coverage.

Concretely, when adding an operation to the FFI surface, ask which side of the split it falls on and add the corresponding assertion in the same commit:

- **Needs the Ark server** (mint, round schedule, reconnect, any send) -> add a `runCatching { … }.isFailure` assertion to `onlyArkDependentOperationsFailWithoutAnArkServer`.
- **Local or chain-backed only** (balance, movements, deposit address, fingerprint) -> add a positive assertion to the same test's local block.

Two signals that this gap has recurred, both cheap to check:

```bash
# Every exported async method on LarkWallet...
grep -n "pub async fn" rust/lark-ffi/src/wallet.rs

# ...should appear either in the split test or in a deliberate exclusion.
grep -c "runCatching" composeApp/src/androidUnitTest/kotlin/xyz/lark/app/core/ffi/FfiHostLibraryTest.kt
```

A drift check could enforce this mechanically — diff the exported method list against the names appearing in the split test and fail on an unclassified addition — but that has not been built. Until it is, the review question is the control: *"which new exports did this add, and where are they classified?"*

Note what this does **not** protect against: the lane proves an operation fails without a server, never that it succeeds *with* one. Confirming the happy path stays live-lane work.

## Related

- [A test lane that skips itself can make a required CI lane green without verifying anything](silently-skipped-test-lane-passes-ci.md) — the same lane reporting green while asserting nothing, by skipping instead of by omission.
- [A test lane that runs every assertion can still be verifying the previous build](test-lane-verifies-a-stale-native-library.md) — the same lane running every assertion against the wrong artifact.

These three are one family: the pure-local lane can be green and mean nothing in three distinct ways — it skipped, it tested a stale build, or it never covered the thing you changed. The first two are now guarded mechanically; this one is still guarded by attention.

Introduced and fixed in PR #48.
