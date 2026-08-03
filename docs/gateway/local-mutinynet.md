# Running lark against the local mutinynet channel stack

The only channel-capable Ark server anywhere is the forked captaind running
locally on mutinynet (Second's public signet server does not run the channel
bridge, and the fork can no longer parse current production ArkInfo). This is
the recipe for bringing that stack up and pointing the app at it, condensed
from `~/.buzz/GUIDES/LARK_LOCAL_SETUP.md` plus the landmines found live on
2026-07-29.

## Stack layout (team machines)

| Piece | Where | Listens |
|---|---|---|
| mutinynet bitcoind (Bitcoin Inquisition) | `~/.buzz/REPOS/tools/bitcoin-2fda7bfc027e/`, datadir `~/.buzz/REPOS/tools/mutinynet-data` | RPC 38332 |
| postgres 17 (dedicated cluster) | `~/.buzz/REPOS/tools/captaind-mutinynet/pg` | 54321 (unix socket) |
| forked captaind (embedded LDK, `supports_channels: true`) | config `~/.buzz/REPOS/tools/captaind-mutinynet/captaind.toml` | 3535 public / 3536 admin |
| forked barkd × 2 (alice, bob) | datadirs `bark-alice/`, `bark-bob/` in the same dir | 3001 / 3002 |

Binaries build from the bark fork (`cargo build -p bark-cli --features lightning`
— without the feature flag barkd has no `/api/v1/lightning/channels/*` routes).

## Bring-up order (order matters — see gotchas)

```sh
# 1. bitcoind — wait until it reaches tip (30 s blocks; days offline = minutes of catch-up)
~/.buzz/REPOS/tools/bitcoin-2fda7bfc027e/bin/bitcoind -datadir=$HOME/.buzz/REPOS/tools/mutinynet-data -daemon

# 2. postgres
CAP=~/.buzz/REPOS/tools/captaind-mutinynet
postgres -D $CAP/pg/cluster -p 54321 -k $CAP/pg -c listen_addresses= &   # needs LC_ALL=en_US.UTF-8

# 3. captaind — wait until its LDK log lines show "New best block … height <tip>"
~/.buzz/REPOS/bark/target/debug/captaind start -C $CAP/captaind.toml >> $CAP/captaind.log 2>&1 &

# 4. only NOW the barkds
~/.buzz/REPOS/bark/target/debug/barkd --datadir $CAP/bark-alice --port 3001 >> $CAP/barkd-alice.log 2>&1 &
~/.buzz/REPOS/bark/target/debug/barkd --datadir $CAP/bark-bob   --port 3002 >> $CAP/barkd-bob.log 2>&1 &
```

### Gotchas (each cost real time on 2026-07-29)

- **Start barkd only after captaind's LDK is listening (9735) and at tip.** A
  barkd started too early panics a tokio worker on the failed peer TCP connect
  — and that worker is the background chain-sync loop, so the daemon keeps
  answering REST while silently serving stale data and never noticing funding
  confirmations. The log tell: a `Synced until block height N` line that never
  advances, or a `thread 'tokio-runtime-worker' panicked … TCP connection to …
  9735 failed` line. Fix: restart that barkd.
- **Sync nudge.** After funding a wallet on-chain or when balances look stale,
  `curl -X POST http://127.0.0.1:300N/api/v1/onchain/sync` (and
  `/api/v1/wallet/sync`) forces the wallet to notice. The fork's automatic loop
  is unreliable after the startup race above.
- **captaind's invoice subsystem can die after a long block replay** ("channel
  closed" internal errors from `/lightning/receives/invoice`). Restart captaind
  once it's at tip.
- **Funding:** mutinynet faucet requires the CLI
  (`~/.buzz/REPOS/tools/mutinynet-cli-bin/mutinynet-cli onchain <addr> <sats>`,
  token already saved). Get an address via
  `curl -X POST http://127.0.0.1:300N/api/v1/onchain/addresses/next`.

## Smoke: the stack itself

`~/.buzz/REPOS/tools/captaind-mutinynet/mutinynet-smoke.sh` drives the full
channel flow through barkd's REST API. Stages 1–6 are non-destructive
(health → onchain floor → channel open with push → ready wait → invoice+pay →
refresh); 7–8 force-close and unilaterally exit (~3 h wall-clock, destroys the
channel). `SMOKE_STAGES=1-6 ./mutinynet-smoke.sh`.

## Pointing the app at the stack

`CoreConfig` recipe (compile-time, never committed — same discipline as any
local gateway run):

