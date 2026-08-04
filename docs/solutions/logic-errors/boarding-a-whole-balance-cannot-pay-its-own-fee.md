---
title: Boarding a whole balance cannot pay its own on-chain fee
date: 2026-08-04
category: logic-errors
module: wallet funding (board path)
problem_type: logic_error
component: payments
symptoms:
  - "\"Move it in\" always fails with \"That did not go through. Your money is still on-chain — try again.\""
  - "The deposit is confirmed on-chain and above the server's board minimum, yet no board transaction is ever broadcast"
  - "No cause is recorded anywhere — the failure surfaces as a bare boolean with the engine's error text discarded"
root_cause: wrong_api
resolution_type: code_fix
severity: high
tags: [boarding, ark, on-chain-fees, wallet-funding, whole-balance, uniffi]
---

# Boarding a whole balance cannot pay its own on-chain fee

## Problem

The funding flow's "Move it in" affordance boarded the user's entire confirmed on-chain
balance by passing that figure as the board *amount*. Boarding always failed, so a
keys-on-device wallet could never be funded at all — the money stayed on-chain with the
app reporting a generic failure.

## Symptoms

- Tapping "Move it in" returns to the deposit screen with "That did not go through. Your
  money is still on-chain — try again."
- The deposit is confirmed and comfortably above captaind's `min_board_amount`
  (`deploy/fly/captaind.toml.template:28`), so the UI's own gate is satisfied and the
  button is enabled.
- No board transaction appears at the deposit address on the explorer — the failure is
  before broadcast, not a stuck transaction.
- Nothing anywhere records *why*. This is the part that cost the most time.

## What Didn't Work

- **Reading the app's own error state.** By design the Kotlin adapter collapses every FFI
  failure into one coarse seam outcome (`boardAll()` returns `Boolean`,
  `composeApp/src/commonMain/kotlin/xyz/lark/app/core/OnchainFunding.kt:40`), discarding the
  engine's message. The screen could say *that* it failed and never *why*. The diagnosis
  only became possible after adding `NSLog` to the Swift delegate's error paths, which is
  now permanent for exactly this reason.
- **Suspecting the confirmation count.** captaind requires three confirmations for a board,
  so the obvious first theory was "not confirmed enough yet". Wrong: those confirmations
  apply to the board transaction *after* it is broadcast, and this failure happened before
  any broadcast. Waiting longer changed nothing.
- **Suspecting the minimum-amount gate.** The deposit was 60,000 sat against a 20,000 sat
  minimum, so this was never it — but it looks plausible when the only signal is a boolean.

## Solution

Stop naming an amount. Export bark's `board_all`, which boards the whole on-chain balance
and works out the boardable figure after fees itself, and call that from the funding flow.

```rust
// rust/lark-ffi/src/wallet.rs:223 — the export the app now uses
pub async fn board_all(&self) -> Result<String, LarkError> {
    let mut onchain = self.onchain.lock().await;
    let pending = self.inner.board_all(&mut *onchain).await.map_err(LarkError::from)?;
    Ok(format!("{pending:?}"))
}
```

bark's own `Wallet::board_all` is a thin wrapper that calls its internal board path with
`amount: None` — the `None` is the whole fix, because it hands fee arithmetic to the engine
that is building the transaction.

The amount-taking export is kept (`rust/lark-ffi/src/wallet.rs:235`) for a future
partial-board affordance, with a doc comment pointing whole-balance callers at `board_all`.

Through the seam, `OnchainFunding.boardAll()` takes no amount at all
(`composeApp/src/commonMain/kotlin/xyz/lark/app/core/OnchainFunding.kt:40`), so the trap
cannot be re-entered by a future caller:

```kotlin
// composeApp/src/commonMain/kotlin/xyz/lark/app/state/AppStateMachine.kt:242
// The confirmed balance still gates whether boarding is offered — it is just no longer
// passed as the amount.
fun boardConfirmed() {
    val sats = funding?.confirmedSats ?: 0L
    val boardable = funding != null && !state.boarding && sats >= funding.minBoardSats
    if (boardable) { /* … */ requireNotNull(funding).boardAll() /* … */ }
}
```

Measured fees once it worked: **112 sat** on a 60,000 sat deposit (simulator) and **121 sat**
on a 30,000 sat deposit (physical device). Small, and exactly large enough that requesting
the full balance can never succeed.

## Why This Works

A board transaction spends the wallet's on-chain UTXOs and pays a miner fee from them. Asking
to board *B* when the wallet holds exactly *B* is asking for a transaction whose outputs equal
its inputs — there is nothing left for the fee, so coin selection fails before anything is
signed or broadcast.

The failure is total rather than intermittent, which is the tell: it is arithmetic, not
network flakiness. Any amount strictly below the balance minus the fee would have worked,
which is why the bug survives casual testing with a partial-amount UI and only bites the
"move everything" case.

Handing the decision to the engine is right beyond avoiding an off-by-a-fee: the fee depends
on the input set and the current fee rate, both of which the engine knows while it builds the
transaction and the caller can only guess at beforehand.

## Prevention

- **Any "send/move my whole balance" affordance must let the engine compute the amount.**
  Look for a `*_all` / `drain` / `sweep` entry point and use it. If a wallet API offers both
  an amount-taking and an amount-free variant, the amount-free one exists precisely because
  the caller cannot do this arithmetic correctly.
- **Never derive a spend amount from a displayed balance.** A displayed figure is a gross
  number; a spendable amount is net of fees the caller does not know.
- **Design the seam so the trap is unreachable.** `boardAll()` takes no parameter, so no
  future caller can pass a whole balance. That is stronger than a comment warning against it.
- **Log the cause at the FFI boundary even when the seam intentionally discards it.** A
  deliberately coarse seam (one boolean for "it failed") is good API design and terrible
  diagnostics; the two are reconciled by logging at the boundary where the message still
  exists. Without that, this bug is invisible in the field as well as in development.
- **Prove money flows by moving money.** Every screen in this flow rendered correctly and the
  gate enabled itself correctly; the operation behind the button had never once succeeded.
  A UI walkthrough cannot detect this class of defect.

## Related Issues

- Shipped in PR #37 (`feat(m2): keys on device on iOS, and a TestFlight lane`).
- [Silently skipped test lane passes CI](../test-failures/silently-skipped-test-lane-passes-ci.md)
  — same failure family: a check that reports success while proving nothing.
- The board minimum this flow gates on lives in `deploy/fly/captaind.toml.template`; see
  `docs/liveness-envelope.md` for the other captaind parameters the wallet depends on.
