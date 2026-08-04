# AGENTS.md

**lark** — an Ark wallet, Kotlin Multiplatform with a Compose Multiplatform UI. Runs on
**mutinynet**, a custom signet with **30-second blocks**. Test coins only.

## The shape of it

Everything above the wallet engine talks to one interface, `LarkCore`
(`composeApp/src/commonMain/kotlin/xyz/lark/app/core/LarkCore.kt`). Three implementations:

| Core | What it is |
| --- | --- |
| `FakeLarkCore` | the design prototype's constants; no network, no funds |
| `GatewayLarkCore` | HTTP to a barkd daemon that holds the wallet |
| `DelegateBackedLarkCore` (`iosMain`) | bark **in-process** via the `rust/lark-ffi` UniFFI crate — the seed lives in the device Keychain |

`CoreConfig.mode` picks one at compile time. **`CoreMode.FFI` is iOS-only** — the Android adapter is
blocked on an off-thread hang in bark's wallet-open path, so an Android build in that mode fails
fast at the composition root with an explanation. Switch to `DEMO` to run Android.

Swift cannot implement a Kotlin `suspend` member (KT-38974), so the iOS core reaches Rust through a
non-suspend, completion-handler `LarkCoreDelegate` that Swift implements and `iosMain` lifts back
into the suspending seam.

## Building and checking

`scripts/ci.sh` is the single source of truth for the check set; CI runs exactly it. Individually:

```sh
./gradlew detekt :composeApp:testDebugUnitTest   # shared tests + static analysis (needs JDK 21)
bash scripts/build-rust.sh                       # host build + Rust tests
cd iosApp && xcodegen generate && xcodebuild -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

Two things that will bite a fresh checkout:

- **The Rust core's XCFramework is a build artifact, not in the repo** (~216MB). The iOS app links
  it, so run `scripts/build-xcframework.sh` before any iOS build. `ci.sh` builds a simulator-only
  debug slice for its own app build; a device archive needs the full script.
- **The forks are sibling checkouts** pinned in `rust/fork-pins.toml`. `scripts/clone-forks.sh` puts
  bark and rust-lightning beside this repo at the pinned SHAs; the crate path-depends on them.

The UniFFI bindings — Kotlin under `composeApp/src/androidMain/kotlin/uniffi/` and the Swift glue at
`iosApp/iosApp/Generated/` — are **committed and drift-checked**. Never hand-edit them; regenerate
with `scripts/generate-bindings.sh` (Kotlin) or `scripts/build-xcframework.sh` (Swift) and commit
the diff.

## Conventions worth knowing before changing money or UI code

- **Never render a fabricated number.** An unknown shows an em-dash, never `0` — "0 VTXOs" beside a
  real balance is a lie, and a zero exit reserve reads as an assertion. Nullable model fields carry
  the unknown.
- **Acknowledgement is not settlement.** A payment the server accepted can still fail; only say
  "Sent" when settlement is observable.
- **Block counts are not durations here.** 30-second blocks mean a config written for Bitcoin's
  10-minute target is off by 20x. Anything converting heights to human time takes the spacing
  explicitly.
- **The iOS host sets `.ignoresSafeArea(.keyboard)`**, so Compose owns keyboard insets: a screen
  with a field near the bottom needs `imePadding()`, and a multi-line field needs an explicit
  dismissal.

## Where the written-down knowledge lives

- `docs/solutions/` — documented solutions to past problems (bugs, conventions, workflow
  learnings), organised by category with YAML frontmatter (`module`, `tags`, `problem_type`,
  `root_cause`). Searchable, and relevant when implementing or debugging in an area one of them
  covers.
- `CONCEPTS.md` — shared domain vocabulary (VTXO, Board, Ark server, the verification lanes).
  Relevant when orienting to the codebase or reading the docs above.
- `docs/liveness-envelope.md` — how long a wallet may sit closed before the server can sweep it,
  and why. Read before changing refresh, expiry, or captaind parameters.
- `docs/plans/` — planning artifacts. `docs/gateway/` — vendored barkd API specs and runbooks.
