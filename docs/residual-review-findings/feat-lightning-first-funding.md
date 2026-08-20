# Residual review findings — feat/lightning-first-funding

Source: `ce-code-review` (multi-agent, serial inline) against `main` at `41e2168`, plan
`docs/plans/2026-08-19-001-feat-lightning-first-funding-plan.md`. Finding 1 of five was applied in
`56545ed`; the rest are recorded here.

No tracker ticket was filed: this run was scoped to commit, push, and PR. These are inlined
verbatim so this file is the durable record rather than a pointer to one.

## Residual Review Findings

- **P2 — `composeApp/src/iosMain/kotlin/xyz/lark/app/core/ffi/DelegateBackedLarkCore.kt:566` — a
  cancelled mint orphans a server-side receive, outside the bound the plan accepted.**
  `AppStateMachine.kt:601` cancels `receiveCodeJob` on every new amount. If cancellation lands while
  `invoiceFor` is awaiting the delegate, the server has already persisted the receive
  (`start_lightning_receive` writes before returning) but the `?.also` that would cache it never
  runs, so `try_claim_all_lightning_receives` retries that receive on every maintenance pass
  indefinitely. The plan records this family as Risk 5 and accepts it on the stated bound of "one
  per distinct amount requested" — but that bound comes from the cache, and a cancelled mint never
  reaches the cache. Orphans from rapid amount entry are therefore unbounded, which is not the risk
  that was accepted. Needs either a bound or an honest restatement of Risk 5.

- **P3 — `composeApp/src/commonTest/kotlin/xyz/lark/app/core/ReceiveCodeDecisionTest.kt` — AE1 was
  asserted as substring containment rather than as an amount.** Largely discharged by the finding-1
  fix in `56545ed`, which added amount assertions including the mutinynet prefix the app ships.
  Recorded because the original acceptance example remains weaker than the behaviour now enforced,
  and a future edit could regress the assertion without failing AE1 as written.

- **P3 (advisory) — `composeApp/src/commonMain/kotlin/xyz/lark/app/ui/screens/onboarding/FundScreen.kt:27`
  — R8/AE7's vocabulary ban has no automated check.** "board", "Ark", "VTXO", and "invoice" are
  forbidden on changed surfaces. The new card copy and rewritten `SUB_COPY` are private consts with
  no UI test lane, so compliance rests on inspection. It holds today; nothing stops the next edit
  breaking it silently.

- **P3 (advisory) — `composeApp/src/commonMain/kotlin/xyz/lark/app/ui/screens/receive/ReceiveScreen.kt:72`
  — `ReceiveActions` is an unstable Compose parameter.** A data class of lambdas makes the screen
  recompose with its parent rather than skipping. Matches the existing `DepositActions` precedent
  and was the detekt-sanctioned way out of the parameter limit, so recorded as a known cost.

## Plan accuracy

The plan's Problem Frame and U5 approach both assert the FFI receive code is a raw Ark address. It
was already a BIP-321 URI — `DelegateBackedLarkCore.kt:486` calls `arkReceiveUri`. The
implementation is correct and the prose is stale; the plan was left unedited because it is a
decision artifact. U4 was dropped during implementation for the same reason: its premise was that
the FFI core could not reach the BIP-321 builders, and it already imports them.

## Tooling defect found during this work

`scripts/build-xcframework.sh` selects the **device** library for bindgen
(`aarch64-apple-ios`), but `FFI_SIM_ONLY=1` rebuilds only the **simulator** slice. With a stale
device library present, it silently regenerates the committed Swift glue from an old crate surface:
the first attempt in this branch produced 104 deletions and omitted the new export entirely. Same
family as `docs/solutions/test-failures/test-lane-verifies-a-stale-native-library.md`. Worth either
a guard in the script (fail when the chosen bindgen slice is older than the crate source) or its own
solution note.
