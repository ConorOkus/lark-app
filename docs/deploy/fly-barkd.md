# Hosting barkd on Fly

Moves the bark wallet daemon off the developer laptop and onto Fly, alongside the mutinynet Ark
stack (`docs/deploy/fly-mutinynet-stack.md`). Build artifacts live in `deploy/fly/`.

Topology: `lark-barkd` → captaind at `77.83.143.203:3535` (Ark) and `https://mutinynet.com/api`
(chain source). Both are public, so barkd needs nothing from Fly's private network.

## ⚠️ This endpoint is an unauthenticated, spendable wallet

barkd has **no authentication of any kind**. Its entire option set is `--datadir`, `--port`,
`--host` — there is no token, no TLS termination of its own, and no `--no-auth` toggle to reverse
(that flag belonged to stock barkd 0.4.0). The daemon holds the wallet seed, exposes
`GET /api/v1/wallet/mnemonic` on builds with it enabled, and will happily sign a send for any
caller.

Deployed at a public URL, **anyone who learns the hostname can drain the wallet**. That was
accepted deliberately here: the funds are mutinynet, so the worst case is a faucet top-up. It is
not a shape to reuse for anything holding value, and it is the reason auth is tracked as the
remaining M1 blocker.

`force_https` protects the traffic, not the access — TLS is confidentiality, not authorization.

## Why this one gets TLS when captaind could not

captaind speaks gRPC, and Fly's edge proxy cannot cleanly terminate TLS for gRPC to an h2c
backend: the `tls` handler will not negotiate h2 ALPN, and `["tls", "http"]` negotiates it but
downgrades to HTTP/1.1 to the backend, producing a 502. That forced captaind onto a raw TCP port
with a dedicated IPv4.

barkd speaks plain HTTP/1.1 REST, so none of that applies. It gets an ordinary `[http_service]`, a
free shared IPv4, and a publicly-trusted certificate on `lark-barkd.fly.dev`.

## Migrating the wallet (do not create a new one)

The datadir holds the seed and the VTXOs, so it is moved, not recreated. `barkd-entrypoint.sh`
**refuses to start** without `db.sqlite` present, because a fresh wallet on an empty volume would
be adopted by the app and reported as a zero balance — which reads as "the funds vanished" rather
than "wrong wallet".

```sh
export PATH=/opt/homebrew/bin:$PATH
cd <lark-app>
LOCAL_DATADIR=~/.buzz/REPOS/tools/captaind-mutinynet/bark-hosted

# 1. Stop the local daemon FIRST — sqlite must not be mid-write, and a copied
#    lock file will wedge the remote start.
pkill -f 'barkd --datadir ./bark-hosted' || true

# 2. Bring the VM up idle so the volume is mounted but nothing holds the datadir.
fly secrets set MAINTENANCE=1 -a lark-barkd

# 3. Stream the datadir onto the volume (~5MB, one shot; contrast the bitcoind
#    snapshot, which needed ~1GB batches to stay under the 600s command cap).
tar cf - -C "$LOCAL_DATADIR" . \
  | fly ssh console -a lark-barkd --pty=false -C "tar xf - -C /data/bark"

# 4. Drop maintenance; the unset restarts the machine straight into barkd.
fly secrets unset MAINTENANCE -a lark-barkd

# 5. Verify it is the *same* wallet, not a new one: the balance and the
#    server_pubkey must match what the local daemon reported.
curl -s https://lark-barkd.fly.dev/api/v1/wallet/balance
curl -s https://lark-barkd.fly.dev/api/v1/wallet/vtxos
curl -s https://lark-barkd.fly.dev/api/v1/bitcoin/tip
```

## Pointing the app at it

`composeApp/.../core/CoreConfig.kt` (compile-time, never committed):

```kotlin
val mode = CoreMode.GATEWAY
const val gatewayBaseUrl = "https://lark-barkd.fly.dev"
const val expectedNetwork = "signet"       // the fork's wire id
const val networkLabel = "mutinynet"
val apiVariant = BarkdApiVariant.FORK_BETA6
const val arkServerUrl = "http://77.83.143.203:3535"
const val chainSource = "https://mutinynet.com/api"
```

Only `gatewayBaseUrl` changes from the local recipe. It also stops being a loopback URL, which
means the app's R4 rule (TLS to anything non-loopback) is now genuinely satisfied here — the local
`http://localhost:3011` setup only passed because loopback is exempt.

## Reclaiming the local disk

The point of moving barkd off the laptop. The datadir was never the cost — it is ~5MB. The cost is
the build tree that produced the binaries:

```sh
du -sh ~/.buzz/REPOS/bark/target        # ~28GB
cargo clean --manifest-path ~/.buzz/REPOS/bark/Cargo.toml
```

Safe because `rust/lark-ffi` path-depends on the bark **sources**, not on `bark/target` — the FFI
crate compiles bark into its own `rust/lark-ffi/target`. Deleting `bark/target` only costs a
rebuild if you need a local `bark`/`barkd`/`captaind` binary again. Keep the fork checkout itself:
`scripts/build-rust.sh` verifies it against `rust/fork-pins.toml`.

## Ops

- Deploy: `fly deploy . -c deploy/fly/fly.barkd.toml --dockerfile deploy/fly/barkd.Dockerfile --remote-only -a lark-barkd`
- Logs: `fly logs -a lark-barkd`
- `auto_stop_machines = false` — barkd is the app's only wallet surface, and a cold start would
  present to the app as OFFLINE rather than as a delay.
