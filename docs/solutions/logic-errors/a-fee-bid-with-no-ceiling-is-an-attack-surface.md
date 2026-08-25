---
title: A fee bid with no relation to what it recovers is an attack surface, not just waste
date: 2026-08-25
category: logic-errors
module: forks/bark
problem_type: logic_error
component: unilateral_exit
symptoms:
  - A CPFP child pays more in fees than the transaction it confirms is worth to the wallet
  - An RBF replacement's minimum fee is set by whatever child a third party put on the anchor
  - A fee-bump escalates without any bound tied to the value being recovered
  - An exit that declines an uneconomic race looks identical to an exit that is stuck
root_cause: missing_validation
resolution_type: code_fix
severity: high
related_components:
  - exit
  - onchain
  - fees
tags:
  - cpfp
  - rbf
  - p2a
  - unilateral-exit
  - fee-burn
  - adversarial
  - upstream-port
---

# A fee bid with no relation to what it recovers is an attack surface, not just waste

## Problem

LARK's unilateral exit drags each exit-tree transaction into a block by attaching a CPFP child that
pays the fee. The builder that constructs that child — `make_signed_p2a_cpfp` in bark's shared
`bitcoin-ext` — computed `parent_weight * fee_rate`, then grew the fee in a loop until the child's
own weight and any RBF minimum were satisfied. Nothing anywhere compared the result to what
confirming the package was actually worth to us.

Two facts turn that from waste into an exposure.

**An exit-tree transaction is mostly other people's money.** A shared tree transaction delivers
value to many branches; ours is one leaf. Bidding against its delivered value spends our on-chain
coins to confirm strangers' outputs. In LARK's proven exit drill the wallet recovered 90,000 sat
from a tree whose upper transactions carry far more — so "the package's value" and "our value" are
different numbers, and only one of them is ours to spend.

**P2A anchors are anyone-can-bump.** The RBF branch prices our replacement off
`current_package_fee` — the fee of whatever child is currently standing on the anchor. Anyone can
put a child there. A third party's bulky, high-absolute-fee child therefore drags our minimum up
behind it, and without a ceiling the wallet pays whatever that implies. The rival never has to
outbid us honestly; they only have to make winning expensive.

## Root cause

The builder had no concept of what the caller stood to recover. Fee was derived entirely from rate,
weight, and whatever a stranger had already bid — three inputs, none of which is the value at risk.

## Resolution

Ported the channels-independent core of gsanders87's `f1333628` (upstream MR !2444, flagged
`FOR UPSTREAM` as independent of the channels work) by hand onto our fork. Three parts:

**An intrinsic ceiling in the builder.** `make_signed_p2a_cpfp` takes `stake: Option<Amount>` and
refuses when the fee would exceed `min(stake, delivered)`, checked against both the opening estimate
and the grown fee on every loop pass. Enforcing it in the builder rather than at call sites is the
point: no caller can bid past it by construction. `None` means the whole delivered value is the
caller's — a ceiling of everything the package pays out, **never** the absence of one.

**The real stake at the exit call site.** `ProgressContext::create_exit_cpfp_tx` reads
`self.vtxo.amount()` rather than defaulting to face value. It reads it from the context instead of
taking a parameter so a future call site cannot forget.

**Cede, and record that you ceded.** A refusal is not a failure, so it must not error the exit — it
holds the transaction's current status and lets a later tick re-bid at the plain target. The
replacement arm cedes to `NeedsReplacementPackage`, never back to `NeedsSignedPackage`, because the
standing child is still on the anchor.

Also corrected the RBF minimum, which charged relay bandwidth for the parent as well as the
replacement. Only the child is replaced; the parent stays in the mempool. Left uncorrected it makes
refusals more likely — so it would have made the wallet cede races it should win.

## The bound this buys is per-level, not per-recovery

Each individual bid is now capped at what that transaction recovers for us. **An N-level exit chain
can still commit up to N × stake in total**, because the driver asks once per level and nothing
remembers what earlier levels already spent. Upstream closes that with a durable per-recovery budget
(`m0048_exit_child_fee`); porting it needs persistence surgery in a fork that is 2685 commits behind
upstream master, with an e2e lane that needs bitcoind and postgres. Describe the protection this way
and no other — the exposure is reduced, not closed.

## Correct and catastrophic looked identical

The subtlest part is not the ceiling; it is what a refusal looks like from outside.

The exit races the VTXO's expiry. A rival holding an expensive child on our anchor can keep every
bid above the ceiling indefinitely, and the wallet will keep correctly declining — until the money
expires. Both the healthy case ("declining an uneconomic race") and the loss case produce the same
observable: a status that does not move.

A log line does not close that gap. Nothing in a wallet reads logs, and under `CoreMode.FFI` no
daemon is watching on the user's behalf. So the cede is recorded on the `ExitTx` itself
(`#[serde(default)]`, so existing persisted exits load unchanged), cleared per transaction by the
driver so a bid we win supersedes a bid we ceded. Surfacing it through the FFI into the UI is
follow-up work — recording it is what makes that reachable.

## Testing note worth reusing

Upstream's ceiling test drives a funded bdk `Wallet` through `two_utxo_wallet()`. That fixture does
not exist in our fork — `bitcoin-ext/src/bdk.rs` has no test module at all, and the helper arrived
upstream after our fork point on bdk_wallet 3.1 where we are on 2.1. Rather than port a fixture
tail, the economic rule was extracted into two pure functions (`cpfp_fee_ceiling`,
`check_cpfp_fee_ceiling`) and tested directly; the same move made the cede decision testable
(`classify_cpfp_failure`) without standing up a `ProgressContext` with a real wallet, database, and
chain source.

**When a rule matters more than the machinery around it, give the rule a name and test the name.**
The alternative here was an untested economic ceiling, which is the wrong trade.

`classify_cpfp_failure` names every `CpfpError` variant with no catch-all arm, so adding a variant
fails to compile until someone decides which bucket it belongs in. The two buckets differ by whether
the user's money is in danger, and the safe-looking default — treating an unknown failure as a calm
decline — is the dangerous one.

## Guarding the fork bump

`rust/lark-ffi/src/wallet.rs` carries `cpfp_stake_ceiling_tests`, which names `ExceedsStake` and the
`stake` argument directly through a `bark-bitcoin-ext` dev-dependency. A future pin bump that loses
the fix fails to compile rather than silently restoring the exposure. This mirrors
`ark_connect_timeout_tests`, which exists for the same reason.
