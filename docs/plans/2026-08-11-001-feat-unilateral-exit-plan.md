---
title: Unilateral Exit - Plan
type: feat
date: 2026-08-11
topic: unilateral-exit
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-brainstorm
execution: code
---

# Unilateral Exit - Plan

## Goal Capsule

- **Objective:** Make unilateral exit real for VTXOs — a wallet can leave the Ark without the Ark server, land its funds on-chain, and send them to an address the holder names. Channel exit is not active scope.
- **Product authority:** This plan owns VTXO exit and the on-chain send that makes exited funds reachable. Channel force-close resolution, background progression, and tester-facing comprehension are named as surrounding work, not requirements here.
- **Open blockers:** None.
- **Product Contract preservation:** Product Contract unchanged. One planning conflict is recorded against KTD-3 (see Planning Contract) without altering the decision.

---

## Product Contract

### Summary

Unilateral exit becomes a wallet-level state: starting one takes the wallet out of the Ark, every app open resumes it until every VTXO is claimed on-chain, and the wallet then returns to normal with a spendable on-chain balance. A general on-chain send makes those funds reachable. A re-runnable headless drill proves the whole path with the Ark server stopped.

### Problem Frame

The app already makes the claim. `composeApp/src/commonMain/kotlin/xyz/lark/app/ui/screens/settings/ExitScreen.kt` tells the user "You don't need permission from anyone to do this — that's the point of LARK," under a button whose handler disarms the funding intent and navigates home (`composeApp/src/commonMain/kotlin/xyz/lark/app/state/AppStateMachine.kt:275`). The figures on that screen — a `~$1.80` miner fee, `about 24 hours` to spendable — are string literals. The claim is made and unbacked, which is worse than not making it.

The cost is not hypothetical. `docs/liveness-envelope.md` derives the safe-to-leave-closed bound and then names this gap directly: because exit is a stub, "the server's continued operation is part of the envelope, not a fallback." Every guarantee the wallet currently offers rests on captaind staying up. That is acceptable on a test network and nowhere else, and it cannot be argued down — only demonstrated away.

Nothing about the underlying mechanism is missing. The pinned bark fork (`rust/fork-pins.toml`) ships a complete exit engine. What is missing is the path from the app to it, the state that survives an app restart, and any evidence the path works when the server is gone.

### Key Decisions

- **Exiting is a state the wallet is in, not a screen it shows.** (session-settled: user-directed — chosen over a foreground progress screen and a two-visit "you can close the app now" flow: only a wallet-level mode resumes on every open and structurally prevents boarding back into a wallet that is leaving.) An exit takes hours across `Processing → AwaitingDelta → Claimable → Claimed`, and nothing runs while the app is closed. Making it a mode means every launch drives it forward before anything else, and the app never has to guess whether the user remembers an exit is in flight.

- **An exit runs to completion; there is no cancel.** (session-settled: user-approved — chosen over cancel-before-broadcast and over retiring the wallet outright: a transaction in the mempool cannot be recalled, so a stop control after that point would misrepresent what the app can do.) Cancel-before-broadcast buys seconds of optionality for a whole extra state. Retiring the wallet would force a new wallet per test run, which is hostile to the reason this is being built. Exiting mode therefore ends at `Claimed`, and the wallet becomes an ordinary wallet that can board again — the existing arm-on-deposit-screen funding guard keeps exit proceeds from being swept back in. The accepted consequence is that a stalled exit holds the wallet in the mode indefinitely; the wallet reports the stall and keeps retrying rather than offering a way out that would not be true.

- **Withdraw is a general capability, not a step inside the exit.** (session-settled: user-directed — chosen over carrying a destination address through the exit and over a sweep-only button on the post-exit screen.) On-chain send becomes available whenever there is an on-chain balance, which also rescues stuck boards and leftover change. The consequence is that the escape hatch is two deliberate actions: a holder who exits and stops has funds in the app's on-chain wallet, not at an address they control.

- **Channel exit is out.** (session-settled: user-directed — chosen over covering channel force-close through the same path: VTXOs landing on-chain is a sufficient proof of the property, and no shipping core holds a channel to exit.) `LarkCore.channels` stays null on every core except the channel-fork gateway, and the FFI exposes no channel surface, so channel exit would be built against a path that does not exist yet.

