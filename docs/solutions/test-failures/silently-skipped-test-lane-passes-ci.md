---
title: A test lane that skips itself can make a required CI lane green without verifying anything
date: 2026-07-31
category: test-failures
module: lark-ffi
problem_type: test_failure
component: testing_framework
symptoms:
  - Required CI lane reports success while the FFI test lane skipped every assertion
  - Hiding the native library leaves the unit-test task UP-TO-DATE instead of re-running
  - Gradle serves the test task FROM-CACHE, replaying an earlier run in which the lane skipped
  - "scripts/ci.sh exits 0 having never exercised the in-process wallet"
root_cause: config_error
resolution_type: config_change
severity: high
related_components:
  - tooling
  - development_workflow
tags:
  - ci
  - gradle
  - build-cache
  - test-skip
  - jna
  - verification-fidelity
  - false-pass
  - rust-ffi
---

# A test lane that skips itself can make a required CI lane green without verifying anything

## Problem

The FFI test lane loads a Rust library through JNA and skips itself when that library will not load, so `./gradlew` still works on a checkout with no Rust toolchain. On the required CI lane — whose entire purpose is proving the in-process wallet works — that same skip made a green run meaningless: the lane could report success having asserted nothing, and nothing distinguished that from a real pass.

## Symptoms

- `scripts/ci.sh` exits 0 and CI is green, while the FFI lane contributed zero executed assertions.
- With the host library hidden, `:composeApp:testDebugUnitTest` resolves **UP-TO-DATE** rather than re-running.
- On a later run the same task resolves **FROM-CACHE**, replaying a stored result from a run where the lane skipped.
- The test-results XML shows `tests="5" skipped="5"` while the build reports success.

## What Didn't Work

Two fixes that each looked sufficient and were not. Both are worth recording, because the failure mode is that each one *appears* to close the hole.

1. **Turning the skip into a failure, alone.** `scripts/ci.sh` exports `LARK_REQUIRE_FFI=1` whenever it runs the Rust leg (`scripts/ci.sh:28`), and the lane's gate fails instead of skipping when that is set (`composeApp/src/androidUnitTest/kotlin/xyz/lark/app/core/ffi/FfiHostLibraryTest.kt:159-162`). This is necessary but not sufficient: it only fires if the tests actually execute.

2. **Declaring the native library as a Gradle task input.** The intuition was that Gradle could not see the library — it is reached through a `jna.library.path` system property — so declaring it via `inputs.files(fileTree(...))` should make its presence part of the cache key. Measured: it did not work. Hiding the library still left the task **UP-TO-DATE**. Rather than leave a plausible-looking declaration that does nothing, it was removed.

The general trap: reasoning about what *should* invalidate a task, instead of running the build twice and reading the task outcome line.

## Solution

Three layers, because no single one closes the hole.

**1. An absent library fails rather than skips, but only on the lane that must verify.**

```kotlin
// FfiHostLibrary.kt:36
val required: Boolean get() = System.getenv("LARK_REQUIRE_FFI") == "1"
```

```kotlin
// FfiHostLibraryTest.kt:159-162
private fun requireHostLibrary() {
    if (FfiHostLibrary.available) return
    if (FfiHostLibrary.required) fail(FfiHostLibrary.REQUIRED_FAILURE_MESSAGE)
    assumeTrue(FfiHostLibrary.SKIP_MESSAGE, false)
}
```

**2. On that lane, the task genuinely executes** — no up-to-date shortcut, no cache hit (`composeApp/build.gradle.kts:129-137`):

```kotlin
if (ffiLaneRequired) {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
```

The non-required lane keeps normal caching, so a developer's `./gradlew` stays fast.

**3. The script asserts the outcome instead of trusting the exit code** (`scripts/ci.sh:54-70`). Parse the lane's results and require a positive test count with zero skips:

```bash
if [ "${ffi_tests:-0}" -eq 0 ] || [ "${ffi_skipped:-1}" -ne 0 ]; then
    echo "error: the FFI lane did not verify the Rust core" >&2
    exit 1
fi
echo "==> FFI lane verified the Rust core ($ffi_tests tests, 0 skipped)"
```

Note the defaults: `${ffi_tests:-0}` and `${ffi_skipped:-1}` both fail closed, so an unreadable or missing results file is treated as "did not verify" rather than passing by accident.

Verified four ways: library absent with the flag set → 5 failures, exit 1; library absent without it → 5 skipped, green; the required lane executes rather than resolving UP-TO-DATE or FROM-CACHE; and a full run prints `FFI lane verified the Rust core (5 tests, 0 skipped)`. Confirmed on `main` after merge (PR #26).

## Why This Works

The skip is a real requirement, not a bug — a contributor without a Rust toolchain must still be able to build and test. The defect was that one mechanism served two lanes with opposite needs: on a dev machine an absent library means "skip politely," and on the required lane it means "this run proved nothing."

Splitting on an explicit environment flag lets both be true. The three layers then close three distinct escape routes:

- The gate handles **the library being absent**.
- Disabling up-to-date and cache handles **the tests never running**, which the gate cannot catch because a gate in un-executed code fires never.
- The outcome assertion handles **everything else** — a filter change, a renamed class, a results file that never got written — because it checks the property actually wanted ("this lane ran and asserted things") rather than a proxy for it.

The deeper reason the first two fixes were insufficient: an exit code is a proxy. `gradlew` exiting 0 means "no task failed," which is satisfied by a task that never ran. Only the third layer states the real claim.

## Prevention

- **For any verification mechanism, ask: if this is wrong, does it fail loudly or pass silently?** Guards, CI gates, coverage checks, and lint steps are all code that can be wrong in the direction of "green." Their risk is not blast radius, it is fidelity.
- **A conditional skip and a required lane are a contradiction.** Where a lane may legitimately skip locally, make the required lane assert that it did not.
- **Do not infer Gradle's incremental behavior — measure it.** Run the build twice and read the task outcome (`UP-TO-DATE`, `FROM-CACHE`, or bare execution). Inputs reached through system properties, environment variables, or absolute paths are invisible to Gradle's model.
- **A cached green is not a green.** Any task whose real inputs Gradle cannot see must opt out of caching on the lane that depends on it having run.
- **Assert the outcome, not the exit code.** When a lane's value is "these specific tests ran," check the results for a positive count and zero skips. Default the parsed values to failing, so an unreadable file cannot pass.
- **When a fix is added to close a hole, verify the hole is closed** by reproducing the original failure with the fix in place. Here that meant hiding the library and confirming a hard failure — which is how the second insufficient fix was caught.

## Related Issues

- PR #26 — merged as `838e099`, the change described here.
- `docs/ffi/kotlin-bindings-status.md` — the FFI lane's scope, its hermetic chain-source stub, and the separate unresolved async-bridge blocker that kept `FfiLarkCore` out of that PR.
- `docs/plans/2026-07-31-001-feat-ffi-lark-core-kotlin-plan.md` — R6 (a Rust-free checkout must still build) and R7 (the required lane must verify), the two requirements this defect sat between.
