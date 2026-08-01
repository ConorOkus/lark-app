# Hosting the mutinynet captaind stack on Fly.io

Goal: get the mutinynet Ark server off a laptop so external testers (and the M2
money-bearing contract lane) hit a hosted captaind. Keys stay on device (M2), so
there is **no per-user server** — captaind is multi-user.

## Topology (what actually has to run)

Three always-on services. `barkd` is NOT included (that was the M1 gateway model;
M2's in-process wallet talks to captaind + esplora directly).

| Service | Why | State | Ports |
|---|---|---|---|
| **bitcoind** (mutinynet / Bitcoin Inquisition custom signet) | captaind's mandatory `[bitcoind]` RPC backend; a node on the *public* mutinynet | chain data (volume) | RPC (internal only) |
| **postgres** | captaind's datastore | db (volume) | 5432 (internal only) |
| **captaind** (Greg's fork, LDK enabled) | the Ark server the app dials | LDK ChannelMonitors + wallet (volume) | gRPC 3535 (public, TLS), LDK p2p 9735 (public TCP) |

**esplora is NOT hosted initially** — our bitcoind syncs the public mutinynet, so
the app points at the public `https://mutinynet.com/api`. Add a hosted esplora
later only if we want independence from mutinynet.com uptime (it must index our
bitcoind and is heavy).

**Lightning backend = embedded LDK only.** Confirmed in `server/src/config.rs`:
`supports_channels = self.config.ldk.enabled().is_some()`, and `cln_array` may be
empty. The local working mutinynet `captaind.toml` has no `[cln]` section — so
**no Core Lightning node is required.**

## Key facts / divergences from Second's reference `contrib/docker/docker-compose.yml`

- Their captaind is `docker.io/secondark/captaind:latest` (stock upstream). We need
  **the fork** (`~/.buzz/REPOS/bark` @ `ark-channels-bridge-test-fixes`, `server`
  crate, path-deps `../rust-lightning` @ `ark-ldk-bridge-modern`) → **build + push
  our own captaind image**. Pins live in `rust/fork-pins.toml`.
- Their bitcoind is stock `bitcoin/bitcoin:30.0` regtest. We need a **mutinynet
  bitcoind** with the mutinynet signet challenge and 30s blocks. It **must** be
  benthecarman's Inquisition fork (release `mutinynet-inq-29`), **not** upstream
  `bitcoin-inquisition` — only the fork implements `-signetblocktime`, and without
  it the node wedges permanently on the first difficulty-retarget boundary. See
  the header comment in `deploy/fly/bitcoind.Dockerfile` for the full mechanism.
- captaind auth to bitcoind: cookie file (shared volume) or `rpc_user`/`rpc_pass`.
  On Fly across machines, use **`rpc_user`/`rpc_pass`** (no shared filesystem).

## Fly specifics — this stack cuts against Fly's grain; know the switches

- **No scale-to-zero.** LDK must keep monitoring channels or funds are at risk.
  `auto_stop_machines = false`, `min_machines_running = 1` on captaind + bitcoind.
- **Volumes are single-node/single-AZ.** Pin each stateful machine. Losing the
  captaind volume = losing ChannelMonitors = losing channel funds → back it up.
- **LDK p2p 9735** needs a dedicated non-HTTP `[[services]]` TCP handler and a
  stable **dedicated IPv4**; captaind must advertise that reachable address.
- **gRPC 3535** exposed over TLS (the app enforces TLS to non-loopback per R4).
- **Private networking** (Fly 6PN / `.internal`) between captaind ⇄ bitcoind ⇄
  postgres; only 3535 + 9735 are public.
- **Healthchecks must catch _stale-but-up_**, not just liveness — the fork's known
  failure mode (runbook `docs/gateway/local-mutinynet.md`) is a dead sync loop that
  keeps answering RPC with stale data. Check `getblockchaininfo` height advancing.

## Build artifacts to create (in this repo, under `deploy/fly/`)

1. `captaind.Dockerfile` — multi-stage: build the `server` crate (+ `../rust-lightning`)
   from the pinned forks, runtime image with the `captaind` binary + entrypoint that
   renders `captaind.toml` from env.
2. `bitcoind-mutinynet.Dockerfile` — mutinynet Inquisition bitcoind + `bitcoin.conf`
   (signet challenge, 30s blocks, `rpcuser/rpcpassword`, `txindex`).
3. `fly.captaind.toml`, `fly.bitcoind.toml` — one Fly app/machine per stateful service,
   volumes, always-on, health, private + public services.
4. `postgres` — Fly Managed Postgres (simplest) or a `fly.postgres.toml` machine + volume.
5. `deploy.sh` — ordered bring-up (bitcoind → wait-at-tip → postgres → captaind),
   mirroring the runbook's start-order landmine.

## Prerequisites (human)

- `flyctl` installed + `fly auth login` (not installed locally today).
- A registry for the fork images (Fly's builder/registry, or dockerhub).
- Fly org with billing (always-on machines + volumes + 1 dedicated IPv4 = ongoing cost).

## Open questions to resolve during build

- ~~Mutinynet bitcoind: build Inquisition from source vs. a trusted prebuilt
  image?~~ **Resolved:** prebuilt release binary from benthecarman's fork, pinned
  by sha256 in the Dockerfile (see above — upstream Inquisition is not usable).
  (Resource: bitcoind wants >=2GB RAM; mutinynet chain is modest but grows.)
- captaind fork image size / build time (LDK + bark is a large Rust build).
- Backup story for the captaind LDK-state volume (channel monitors).
- Does captaind need any mutinynet-specific ark params (vtxo expiry / timelocks)
  sized to the M2 liveness envelope? (ties to the M2 plan's U9.)

## Build & deploy sequence

1. Build + locally smoke `captaind.Dockerfile` (proves the fork compiles in-image).
2. Build the mutinynet `bitcoind` image; confirm it syncs to tip on the public mutinynet.
3. `fly launch` each app (no deploy), attach volumes + dedicated IPv4.
4. Deploy bitcoind → wait at tip → postgres → captaind (order matters).
5. Smoke: app in `CoreMode.FFI` against the hosted captaind + public esplora.