- **Prove it headless before building the screen, and keep the drill.** (session-settled: user-approved — chosen over building the product arc first and taking the proof at the end.) A three-hour nondeterministic flow debugged through a phone UI is the wrong loop; a headless drill fails in minutes with logs. Keeping the drill rather than discarding it extends the fork-pin discipline already in `rust/fork-pins.toml` to the one property the README promises publicly, so a bark bump cannot silently break the exit claim.

### Actors

- A1. Wallet holder — starts the exit and owns the destination address. For the proof scenario this is the developer, not an unbriefed tester.
- A2. Ark server — present in normal operation, deliberately absent in the scenario that matters. Its absence must not block any step of an exit.
- A3. Chain source — the esplora endpoint the wallet reads and broadcasts through. This is the only counterparty an exit genuinely requires.

### Exit states

```mermaid
stateDiagram-v2
    [*] --> Normal
    Normal --> Exiting: holder starts an exit
    state Exiting {
        [*] --> Processing
        Processing --> AwaitingDelta: exit txs confirmed
        AwaitingDelta --> Claimable: exit delta elapsed
        Claimable --> Claimed: claim broadcast and confirmed
    }
    Exiting --> Normal: every VTXO Claimed
    Normal --> [*]
```

While in `Exiting` the wallet accepts no sends or receives, keeps the funding intent disarmed, and resumes from its persisted state on every app open. Leaving `Exiting` is the only exit from the mode; there is no path back to `Normal` that does not pass through `Claimed`.

### Requirements

**Exit lifecycle**

- R1. A wallet holding VTXOs can start a unilateral exit with no reachable Ark server.
- R2. A started exit advances through the exit states whenever the app is open, without further user action.
- R3. Exit state survives app termination and resumes on the next open.
- R4. A started exit cannot be cancelled; it terminates only at `Claimed`.
- R5. An exit covers the wallet's whole VTXO set, not a selected subset.

**Wallet mode and guards**

- R6. While an exit is in flight the wallet reports an exiting state, and the home surface shows it with the off-chain, in-flight, and on-chain amounts distinguished.
- R7. While an exit is in flight, sending and receiving are unavailable.
- R8. While an exit is in flight the funding intent stays disarmed, so no on-chain funds are boarded back into a wallet that is leaving.
- R9. When the last VTXO reaches `Claimed`, exiting mode ends and the wallet becomes an ordinary wallet with an on-chain balance and no VTXOs, able to board again.

**On-chain send**

- R10. A wallet with an on-chain balance can send to a holder-supplied on-chain address.
- R11. On-chain send is available whenever an on-chain balance exists, not only after an exit.
- R12. An on-chain send names its miner fee before the holder confirms it.

**Honest figures**

- R13. The exit screen's amount, miner fee, and time-to-spendable are derived from wallet and network state rather than hardcoded.

**Proof**

- R14. A headless drill runs board → exit → claim → withdraw against the deployed stack and prints each state transition.
- R15. The drill reaches `Claimed` with captaind stopped.
- R16. The drill is re-runnable, and when its stack is unreachable it skips visibly rather than passing silently.

**Stalled exits**

- R17. An exit that cannot progress is reported as stalled, with the reason, and the wallet stays in exiting mode.
- R18. Every app open retries a stalled exit. No user action is needed to resume it, and none is offered to abandon it.

### Key Flows

- F1. Exit with the server gone
  - **Trigger:** A1 starts an exit while A2 is unreachable.
  - **Actors:** A1, A3
  - **Steps:** The wallet opens despite a failed Ark handshake; the exit is started for the whole VTXO set; exit transactions are signed, broadcast and confirmed through A3; the exit delta elapses; the claim is broadcast and confirmed.
  - **Outcome:** Funds are confirmed in the wallet's on-chain balance and exiting mode has ended.
  - **Covered by:** R1, R2, R4, R5, R9

- F2. Resume after relaunch
  - **Trigger:** A1 force-quits the app while an exit is between `Processing` and `Claimable`.
  - **Actors:** A1
  - **Steps:** The app is relaunched; the wallet loads its persisted exit state; the exiting surface appears before any other wallet state; progress resumes from where it stopped.
  - **Outcome:** No progress is lost and no user action is needed to resume.
  - **Covered by:** R3, R6

- F3. Withdraw after exit
  - **Trigger:** A1 has an on-chain balance following a completed exit.
  - **Actors:** A1, A3
  - **Steps:** A1 supplies a destination address; the wallet shows the miner fee; A1 confirms; the transaction is broadcast through A3.
  - **Outcome:** Funds leave the app for an address A1 controls.
  - **Covered by:** R10, R11, R12

