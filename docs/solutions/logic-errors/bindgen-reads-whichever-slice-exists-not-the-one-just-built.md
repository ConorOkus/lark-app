---
title: Selecting a build artifact by existence generates bindings from a crate you deleted three days ago
date: 2026-08-20
category: logic-errors
module: lark-ffi
problem_type: logic_error
component: build_system
symptoms:
  - A regenerated binding file comes back with only deletions and none of the new exports
  - The freshly added export is absent from the generated Swift glue while present in the generated Kotlin
  - The script exits 0 and reports "refreshing committed Swift glue" while writing older output
  - Committed generated source silently regresses to an earlier crate surface
root_cause: config_error
resolution_type: code_fix
severity: high
related_components:
  - tooling
  - development_workflow
tags:
  - rust-ffi
  - uniffi
  - codegen
  - stale-artifact
  - build-profile
  - xcframework
  - false-pass
  - generated-code
---

# Selecting a build artifact by existence generates bindings from a crate you deleted three days ago

## Problem

`scripts/build-xcframework.sh` generates the committed Swift glue by pointing `uniffi-bindgen` at a compiled library slice. It chooses that slice by asking which file exists:

```bash
# scripts/build-xcframework.sh:62-63
BINDGEN_LIB="$BUILD/aarch64-apple-ios/$PROFILE/$LIB"
[ -f "$BINDGEN_LIB" ] || BINDGEN_LIB="$BUILD/aarch64-apple-ios-sim/$PROFILE/$LIB"
```

Device first, simulator as fallback. The comment above it explains why that is safe — "the generated API does not depend on target or profile" — and it is right about target and profile. It is silent about *time*, which is the axis that matters: nothing checks that the chosen slice was built from the current crate.

`FFI_SIM_ONLY=1` exists to halve the build by skipping the device target. Combine the two and the failure is immediate. The simulator slice gets rebuilt from the new crate; the device slice, untouched and still on disk from some earlier session, wins the existence check; bindgen reads it. The script then overwrites the committed glue with bindings for a crate that no longer exists.

## Symptoms

- A new `#[uniffi::export]` method appears in the generated **Kotlin** bindings (`+60` lines) and is entirely absent from the generated **Swift** glue.
- `git diff` on the Swift glue shows **only deletions** — here `104 -`, `0 +` — because the stale slice predates several exports the committed file already had.
- The script prints `==> generating Swift bindings + headers` and `==> refreshing committed Swift glue`, then exits 0. Both statements are true; neither is useful.
- The only signal separating right from wrong is a timestamp:

```
2026-08-17 23:02  target/aarch64-apple-ios/release/liblark_ffi.a      <- bindgen read this
2026-08-20 08:15  target/aarch64-apple-ios-sim/release/liblark_ffi.a   <- this run built this
```

## What Didn't Work

**Reading the surrounding comment as reassurance.** "The generated API does not depend on target or profile" is a correct statement that answers the wrong question. It licenses picking *either* slice, which is exactly the reasoning that makes picking a stale one feel safe. A comment justifying indifference between two artifacts is worth re-reading as a question about what else might differ between them.

**Assuming the sim-only flag is only a speed knob.** `FFI_SIM_ONLY=1` reads as "build less, get the same thing." It is instead the thing that decouples the artifact bindgen reads from the artifact this run produced. The flag is safe on a clean target directory and unsafe on a warm one, which is the reverse of the intuition that a warm cache is the harmless case.

**Trusting the drift check to catch it.** `FFI_CHECK_GLUE=1` diffs generated output against the committed file, and CI sets it — so CI *would* have caught this. Locally the same script takes the other branch and **writes** instead of comparing. The guard that exists is a verifier in one mode and the corrupting step in the other, and the corrupting mode is the default for a developer.

## Solution

Run the full build so the device slice is current:

```bash
./scripts/build-xcframework.sh   # no FFI_SIM_ONLY
```

The regenerated glue then shows `+50` insertions carrying the new export, and the device slice timestamp moves to the current run.

The durable fix is to stop selecting by existence. Either pick the slice this invocation actually built — the script already knows, because `FFI_SIM_ONLY` is its own flag — or refuse to proceed when the chosen slice is older than the crate source. The second is strictly better as a guard: it fails loudly on any staleness, including a device build that failed halfway.

## Why This Works

Only the immediate fix is a fix; the structural problem stands until the selection rule changes. `-f` asks whether a path is populated, and the question the script needs answered is whether it is *current*. Those coincide on a clean checkout and diverge on every machine that has built this before — so the defect is invisible in CI and near-permanent locally, which is the worst possible distribution.

What makes this sharper than an ordinary stale-cache bug is that the output is **committed source**. A stale test artifact yields a meaningless pass, recoverable by re-running. A stale codegen input yields a plausible diff that a reviewer reads as intentional deletion, and it lands.

## Prevention

- **Select build artifacts by provenance, not by presence.** `[ -f ]` answers a different question than "did this run produce it." Any script that falls back between artifacts needs a freshness condition, not just an existence one.
- **A flag that skips a build step can change which artifact a later step consumes.** Trace what else reads the output before treating a `SKIP_`/`ONLY_` flag as purely a speed knob.
- **When a guard has a check mode and a write mode, the write mode is unguarded.** Here CI verifies and local overwrites; the mode with no verification is the one humans run.
- **Only deletions in a generated diff means the generator read the wrong input.** Codegen from newer source does not remove exports. Treat a deletion-only regeneration as a stale-input signal, never as a real API removal.
- **Regenerating two binding languages is two commands, and they do not share an input.** `scripts/generate-bindings.sh` builds a host library for Kotlin; `scripts/build-xcframework.sh` builds iOS slices for Swift. Kotlin being correct says nothing about Swift.

## Related Issues

- [A test lane that runs every assertion can still be verifying the previous build](../test-failures/test-lane-verifies-a-stale-native-library.md) — the same root fact from the other side. There the stale artifact produced a false pass; here it produces false source. Both come from an artifact the build system does not model.
- [A test lane that skips itself can make a required CI lane green without verifying anything](../test-failures/silently-skipped-test-lane-passes-ci.md) — the first of the family.
- PR #49 — the Lightning funding branch this surfaced on. The stale glue was caught before commit; the fix was a full rebuild.
