# lark-app

Cross-platform client for **lark** — an Ark wallet (mutinynet for now).

## Features

Status of the shipping build (iOS, keys on device). Everything checked has moved real sats on
mutinynet; everything unchecked is either stubbed or absent, and says which.

**Wallet**

- [x] Create a wallet — BIP-39 seed generated on device, stored in the Keychain
- [x] Restore from a 12-word phrase
- [x] Reopen from the Keychain on relaunch (~2s)
- [x] View and confirm backup words
- [ ] Cloud backup and recovery — the two-artifact crypto exists in the crate, no UI or transport

**Money in**

- [x] On-chain deposit address with QR
- [x] Board on-chain funds into the Ark
- [x] Ark receive code with QR
- [ ] Lightning receive — the Get paid QR carries only the Ark address ([#17])
- [ ] Buy with a card — removed; no provider exists

**Money out**

- [x] Send to an Ark address
- [x] Pay a BOLT11 invoice
- [x] Type or paste a destination
- [ ] Scan a QR — the scan screen is a viewfinder mock with a "simulate a scan" pill, no camera
- [ ] Unilateral exit — the screen exists, the action is a no-op ([#19])

**Upkeep and display**

- [x] Balance and activity read from real wallet state
- [x] Periodic maintenance while open — registers confirmed boards, claims receives, refreshes VTXOs
- [x] Health states, Advanced stats, network label
- [ ] Background refresh while the app is closed ([#28]) — the reason offline tolerance is capped
- [ ] **Real fiat rate** — `fiatRate` is a hardcoded demo constant, so every dollar figure on
      screen is invented. There is no rate source wired on any core.

**Platform**

- [x] iOS, distributed through TestFlight
- [ ] Android — the UI runs, but only in `DEMO`; the FFI adapter is blocked
- [ ] `PrivacyInfo.xcprivacy` — expect ITMS-91053 notices until it lands

Defects on the `GATEWAY` path are tracked separately ([#9]–[#16], [#32], [#35], [#36]); that
path is not what ships.

[#9]: https://github.com/ConorOkus/lark-app/issues/9
[#16]: https://github.com/ConorOkus/lark-app/issues/16
[#17]: https://github.com/ConorOkus/lark-app/issues/17
[#19]: https://github.com/ConorOkus/lark-app/issues/19
[#28]: https://github.com/ConorOkus/lark-app/issues/28
[#32]: https://github.com/ConorOkus/lark-app/issues/32
[#35]: https://github.com/ConorOkus/lark-app/issues/35
[#36]: https://github.com/ConorOkus/lark-app/issues/36

## Architecture

- **UI:** Compose Multiplatform — one UI codebase rendering natively on Android and iOS.
- **Shared logic:** Kotlin Multiplatform (`composeApp/src/commonMain`) — state machines and models, Bitkey-style.
- **Core:** the wallet engine, swappable behind a single interface. The shipping build runs the in-process Rust core, with keys on device.

```
                  Compose Multiplatform UI
                            │
                      AppStateMachine
                            │
   ═══════════════════ LarkCore ═══════════════════   ← the seam
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
      DEMO               GATEWAY               FFI   ← ships today
  FakeLarkCore      GatewayLarkCore    DelegateBackedLarkCore
        │                   │                   │
   tests only           HTTP/REST             UniFFI
   no network               │                   │
                          barkd          lark-ffi (Rust)
                    keys on the server     keys on device
                            │                   │
                            └─────────┬─────────┘
                                      │
                       captaind (Ark) + esplora (chain)
                                 mutinynet
```

Everything above the seam is platform- and engine-agnostic; everything below it is
interchangeable.

### The seam

`LarkCore` is the only thing the UI and state machines know about the wallet: `StateFlow`s
for balance, health and wallet existence, plus suspending `send`/`refresh`/`restoreWallet`.
Demo-only affordances live on a separate `DemoControls` interface, so nothing above the seam
can depend on a capability real money does not have. One contract suite runs against every
implementation, which is what makes the swap safe.

| Mode (`CoreConfig.mode`) | Implementation | Keys | Talks to |
|---|---|---|---|
| `FFI` — **ships today** | `DelegateBackedLarkCore` | **on device** | captaind + esplora directly |
| `GATEWAY` | `GatewayLarkCore` | on the server | barkd over REST |
| `DEMO` — **tests only** | `FakeLarkCore` | none | nothing — invented data, no network |

`DEMO` is not a product mode. `FakeLarkCore` is the fixture everything above the seam is tested
against, and the reference implementation the two real cores were written to mirror; it is
runnable as an app mostly as a side effect. Treat it as a test double that happens to compile
into the binary.

Mode is a compile-time constant (`core/CoreConfig.kt`), not a runtime setting. Editing it to
`DEMO` for local work is fine — it is currently the only way to run the Android app — but
`ShippedCoreConfigTest` fails CI if that edit is ever committed, because the fake's confident
balance and payable-looking receive code are indistinguishable from real funds at runtime.
Every other mode fails loudly when it cannot reach its engine; `DEMO` succeeds at lying.

### Platform split

`commonMain` holds the UI, state, seam and models; platform source sets hold only what cannot
be shared.

**iOS** runs the FFI path: a Swift delegate owns the Rust wallet handle, the mnemonic lives in
the Keychain, and the datadir is excluded from backup. Swift cannot implement a Kotlin
`suspend` member ([KT-38974]), so the bridge is a completion-handler protocol that
`DelegateBackedLarkCore` lifts back into coroutines.

**Android** runs `DEMO` today — the FFI adapter is blocked on an off-thread hang in bark's
wallet-open path (`docs/ffi/kotlin-bindings-status.md`).

### Rust core

`rust/lark-ffi` is a UniFFI crate wrapping the bark wallet and the LDK fork: wallet open,
balances, addresses, boarding, sends, movements, backup crypto. Bindings are generated and
committed, with a CI drift check so they cannot diverge from the crate. Both upstream forks
are pinned by SHA in `rust/fork-pins.toml` and expected as siblings of this repo (`../bark`,
`../rust-lightning`), so a fork bump is a one-line reviewable diff. iOS links the crate as an
XCFramework built by `scripts/build-xcframework.sh` (gitignored — build it before archiving).

[KT-38974]: https://youtrack.jetbrains.com/issue/KT-38974

## Infrastructure

The app runs against **mutinynet**, a custom signet with 30-second blocks. At runtime it needs
two things:

- **An Ark server** (`captaind`) — participates in signing, so no local stand-in can
  substitute for it. Operations that need one either reach a real server or honestly fail.
- **A chain source** (esplora) — genesis, tip and fee data. Public `mutinynet.com/api`; we do
  not host one.

We self-host the Ark side: `captaind` (a fork with LDK enabled), the mutinynet `bitcoind` it
uses as its RPC backend, and a postgres for its datastore. `barkd`, the wallet daemon behind
`GATEWAY` mode, runs alongside them. Deployment configs live in `deploy/`, runbooks in
`docs/deploy/`.

Things worth knowing before touching any of it:

- **bitcoind must be benthecarman's Inquisition fork.** Only it implements
  `-signetblocktime`; without that the node wedges permanently at the first
  difficulty-retarget boundary.
- **captaind cannot be allowed to idle down.** LDK has to keep monitoring channels or funds
  are at risk, and its volume holds the ChannelMonitors — lose it and you lose channel funds.
- **captaind's gRPC is not TLS yet.** Terminating TLS for gRPC in front of an h2c backend
  needs a sidecar; that is a tracked follow-up, and until it lands the app's
  TLS-to-non-loopback rule is not satisfiable here.
- **barkd has no authentication at all** — no token, no toggle. Anyone who learns the hostname
  can drain it. Accepted only because the funds are mutinynet; it is the remaining blocker on
  the gateway path and not a shape to reuse.
- **Offline tolerance is a server setting.** `vtxo_lifetime` is ~21 days at 30s blocks — how
  long a tester can ignore the app before the server can sweep, since nothing refreshes while
  it is closed. The field is a `u16`, so ~22.75 days is the hard ceiling on this chain; past
  that the fix is background refresh, not a bigger number (`docs/liveness-envelope.md`).

### CI and distribution

CI runs `scripts/ci.sh` on a macOS runner for every PR: clone the pinned forks, build and test
the Rust crate, check binding drift, run detekt and the shared JVM tests, then build both app
targets. A cold run is ~23 minutes.

iOS ships to TestFlight via `scripts/testflight.sh`. Internal testing only — App Store
guideline 3.1.5(b) expects wallets from an organization account.

## Prerequisites

- JDK 17+ (this repo is built with 21) — set `JAVA_HOME` if it is not on your path
- Android SDK (compileSdk 35) — `ANDROID_HOME` or a `local.properties` with `sdk.dir`
- Xcode 16+ (iOS)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) — the Xcode project is generated, not committed

## Building

### Android

```sh
./gradlew :composeApp:assembleDebug
# APK at composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### iOS

```sh
cd iosApp
xcodegen generate
xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'generic/platform=iOS Simulator' build
```

Or open the generated `iosApp.xcodeproj` in Xcode and run. The Kotlin framework is compiled by a pre-build phase (`embedAndSignAppleFrameworkForXcode`).

### Tests and lint

```sh
./gradlew :composeApp:testDebugUnitTest   # shared tests on JVM
./gradlew detekt                          # static analysis
```

`scripts/ci.sh` runs the full check set (both platforms plus the Rust leg) — the same script CI
runs, so it is the way to reproduce a CI failure locally.