### Acceptance Examples

- AE1. **Covers R1.** Given captaind is stopped and the wallet holds VTXOs, when the holder starts an exit, then the exit starts and progresses rather than failing on a server error.
- AE2. **Covers R3, R6.** Given an exit is in `AwaitingDelta`, when the app is force-quit and reopened, then the wallet reports exiting state and resumes progressing without user action.
- AE3. **Covers R7, R8.** Given an exit is in flight, when the holder opens the deposit screen and sends on-chain funds to the address shown, then those funds are not boarded while the exit is in flight.
- AE4. **Covers R4.** Given an exit has reached `Processing`, when the holder looks for a way to stop it, then no cancel control is offered.
- AE5. **Covers R9.** Given the last VTXO reaches `Claimed`, when the holder returns to home, then the wallet reports a normal state with an on-chain balance, no VTXOs, and boarding available again.
- AE6. **Covers R16.** Given the deployed stack is unreachable, when the drill runs, then it reports a skip rather than a pass.
- AE7. **Covers R17, R18.** Given an exit cannot progress, when the holder opens the app, then the wallet reports the exit as stalled with its reason and retries it, and offers no way to leave exiting mode.

### Success Criteria

- The property is demonstrable: captaind is stopped on the deployed stack, an exit is run from a device, and sats arrive at an address the holder named. Any step that needs the Ark server is a failure of this criterion.
- The property is repeatable without a device: the drill runs the same path unattended and asserts its terminal state.
- The property is drivable by hand: the holder can start an exit, watch it progress, and see it claim, without reading logs to know what is happening.
- `docs/liveness-envelope.md` can be rewritten to stop listing exit as a missing fallback.

### Scope Boundaries

**Deferred for later**

