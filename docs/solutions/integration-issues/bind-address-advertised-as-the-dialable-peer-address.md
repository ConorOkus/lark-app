---
title: A bind address advertised as the dialable address makes every peer connection fail
date: 2026-08-03
category: integration-issues
module: deploy/fly
problem_type: integration_issue
component: payments
symptoms:
  - "Every client logs: TCP connection to <pubkey> at 0.0.0.0:9735 failed"
  - A panicked tokio-runtime-worker takes out barkd's chain-sync loop while REST keeps answering 200
  - "\"Synced until block height N\" never advances, so channel funding confirmations are never noticed"
  - No channel can be opened, so the app correctly routes every payment over Ark and shows an em-dash Lightning bridge
root_cause: config_error
resolution_type: code_fix
severity: critical
related_components:
  - tooling
  - development_workflow
tags:
  - lightning
  - ldk
  - announce-address
  - bind-address
  - fly-io
  - captaind
  - nat
  - silent-failure
---

# A bind address advertised as the dialable address makes every peer connection fail

## Problem

captaind's `[ldk]` config had a single `listen_address`, used both as the socket
to bind *and* as the address handed to clients in `get_ln_node_info`. On Fly the
bind has to be `0.0.0.0:9735`, so every bark client dutifully dialed
`0.0.0.0:9735` — its own loopback wildcard — and no Lightning peer connection to
the Ark server could ever be established. Channels were impossible on the hosted
stack, which meant the entire channel send/receive feature was unreachable.

## Symptoms

- Every client logged `TCP connection to <pubkey> at 0.0.0.0:9735 failed`.
- Worse than a clean failure: the failed connect **panicked a tokio worker**, and
  that worker was barkd's background chain-sync loop. The daemon kept answering
  REST with HTTP 200 while silently serving stale data — a wallet reporting
  confident balances it had stopped updating. The tell is a
  `Synced until block height N` line that never advances.
- `GET /api/v1/lightning/channels` had nothing to report, so the app fell back to
  Ark for every payment and showed an em-dash Lightning bridge. That fallback is
  correct behavior, which is exactly why the underlying breakage was easy to
  mistake for "channels just aren't wired up yet."

## What Didn't Work

- **Binding the public address directly.** The obvious fix — set
  `listen_address` to the dedicated IPv4 so bind and advertise genuinely coincide
  — cannot work on Fly. Fly forwards the dedicated IPv4 to a *private* address
  that the VM's interface does not hold, so binding the public address fails at
  startup. Any NAT, proxy, or forwarding deployment has this shape; only a
  host-networked box can get away with one field.
- **Rendering an empty `announce_address` when the env var is unset.** captaind
  parses the value as a `SocketAddr`, and `""` is not one, so the daemon refuses
  to start. An unset announce address has to make the key *absent*, not empty —
  `deploy/fly/captaind-entrypoint.sh:33` deletes the line rather than rendering
  it blank.

## Solution

Two commits on the bark fork — which lives in a sibling checkout, so the
citations below name crates and symbols rather than paths; `rust/fork-pins.toml`
holds the authoritative branch and SHA — plus the deploy side:

1. **Split the two roles.** captaind's `LdkConfig` (fork, `server` crate) gained
   an optional `announce_address: Option<SocketAddr>` and an accessor that falls
   back to the bind address, so existing host-networked configs keep working
   unchanged:

   ```rust
   /// The address clients should dial to reach this node's LN peer listener.
   pub fn announced_address(&self) -> SocketAddr {
       self.announce_address.unwrap_or(self.listen_address)
   }
   ```

   The `get_ln_node_info` RPC handler then returns `announced_address()` instead
   of the raw bind address. The client side is unchanged — bark's channel-open
   path parses and dials whatever the server advertises, which is precisely why
   the server must advertise something dialable.

2. **Stop panicking on a failed outbound connect.** A peer being unreachable is
   an ordinary condition on a reconnect loop, and the caller already treats a
   peer that never appears as a timeout. The fork's `bark-lightning`
   peer-connect helper now logs `log::warn!` where it used to `panic!`.

3. **Deploy wiring.** `deploy/fly/captaind.toml.template` sets
   `announce_address = "__LDK_ANNOUNCE_ADDRESS__"`, the entrypoint substitutes
   the Fly dedicated IPv4 from a secret, and drops the line entirely when unset.

Verified live: a `Connecting to Lightning peer <pubkey> at 77.83.143.203:9735`
line — the dedicated IPv4, not the wildcard — followed by
`Connected to Lightning peer`, from two independent clients, then a usable
500k/500k channel. Shipped in #34.

## Why This Works

Bind and advertise answer different questions — "which local sockets do I
accept on?" versus "what should a stranger dial to reach me?" — and they coincide
only when the server owns its public address locally. Collapsing them into one
field encodes the host-networked case as an assumption in the type, so the config
becomes unrepresentable the moment anything sits between the process and the
network. `Option<SocketAddr>` with a fallback keeps the simple case simple while
making the deployed case expressible.

The panic fix is separable but not incidental: it's what turned a visible
connectivity failure into a *silent* one. A crashed sync loop behind a healthy
REST surface is far more expensive to diagnose than a connection error, because
every subsequent observation is subtly wrong rather than obviously broken.

## Prevention

- **Name the role in the field.** Any config field holding an address should say
  whether it is bound or advertised. `listen_address` was not wrong for what it
  did; it was wrong for what it was *also* used for.
- **Never panic on a reconnect path.** If a loop retries, its failure mode is a
  log line. A panic in an async worker doesn't just fail the attempt — it takes
  out whatever else that worker was scheduled to do, and the process survives to
  report success.
- **When a peer won't connect, check the advertised value, not the config.** Read
  what `get_ln_node_info` / `ark-info` actually returns; the config file is the
  input to that answer, not the answer.
- **Treat "REST still answers" as no evidence of health.** Confirm the sync
  height is advancing before trusting any balance or confirmation state — the
  same discipline the app's smoke checklist opens with
  (`docs/gateway/local-mutinynet.md`).

## Related Issues

- PR #34 — `deploy(captaind): advertise a dialable LDK peer address`
- `docs/gateway/local-mutinynet.md` — the sibling trap: `supports_channels: true`
  from `ark-info` says nothing about whether *this* barkd has an LDK node, and
  the bring-up-order gotcha caused by this same panic
- `CONCEPTS.md` — Bind address versus announce address
- The fork commits still need upstreaming to `ark-bitcoin/bark`; they live on
  `fix/ldk-announce-address` on `gitlab.com/ConorOkus/bark` as of this writing
