# Kotlin bindings for `lark-ffi` — what works, and the one thing that blocks `FfiLarkCore`

Status as of 2026-07-31. Companion to `docs/plans/2026-07-31-001-feat-ffi-lark-core-kotlin-plan.md`
(M2 unit U2) and the parent plan `docs/plans/2026-07-30-001-feat-m2-ffi-core-cloud-backups-plan.md`.

## What is landed and verified

- **JNA + build wiring.** `net.java.dev.jna` on `androidMain` (`@aar`, for device natives) and
  `androidUnitTest` (plain jar, for the host JVM's desktop natives). `jna.library.path` is set on
  test tasks to the absolute path of `rust/lark-ffi/target/debug`. Generated bindings are excluded
  from detekt.
- **Generated Kotlin bindings**, committed at `composeApp/src/androidMain/kotlin/uniffi/lark_ffi/`,
  regenerated only by `scripts/generate-bindings.sh`.
- **Seam contract suite split by funding requirement.** `LarkCoreLifecycleContractTest` holds
  everything a zero-balance wallet can satisfy; `LarkCoreContractTest` adds the money-bearing half.
  The three existing runners are unchanged and still pass 11 tests each.
- **A real-Rust characterization lane** (`FfiHostLibraryTest`, 5 tests, green) covering library
  load, typed errors, wallet creation, and which operations need which backend.

## Facts measured against the real crate

These were established empirically this session; they correct or sharpen what the plan assumed.

| Question | Answer |
| --- | --- |
| Do the crate's `async` exports arrive as Kotlin `suspend fun`s? | Yes — `openWallet`, `balanceSats`, `refresh`, `mintAddress`, `depositAddress`, `sendBolt11`, `board`, `movements`. |
| Is wallet creation server-free? | **Only with respect to the Ark server.** `force = true` skips the Ark probe, but bdk's onchain wallet needs a chain source, so an unreachable esplora fails creation. |
| How large is the chain-source surface? | Three endpoints: `GET /block-height/0`, `GET /blocks/tip/height`, `GET /fee-estimates`. `StubEsplora` answers exactly these and 404s the rest. |
| Is the genesis hash validated? | Yes, against the configured network. Mutinynet shares signet's genesis (`00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6`) — a custom signet challenge changes block validity, not the genesis block. |
| What works with no Ark server? | `balanceSats` (0), `movements` (empty), `depositAddress` (real `tb1p…`), `fingerprint`. |
| What needs the Ark server? | `mintAddress` ("You should be connected to Ark server"), and every money-bearing operation. |
| Do the forks need a clone credential in CI? | **No.** `gitlab.com/ConorOkus/bark` and `github.com/instagibbs/rust-lightning` both clone unauthenticated, and both `rust/fork-pins.toml` SHAs resolve. |

## The blocker: async FFI calls only complete on the JUnit main thread

`FfiLarkCore` is designed (plan KTD-3) to launch `openWallet` into an injected `CoroutineScope` and
run a poll loop, because the seam's `createWallet()`/`restoreWallet()` are synchronous while the
crate's operations are `suspend`. **That does not currently work in the JVM unit-test environment.**

Measured behavior, 11 controlled variants, fully reproducible:

| Call shape | Result |
| --- | --- |
| `runBlocking { openWallet(…) }` on the JUnit test thread | **completes** |
| `runBlocking(Dispatchers.IO) { openWallet(…) }` from the test thread | **completes** |
| `runBlocking { withContext(Dispatchers.IO) { openWallet(…) } }` | **completes** |
| `scope.launch { openWallet(…) }` on `Dispatchers.IO` or `Dispatchers.Default` | hangs forever |
| `scope.launch { runBlocking { openWallet(…) } }` | hangs forever |
| `withContext(dedicatedSingleThread) { runBlocking { … } }` | hangs forever |
| `executor.execute { runBlocking { openWallet(…) } }` (plain thread) | hangs forever |
| dedicated thread that also performs the library's first call | hangs forever |

In every hanging case the esplora request **is** made and answered — the stub logs
`GET /block-height/0` — and then nothing further happens. Ruled out along the way:

- Not the dispatcher: `Dispatchers.IO` completes fine under `runBlocking`.
- Not call ordering or leaked wallet handles: the launched variant hangs even when run first.
- Not stub-server threading: single-threaded and pooled executors behave identically.
- Not duplicate JNA: the unit-test runtime classpath resolves exactly one, `jna:5.14.0`.
- Not a swallowed callback exception: with `Native.setCallbackExceptionHandler` installed, **zero**
  exceptions are reported — the continuation callback is never invoked at all.

The last point is the diagnostic one. Since the callback never fires, the Rust future is not
completing after its HTTP response is delivered; the Kotlin side is waiting correctly. That points
at how UniFFI 0.28's `async_runtime = "tokio"` integration drives the reactor for this crate, which
is crate-side work (parent plan U1), not something the Kotlin adapter can fix.

Note this may well be **specific to the JVM unit-test host**. The launch-into-a-scope pattern is
what UniFFI apps normally do on Android, and this repo cannot currently build for a device (no
Android NDK / `cargo-ndk` available here — see the plan's Scope Boundaries), so on-device behavior
is untested rather than known-bad.

## What to do next

1. **Reproduce on a device or emulator.** Build the `.so` via `BUILD_ANDROID=1 scripts/build-rust.sh`
   with an NDK present and drive `openWallet` from a launched coroutine. If it completes there, the
   blocker is a JVM-host artifact and the contract lane needs a different shape (or the adapter is
   verified on-device and the JVM lane stays at the characterization level landed here).
2. **If it reproduces off the main thread everywhere**, investigate crate-side: how the UniFFI tokio
   integration is configured, whether the runtime is multi-threaded, and whether the future is
   spawned onto it rather than merely polled inside `enter()`.
3. **A draft `FfiLarkCore` + its contract lane exist** and compile against these bindings. They were
   deliberately not committed: with the async bridge unverified, committing a wallet core whose
   contract suite fails 9/9 would claim a working core that does not work. The draft is worth
   recovering once step 1 or 2 resolves — the adapter's decisions (poll loop, honest absences,
   BOLT11-only send, seed-redacting config, per-test datadirs) all still hold.