- Channel exit — force-close resolution through the exit path, once a shipping core holds channels.
- Background progression while the app is closed (issue #28). Exit remains bounded by how often the holder opens the app.
- Comprehension for an unbriefed tester. The bar here is that the path works and re-runs, not that it explains itself to a stranger.
- Selecting which VTXOs to exit, and coin control or amount selection on the on-chain send.

**Outside this work's shape**

- Any cancel, abort, or undo affordance. This is a decision, not a deferral — see Key Decisions.

<!-- ce-section: work-relationships -->
### How This Work Fits Together

This plan owns one area: VTXO exit through to a spendable on-chain balance, plus the on-chain send that makes that balance reachable. The breakdown below is the current understanding, not a committed roadmap; a later plan may revise, split, or discard any of it.

- Channel exit
  - Depends on a shipping core exposing channels at all — neither the FFI nor the stock gateway does today.
  - Shares the exit engine and the exiting-mode state this plan establishes.
  - Still to decide: whether an exit should be refused while a channel exists, or should force-close it.
- Background progression (issue #28)
  - Enables an exit to advance without the holder opening the app, shortening the window this plan leaves open.
  - Can proceed independently of this plan.
- Tester-facing comprehension
  - Depends on this plan's mode and surfaces existing first.
  - Can proceed independently once they do.

### Dependencies / Assumptions

- The exit engine comes from the pinned bark fork (`rust/fork-pins.toml`), whose `bark/src/exit/mod.rs` provides whole-wallet exit, progression, and claiming. A pin bump can change exit behavior, which is the reason R14–R16 keep a standing drill.
- `Wallet::open` tolerates a failed Ark handshake — it logs a warning and proceeds with no server (`bark/src/lib.rs:938-947` in the pinned fork). R1 rests on this.
- Exit state is loaded by `Wallet::open_with_onchain`, not by `Wallet::open`. `rust/lark-ffi/src/wallet.rs:100` currently calls the latter, so persisted exits are invisible after relaunch. R3 cannot hold until this changes; it is a prerequisite, not an option.
- The FFI exposes no exit surface and no on-chain send today (`rust/lark-ffi/src/wallet.rs`), so both are new across the whole seam.
- Assumed: mutinynet's `vtxo_exit_delta` of 144 blocks (~72 minutes, per `docs/liveness-envelope.md`) sets the floor on how fast a drill run can complete.

### Outstanding Questions

**Deferred to Planning**

- What threshold marks an exit as stalled rather than merely slow (R17).
- Whether the drill is a binary in the Rust crate or an opt-in test in the Kotlin live lane.
- How often exit progression is driven while the app is open, and whether it shares the existing maintenance cadence.
- Whether the on-chain send offers a fee-rate choice or a single default.
- Where the exiting-state surface sits on the home screen.

### Sources / Research

- `composeApp/src/commonMain/kotlin/xyz/lark/app/ui/screens/settings/ExitScreen.kt` — the screen exists; amount, fee, and readiness figures are literals.
- `composeApp/src/commonMain/kotlin/xyz/lark/app/state/AppStateMachine.kt:275` — `startExit()` disarms the funding intent and navigates home; no exit is performed. The surrounding comments document the board-during-exit hazard that R8 preserves.
- `composeApp/src/commonMain/kotlin/xyz/lark/app/core/LarkCore.kt:40-44` — `channels` stays null on every core except the channel-fork gateway.
- `rust/lark-ffi/src/wallet.rs:71-121` — `open_wallet` calls `Wallet::open`; the public surface has no exit and no on-chain send.
- `bark/src/exit/mod.rs` in the pinned fork — whole-wallet and per-VTXO exit, progression, and claiming.
- `bark/src/lib.rs:900-975` in the pinned fork — `Wallet::open` server tolerance; `Wallet::open_with_onchain` exit-state loading.
- `docs/liveness-envelope.md` — exit delta, worst-case exit timing, and the standing note that exit's absence makes server uptime part of the envelope.
- `rust/fork-pins.toml` — the bark pin the exit engine comes from.

---

## Planning Contract

### Key Technical Decisions

- KTD-1. **Exiting is a wallet-level state, not a screen.** (session-settled: user-directed — chosen over a foreground progress screen and a two-visit release flow: only a wallet-level mode resumes on every app open and structurally prevents boarding back into a wallet that is leaving.) Implements Key Decision 1. The mode lives in `AppStateMachine` as machine state derived from the exit capability's reported status, not as a route.

- KTD-2. **No cancel; exiting mode ends at `Claimed`.** (session-settled: user-approved — chosen over cancel-before-broadcast and over retiring the wallet: a transaction in the mempool cannot be recalled, so a stop control after that point would misrepresent what the app can do.) Implements Key Decision 2. No unit exposes an abort path, and `WalletExit` has no cancel member — the absence is enforced by the interface, not by UI omission.

- KTD-3. **Claims land in the wallet's own on-chain address; withdraw is a separate general send.** (session-settled: user-directed — chosen over carrying a destination address through the exit and over a sweep-only button.) Implements Key Decision 3. **Planning conflict, recorded and proceeding as settled:** `Exit::drain_exits` in the pinned fork takes a destination `Address` and builds the claim PSBT directly to it, so bark can reach a holder-named address in one transaction. Routing claims into the app's own on-chain wallet first therefore costs one extra on-chain transaction and its miner fee per exit. This is a cost, not a defect — the decision buys a reusable send path that also rescues stuck boards — but the extra fee is real and belongs on the record.

- KTD-4. **Channel exit is out of scope.** (session-settled: user-directed — chosen over covering channel force-close through the same path: no shipping core exposes channels and VTXOs landing on-chain is sufficient proof.) Implements Key Decision 4. `Exit::progress_exits` is called through the library path that passes `None` for the channel driver, leaving `ChannelBridgeTx`/`ChannelCommitment`/`ChannelSwept` inert.

- KTD-5. **Drill first, and the drill is kept.** (session-settled: user-approved — chosen over building the product arc first and over treating the drill as scaffolding.) Implements Key Decision 5. U1–U4 land and prove the mechanism before any seam or UI work begins.

- KTD-6. **A stalled exit is reported and retried, never abandoned.** (session-settled: user-directed — chosen over automatic fee escalation, unlocking the wallet after N failures, and exposing raw txids.) Implements Key Decision 6.

- KTD-7. **Exit is a capability interface beside `LarkCore`, not a member of it.** `LarkCore` is the universal seam every core implements; exit is available only to a core that holds keys and an on-chain wallet. `OnchainFunding` already establishes the pattern — a nullable capability injected into `AppStateMachine` alongside the core — so `WalletExit` follows it. Folding exit onto `LarkCore` would force `FakeLarkCore` and both gateway cores to carry members they cannot honour.

- KTD-8. **A dedicated exit watcher, modelled on the funding watcher, drives progression.** `startFundingWatcher()` in `composeApp/src/commonMain/kotlin/xyz/lark/app/state/AppStateMachine.kt` is the precedent: a scoped job that polls while a condition holds. The exit watcher is separate rather than folded in because the two have opposite lifecycles — the funding watcher must be *disarmed* while exiting (R8), so sharing a loop would couple a guard to the thing it guards.

- KTD-9. **The drill is a Rust binary, not a Kotlin live-lane test.** Resolves the open area. The exit path is entirely inside the crate; a Kotlin test would add the FFI and Gradle surface to every debugging round without testing anything the binary cannot. The binary also runs against a stopped captaind without a simulator, which is the scenario that matters.

- KTD-10. **Stalled means three consecutive progress passes returning an error for the same VTXO.** Resolves the open area. `ExitProgressStatus` carries `error: Option<ExitError>` per VTXO, so the count is available without inventing bookkeeping. Three passes rather than one avoids reporting a stall for a single transient chain-source failure.

- KTD-11. **On-chain send uses one fee rate from the chain source; no picker.** Resolves the open area. `OnchainWallet::send` takes a `FeeRate`, and mutinynet's fee market makes a chooser noise rather than control. R12 requires the fee be *shown* before confirmation, which is satisfied without making it adjustable.

### High-Level Technical Design

Exit crosses every layer of the seam. The capability interface is what keeps `LarkCore` untouched.

```mermaid
flowchart TB
    UI[Exit screen and home exiting surface]
    ASM[AppStateMachine: exiting mode + exit watcher]
    WE[WalletExit capability]
    OF[OnchainFunding capability]
    LC[LarkCore seam]
    DEL[LarkCoreDelegate: callback-shaped boundary]
    FFI[lark-ffi LarkWallet]
    BARK[bark Exit + OnchainWallet]
    DRILL[exit-drill binary]

    UI --> ASM
    ASM --> WE
    ASM --> OF
    ASM --> LC
    WE --> DEL
    DEL --> FFI
    FFI --> BARK
    DRILL --> FFI
```

The drill enters at the FFI, below the delegate boundary, which is what lets it run with no simulator and no Kotlin.

### Assumptions

Recorded because this plan was enriched headlessly; each is a planning bet, not a user decision.

- The exiting surface replaces the home screen's balance and action area rather than appearing as a banner above it, matching the wallet-mode framing of KTD-1.
- Exit progression polls on a fixed cadence independent of the funding watcher's adaptive one, since exit has no equivalent of "nothing has arrived yet".
- The drill targets the Fly stack described in `docs/gateway/local-mutinynet.md` and reads its endpoints from environment variables rather than hardcoding them.
- `Exit::progress_exits` is safe to call when no exit is in flight and returns without effect, so the watcher does not need a separate "is there an exit" probe before each pass.

### Sequencing

U1 → U2 → U3 → U4 prove the mechanism in Rust with no app involved. U5 opens the seam and can start once U2 and U3 have landed their FFI surface. U6 depends on U5 only and is testable against the fake. U7 carries the new surface to iOS. U8 is last because it consumes everything below it.

---

## Implementation Units

### U1. Load exit state when the wallet opens

- **Goal:** A persisted in-flight exit is visible after the process restarts.
- **Requirements:** R3
- **Dependencies:** none
- **Files:** `rust/lark-ffi/src/wallet.rs`
- **Approach:** `open_wallet` calls `Wallet::open`, which does not load exit state; `Wallet::open_with_onchain` does, by calling `exit.load(onchain)`. Switch the existing-wallet branch to the onchain-aware variant, which already has the `OnchainWallet` in scope from the line above it. The creation branch already passes the onchain wallet.
- **Patterns to follow:** the existing branch structure in `open_wallet` that chooses between open and create on `read_properties`.
- **Execution note:** Write the reopen assertion first — it fails against the current code, which is the proof the change is load-bearing.
- **Test scenarios:**
  - Opening a wallet whose database holds an exit in a non-terminal state reports that exit as in flight.
  - Opening a wallet with no exit history reports no exit and does not error.
  - Opening a wallet with a fully claimed exit reports no exit in flight.
- **Verification:** `cargo test` in `rust/lark-ffi` passes, including the new reopen coverage.

### U2. Exit lifecycle on the Rust FFI

- **Goal:** The crate can start an exit, advance it, and report where it is.
- **Requirements:** R1, R2, R4, R5, R17
- **Dependencies:** U1
- **Files:** `rust/lark-ffi/src/wallet.rs`, `composeApp/src/androidMain/kotlin/uniffi/lark_ffi/lark_ffi.kt`, `iosApp/iosApp/Generated/lark_ffi.swift`, `iosApp/FfiThreadingTests/Generated/lark_ffi.swift`
- **Approach:** Add three exported members on `LarkWallet`: start an exit for the whole wallet, advance the exit, and read exit status. Start maps to `Exit::start_exit_for_entire_wallet`; advance maps to `Exit::progress_exits` with `None` for the fee-rate override and the library path that leaves channel stages inert (KTD-4); status maps over `ExitProgressStatus` into an FFI-safe record carrying the VTXO count per state, an overall stage, and a per-exit error string. Expose no cancel member (KTD-2). The generated bindings are committed and CI diffs them against the crate, so regenerate and commit them in this unit.
- **Patterns to follow:** the existing `board_all` / `vtxo_summary` members — `#[uniffi::export(async_runtime = "tokio")]`, `Result<_, LarkError>`, plain records over crate types.
- **Test scenarios:**
  - Starting an exit on a wallet with VTXOs moves the reported stage off "none".
  - Starting an exit twice does not duplicate exits or error.
  - Starting an exit on a wallet with no VTXOs is a no-op rather than an error.
  - Advancing an exit with no exit in flight returns without effect.
  - Status reports a per-VTXO error string when the underlying progress status carries one.
  - No exported member offers cancellation or abandonment.
- **Verification:** `cargo test` passes; `bash scripts/generate-bindings.sh` leaves no diff against the committed bindings.

### U3. On-chain send on the Rust FFI

- **Goal:** The crate can spend the on-chain balance to a supplied address, and can quote the fee first.
- **Requirements:** R10, R11, R12
- **Dependencies:** none
- **Files:** `rust/lark-ffi/src/wallet.rs`, `composeApp/src/androidMain/kotlin/uniffi/lark_ffi/lark_ffi.kt`, `iosApp/iosApp/Generated/lark_ffi.swift`, `iosApp/FfiThreadingTests/Generated/lark_ffi.swift`
- **Approach:** Add an exported member that sends a named amount to an address via `OnchainWallet::send`, and one that returns the miner fee a given send would pay so the UI can show it before confirmation (KTD-11). Use the chain source's fee estimate rather than accepting a caller-supplied rate. The wallet already holds the `OnchainWallet` behind a mutex; follow the existing locking discipline.
- **Patterns to follow:** `onchain_balance` and `onchain_sync` for how the onchain mutex is taken and released.
- **Test scenarios:**
  - Sending to a valid address for less than the confirmed balance succeeds and reduces the on-chain balance.
  - Sending more than the confirmed balance fails and leaves the balance untouched.
  - Sending to a malformed address fails with a validation error rather than panicking.
  - Sending to an address on the wrong network fails.
  - A fee quote for a valid send returns a positive value; quoting does not broadcast.
- **Verification:** `cargo test` passes; bindings regenerate with no diff.

### U4. Headless exit drill

- **Goal:** A re-runnable binary proves board → exit → claim → withdraw against the deployed stack, including with captaind stopped.
- **Requirements:** R14, R15, R16
- **Dependencies:** U1, U2, U3
- **Files:** `rust/lark-ffi/src/bin/exit-drill.rs`, `docs/gateway/local-mutinynet.md`
- **Approach:** A binary that opens or creates a wallet at a supplied datadir, then walks the phases in order, printing each state transition with its block height and elapsed time. Endpoints come from environment variables (KTD-9 assumption). When the configured stack is unreachable, report a skip with an exit status distinct from both success and failure — a skip must never read as a pass, which is the failure mode `docs/solutions/test-failures/silently-skipped-test-lane-passes-ci.md` documents. Document the invocation, including the captaind-stopped variant, alongside the existing stack notes.
- **Patterns to follow:** the `uniffi-bindgen` binary for `[[bin]]` wiring; the `LARK_REQUIRE_FFI` discipline in `scripts/ci.sh` for making a skip loud.
- **Execution note:** Run this against the live Fly stack before the seam work starts — its whole purpose is to fail early, in Rust, rather than late through a phone.
- **Test scenarios:**
  - With the stack reachable and captaind running, the drill reaches a claimed exit and reports it.
  - With captaind stopped, the drill still reaches a claimed exit.
  - With the chain source unreachable, the drill reports a skip and does not report success.
  - Re-running the drill against an already-exited wallet does not report a false pass.
- **Verification:** the drill run against the Fly stack reaches a claimed exit and prints the transition log; the captaind-stopped run does the same.

### U5. WalletExit seam, fake, and contract tests

- **Goal:** The app has an exit capability it can program against, with a deterministic implementation for tests.
- **Requirements:** R2, R4, R5, R17, R18
- **Dependencies:** U2
- **Files:** `composeApp/src/commonMain/kotlin/xyz/lark/app/core/WalletExit.kt`, `composeApp/src/commonMain/kotlin/xyz/lark/app/core/FakeWalletExit.kt`, `composeApp/src/commonTest/kotlin/xyz/lark/app/core/WalletExitContractTest.kt`
- **Approach:** A capability interface with start, advance, and an observable status carrying stage, counts, and a stall signal — no cancel member (KTD-2, KTD-7). The stall signal is computed by the implementation from consecutive errored passes (KTD-10), so callers never count. The fake drives a scripted progression under virtual time so the state machine can be tested without a chain.
- **Patterns to follow:** `composeApp/src/commonMain/kotlin/xyz/lark/app/core/OnchainFunding.kt` for capability shape; `FakeLarkCore`'s injected `workDelay` for virtual-time control; the existing `LarkCoreContractTest` family for contract-test structure.
- **Test scenarios:**
  - A scripted progression reports each stage in order and terminates at claimed.
  - Advancing after the terminal stage is a no-op.
  - Three consecutive errored passes for the same VTXO raise the stall signal; two do not.
  - A stall that later clears lowers the signal without leaving the mode.
  - The interface exposes no member that ends an exit early.
- **Verification:** `./gradlew :composeApp:testDebugUnitTest` passes.

### U6. Exiting mode in the app state machine

- **Goal:** The wallet enters, holds, and leaves the exiting mode with its guards intact.
- **Requirements:** R6, R7, R8, R9, R18
- **Dependencies:** U5
- **Files:** `composeApp/src/commonMain/kotlin/xyz/lark/app/state/AppStateMachine.kt`, `composeApp/src/commonTest/kotlin/xyz/lark/app/state/AppStateMachineTest.kt`, `composeApp/src/commonTest/kotlin/xyz/lark/app/state/ExitingModeTest.kt`
- **Approach:** Replace the body of `startExit()` — which currently only disarms funding and navigates home — with entry into the exiting mode: disarm funding as it already does, then start the exit through the capability and launch the exit watcher. The watcher is a separate scoped job modelled on `startFundingWatcher()` (KTD-8), started from `init` as well so a persisted exit resumes at launch without user action. While the mode holds, send and receive are refused and the funding watcher stays down. When the capability reports the terminal stage, the mode ends and normal state resumes with boarding available again.
- **Patterns to follow:** `startFundingWatcher()` / `pollFundingOnce()` for the job-and-poll shape; the `init` block's launch-time watcher start for resume-at-launch; the existing `update {}` / `render()` discipline for state changes.
- **Test scenarios:**
  - Covers AE2. A machine constructed with an exit already in flight enters exiting mode without user action and advances it.
  - Covers AE3. While exiting, opening the deposit screen does not arm funding and no board is attempted.
  - Covers AE5. When the capability reports the terminal stage, the mode ends, the wallet reports normal state, and boarding is available.
  - Covers AE4. No machine entry point ends an exit early.
  - While exiting, a send attempt is refused and the balance is untouched.
  - Covers AE7. A raised stall signal is reported in the model and the watcher keeps advancing.
  - Entering exiting mode cancels an already-running funding watcher.
- **Verification:** `./gradlew :composeApp:testDebugUnitTest` passes.

### U7. iOS delegate transport for exit and on-chain send

- **Goal:** The new crate surface reaches the shipping iOS core.
- **Requirements:** R1, R2, R10, R12
- **Dependencies:** U2, U3, U5
- **Files:** `composeApp/src/commonMain/kotlin/xyz/lark/app/core/ffi/LarkCoreDelegate.kt`, `composeApp/src/commonMain/kotlin/xyz/lark/app/core/ffi/FfiMappers.kt`, `composeApp/src/iosMain/kotlin/xyz/lark/app/core/ffi/DelegateBackedLarkCore.kt`, `iosApp/iosApp/` Swift delegate implementation
- **Approach:** Add callback-shaped members for start, advance, status, on-chain send, and fee quote — non-suspend with completion handlers, because Kotlin/Native forbids Swift from implementing a suspend member. Map the FFI records into the seam's own types in `FfiMappers`, and lift the callbacks back into the suspending capability on the iOS side.
- **Patterns to follow:** the existing delegate members' `onResult(value, error)` / `onDone(error)` contract and the report-exactly-once rule stated in `LarkCoreDelegate`'s KDoc; the existing lifting in `DelegateBackedLarkCore`.
- **Test scenarios:**
  - Each new delegate member reports exactly once on success and exactly once on failure.
  - A reported error surfaces as a failed capability call rather than a hang.
  - Status mapping preserves stage, counts, and the stall signal.
  - A call made before the wallet is open reports an error rather than crashing.
- **Verification:** `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` succeeds and the iOS app builds.

### U8. Exit and on-chain send screens

- **Goal:** The screens tell the truth and the paths are reachable.
- **Requirements:** R6, R10, R12, R13
- **Dependencies:** U6, U7
- **Files:** `composeApp/src/commonMain/kotlin/xyz/lark/app/ui/screens/settings/ExitScreen.kt`, `composeApp/src/commonMain/kotlin/xyz/lark/app/ui/screens/settings/AdvancedScreen.kt`, `composeApp/src/commonMain/kotlin/xyz/lark/app/ui/screens/home/`, a new on-chain send screen, `composeApp/src/commonTest/kotlin/xyz/lark/app/ui/`
- **Approach:** Replace the exit screen's literal amount, miner fee, and readiness figures with values derived from wallet and network state — the fee from the same quote path U3 exposes, the readiness estimate from the exit delta against the network's block spacing rather than a fixed "about 24 hours". Add the exiting home surface showing off-chain, in-flight, and on-chain amounts with send and receive unavailable. Add the on-chain send screen: address, amount, quoted fee, confirm — reachable whenever an on-chain balance exists, not only after an exit (R11).
- **Patterns to follow:** the existing `SurfaceCard` / `ExitRow` composition in `ExitScreen.kt`; the Advanced screen's real-countdown rendering for expiry, which already computes at the network's actual block spacing.
- **Test scenarios:**
  - The exit screen renders the wallet's actual spendable amount, not a constant.
  - The exit screen's miner fee comes from the quote path and changes when the quote changes.
  - The readiness estimate is derived from the exit delta and block spacing.
  - The exiting home surface distinguishes off-chain, in-flight, and on-chain amounts.
  - Send and receive are not offered while exiting.
  - The on-chain send screen is reachable with an on-chain balance and no prior exit.
  - Confirming a send shows the quoted fee before it broadcasts.
- **Verification:** `./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug` passes and the exit flow is walkable on the simulator.

---

## Verification Contract

| Gate | Command | Applies to | Done signal |
| --- | --- | --- | --- |
| Rust unit and integration | `cargo test` in `rust/lark-ffi` | U1, U2, U3 | All tests pass, including new exit and on-chain send coverage |
| Binding drift | `bash scripts/generate-bindings.sh` then `git diff --quiet HEAD -- composeApp/src/androidMain/kotlin/uniffi` | U2, U3 | No diff — committed bindings match the crate |
| Shared and app tests | `./gradlew :composeApp:testDebugUnitTest` | U5, U6, U8 | All tests pass |
| iOS framework link | `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` | U7 | Links clean |
| Full lane | `bash scripts/ci.sh` | all | Exits 0 with `LARK_REQUIRE_FFI=1` set, so the FFI lane cannot skip silently |
| Exit drill, server up | `cargo run --bin exit-drill` in `rust/lark-ffi`, against the Fly stack | U4 | Reaches a claimed exit and prints the transition log |
| Exit drill, captaind stopped | `cargo run --bin exit-drill` in `rust/lark-ffi`, with captaind stopped | U4 | Reaches a claimed exit |

## Definition of Done

- Every requirement R1–R18 is implemented or explicitly deferred in writing.
- Acceptance examples AE1–AE7 have corresponding passing tests.
- The exit drill reaches a claimed exit with captaind stopped, and its output is recorded.
- Sats from an exit reach an address named by the holder, through the general on-chain send.
- The exit screen shows no hardcoded amount, fee, or duration.
- `bash scripts/ci.sh` passes with the FFI lane verified rather than skipped.
- `docs/liveness-envelope.md` no longer lists unilateral exit as a missing fallback.