| Constant | Value |
|---|---|
| `mode` | `CoreMode.GATEWAY` |
| `apiVariant` | `BarkdApiVariant.FORK_BETA6` |
| `gatewayBaseUrl` | `http://localhost:3001` (barkd alice) |
| `arkServerUrl` | `http://localhost:3535` (captaind — used in wallet create) |
| `chainSource` | esplora URL for a fresh wallet create; may stay `""` on the team stack — the wallets are pre-created, so the app's create fails and the balance-probe adopt path takes over |
| `expectedNetwork` | `signet` (the fork's ark-info network id; mutinynet is a signet variant) |
| `networkLabel` | `mutinynet` (what the Settings footer shows) |

Platform note: the live smoke targets the **iOS simulator**, which shares the
host loopback. On the Android emulator run `adb reverse tcp:3001 tcp:3001` and
`adb reverse tcp:3535 tcp:3535` so the app still dials `http://localhost:…` —
never relax the CoreConfig https rule for `10.0.2.2` or LAN hosts.

## App smoke checklist (record pass/fail only after the sync-health check)

1. **Sync-health check first:** nudge `POST /api/v1/onchain/sync` on the barkd
   the app targets and confirm its `Synced until block height` advances; if
   not, restart that barkd (bring-up order above). A dead sync loop makes the
   app look broken when it isn't.
2. Launch the app → onboard ("Set up a wallet" adopts the gateway's existing
   wallet). Home shows READY health and the real spendable balance.
3. Get paid shows a `bitcoin:?ark=tark1…` QR (address minted once per session
   via `addresses/next`).
4. Backup shows the words-unavailable notice (the fork has no mnemonic
   endpoint — expected, not a bug).
5. Settings footer reads `… · mutinynet`; Advanced shows the Lightning bridge
   row with the channel count/balance and one row per channel (state + expiry).
6. Activity lists the wallet's movements (`/wallet/history` on this surface).

## Channel-payment smoke (send and receive over a channel)

**Precondition — check this first, or the rest of the stage is meaningless.**
`GET /api/v1/lightning/channels` on the barkd the app targets must answer with a
channel, not a 500:

```bash
curl -s http://localhost:3001/api/v1/lightning/channels
```

If it answers `500 {"message": "… LDK node not initialized"}`, this barkd has no
LDK node and **there is nothing to smoke** — the app will correctly route every
payment over Ark and show an em-dash Lightning bridge. That is the designed
behavior, not a regression. See the trap below.

1. **Send over a channel.** The app has no destination text entry yet (#14), so
   point `PASTED_HANDLE` in `AppStateMachine` at a real BOLT11 invoice and
   rebuild — the same technique #14 documents. The invoice must be:
   - on this wallet's network (a signet/mutinynet `lntbs…` prefix),
   - **amount-bearing**, and
   - for an amount within the channel's outbound liquidity
     (`local_balance_msat / 1000` summed over usable channels).

   Enter that same amount on the keypad. Any mismatch between the keypad amount
   and the invoice amount deliberately routes over Ark instead — that is the
   guard against paying a figure the review screen never showed, not a bug.

   Expect: the app lands on **Sent.** only after the payment reached a terminal
   LDK state. A payment that never settles within the budget lands on **On its
   way** instead — record which you saw.

2. **Receive over a channel.** Get paid → Set amount → enter an amount, and read
   the code box. Two outcomes, both correct:
   - `bitcoin:?ark=…&lightning=lntbs…` — inbound liquidity covered the amount.
   - `bitcoin:?ark=…` only — it did not, so no invoice was minted.

   **Record which branch you observed.** On a channel this wallet funded itself,
   inbound liquidity is zero (inbound is the counterparty's side), so ark-only is
   the expected answer until value has moved out of the channel. Verify with:

   ```bash
   curl -s http://localhost:3001/api/v1/lightning/channels \
     | python3 -c 'import json,sys; cs=json.load(sys.stdin); print(sum(c["capacity_sat"]-c["local_balance_msat"]//1000 for c in cs if c["is_usable"]), "sat inbound")'
   ```

### Trap: `supports_channels: true` on a barkd with no LDK node

The hosted Fly stack (`lark-barkd.fly.dev`) reports `supports_channels: true`
from `ark-info` while every `/api/v1/lightning/*` route answers
`500 "LDK node not initialized"` (probed 2026-08-03). ark-info describes the
*Ark server's* capability; it says nothing about whether this barkd has an LDK
node of its own.

So the capability flag cannot be trusted as proof, and the app does not trust
it: it treats LDK availability as a runtime fact, falls back to Ark for every
payment, keeps the Lightning bridge row at an em-dash, and after the first
not-initialized answer drops to a slow re-probe instead of failing a request
every poll cycle. If channel behavior appears to do nothing on a given stack,
check this before assuming an app bug.
