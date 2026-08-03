# Kotlin bindings for `lark-ffi` — what works, and the one thing that blocks `FfiLarkCore`

Status as of 2026-07-31. Companion to `docs/plans/2026-07-31-001-feat-ffi-lark-core-kotlin-plan.md`
(M2 unit U2) and the parent plan `docs/plans/2026-07-30-001-feat-m2-ffi-core-cloud-backups-plan.md`.

## Build prerequisites

Beyond a Rust toolchain and the two pinned sibling fork checkouts, building the crate needs
**`protoc`** — `bark-server-rpc`'s build script compiles the captaind protos with prost-build.
`brew install protobuf` on macOS, `apt-get install -y protobuf-compiler` on Debian/Ubuntu, or point
`PROTOC` at an existing binary. `scripts/build-rust.sh` checks for it up front, because without the
check cargo fails deep inside a build script with a panic that reads like a crate bug.

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

### Measured on-device 2026-08-02: not a host artifact — it reproduces

The open question above ("may well be specific to the JVM unit-test host") is now **closed, and the
answer is no**. `FfiAsyncThreadingInstrumentedTest` runs the same three shapes on a real Android
runtime (emulator, android-35, arm64-v8a, API 26 build of the crate) against `DeviceStubEsplora`:

| Call shape, on device | Result |
| --- | --- |
| `runBlocking { openWallet(…) }` on the instrumentation thread | **completes** |
| `scope.launch { openWallet(…) }` on `Dispatchers.IO` | hangs — failed the 90s deadline |
| `executor.execute { runBlocking { openWallet(…) } }` | hangs — failed the 90s deadline |

Identical to the host, including the signature: in both hanging cases the stub logged
`/block-height/0`, so the chain-source request went out **and was answered**, and the call still
never returned. The only variable between the passing and failing shapes is which thread entered
the call — both failing shapes use the same `runBlocking`/dispatcher machinery that works on the
instrumentation thread.

This rules out the JVM host, Robolectric, desktop JNA natives, and the JDK's `HttpServer` stub as
causes.

The NDK note in the original text is also stale: NDK 28.1 and an arm64 system image were already
installed (under the Homebrew `android-commandlinetools` root, not `~/Library/Android/sdk`); only
`cargo-ndk` had to be added.

### Measured on iOS 2026-08-02: the same crate completes — so this is a *bindings* bug

The natural reading of the Android result is "crate-side". **It is not.** `iosApp/FfiThreadingTests`
runs the same experiment through the other foreign binding — Swift, no JVM, no JNA, no Kotlin
continuation shim — against the same crate revision, on the iOS 26 simulator:

| Call shape, iOS simulator | Result |
| --- | --- |
| `Task { try await openWallet(…) }` | **completes** (75.3s) |
| `Task.detached { try await openWallet(…) }` | **completes** (75.7s) |

`Task.detached` is the closest Swift analogue of the Kotlin shape that hangs: no inherited context,
driven by a thread unrelated to the caller. More decisive still, Swift has **no** equivalent of
`runBlocking` — nothing in either shape lets the calling thread drive the future, and both complete
anyway.

So the Rust future *is* driven to completion after its HTTP response, and the foreign continuation
callback *is* invoked — when the foreign side is Swift. The fault is therefore in the **UniFFI 0.28
Kotlin/JNA async path**, not in the crate's `async_runtime = "tokio"` usage. The `src/lib.rs`
comment ("UniFFI's tokio integration owns the runtime, so the crate does not build one") is not the
culprit it looked like.

Note the two lanes differ in chain source — Android used `DeviceStubEsplora`, iOS the real
mutinynet esplora — but that is not the discriminator: the Android control passes against the stub,
so the stub can serve a complete wallet creation. The 75s per iOS test is the real esplora, not a
symptom.

**What this changes:** the iOS half of the seam (parent plan U3) is unblocked — the Swift binding
drives the crate correctly from a detached task, which is the shape an iOS adapter needs. The
Kotlin adapter stays blocked, but on a much smaller and better-located problem.

## What to do next

1. ~~**Reproduce on a device or emulator.**~~ **Done 2026-08-02 — it reproduces.** See the on-device
   table above. Re-run with `./gradlew :composeApp:connectedDebugAndroidTest` after building the
   native library for the device:
   ```sh
   export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.1.13356709
   cd rust/lark-ffi && cargo ndk -t arm64-v8a --platform 26 \
     -o ../../composeApp/src/androidMain/jniLibs build
   # then strip: the debug .so is ~410MB unstripped, ~32MB after llvm-strip --strip-unneeded
   ```
2. ~~**Investigate crate-side.**~~ **Ruled out 2026-08-02 by the iOS run above** — the same crate
   completes from a detached Swift task. Investigate the **Kotlin async binding** instead:
   `uniffiRustCallAsync` in the generated `lark_ffi.kt`, the `UniffiRustFutureContinuationCallback`
   it installs, and whether that JNA callback is invoked when the waker fires on a Rust-owned
   thread. The measured "zero exceptions from `Native.setCallbackExceptionHandler`" says it is
   never invoked at all, which is consistent with a callback the JVM cannot dispatch to — e.g. an
   unattached native thread, or a callback object that has to stay strongly referenced.
   Reproduce with:
   ```sh
   ./gradlew :composeApp:connectedDebugAndroidTest   # Android: two shapes hang
   cd iosApp && xcodegen generate && xcodebuild test -project iosApp.xcodeproj \
     -scheme FfiThreadingTests -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
   ```
3. **The iOS adapter is no longer blocked.** U3 can proceed on the Swift binding as designed; only
   the Kotlin/Android adapter waits on step 2.
4. **A draft `FfiLarkCore` + its contract lane exist** and compile against these bindings. They were
   deliberately not committed: with the async bridge unverified, committing a wallet core whose
   contract suite fails 9/9 would claim a working core that does not work. The draft is worth
   recovering once step 1 or 2 resolves — the adapter's decisions (poll loop, honest absences,
   BOLT11-only send, seed-redacting config, per-test datadirs) all still hold.
