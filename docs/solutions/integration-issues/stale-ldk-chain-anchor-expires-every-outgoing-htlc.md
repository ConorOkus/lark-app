---
title: A stale LDK chain anchor makes every outgoing HTLC expire before it can be forwarded
date: 2026-08-03
category: integration-issues
module: forks/bark-lightning
problem_type: integration_issue
component: payments
symptoms:
  - "`ldk-pay` accepts the payment (`pending`), then it resolves `failed` with `RouteNotFound`"
  - "The Ark server logs `Failed to accept/forward incoming HTLC: CLTVExpiryTooSoon`; the client logs nothing but the final reason"
  - The same wallet receives over a channel perfectly but can never send
  - A channel opened minutes ago sends fine; a channel open for hours cannot
root_cause: missing_workflow_step
resolution_type: code_fix
severity: high
related_components:
  - tooling
  - development_workflow
tags:
  - lightning
  - ldk
  - ark
  - channels
  - cltv
  - htlc
  - stale-chain-view
  - misleading-error
---

# A stale LDK chain anchor makes every outgoing HTLC expire before it can be forwarded

## Problem

Channel payments from a wallet whose channel had been open for a few hours failed every time, while
the identical payment from a freshly opened channel settled. The client reported `RouteNotFound`,
which is not what happened: a route was found, an HTLC was sent, and the Ark server rejected it
because its CLTV expiry was already in the past. The wallet's embedded LDK node was computing HTLC
expiries from a chain tip roughly 670 blocks (~5.5 hours) stale, because nothing in the client
advances that view except a handful of channel lifecycle events.

This is why the "sent → settled" branch had never been observed end to end before (#32's shipped
work could report failure honestly but had nothing successful to show).

## Symptoms

- `POST /lightning/channels/ldk-pay` returns `{"status":"pending"}` — accepted, not rejected — and
  the payment then resolves to `{"status":"failed","detail":"RouteNotFound"}`.
- The client's only log line is LDK's `Failed to find a route on retry, abandoning payment …`.
- The Ark server, at `TRACE`, tells the truth:
  `INFO lightning::ln::channelmanager: Failed to accept/forward incoming HTLC: CLTVExpiryTooSoon`.
- Asymmetry that looks like a routing or topology problem but is not: the affected wallet **receives**
  over the same channel without trouble, and a second wallet whose channel is new **sends** without
  trouble, through the same server, using invoices of the same shape.
- Progressive, not binary: the failure appears once the channel has been open long enough for the
  gap between the anchored height and the real tip to exceed the route's total CLTV delta.

## What Didn't Work

- **Reading the reported failure reason at face value.** `RouteNotFound` sent the investigation
  toward pathfinding: decoding both invoices' route hints byte-for-byte (identical shape — same
  server node, distinct SCIDs, same 314-block delta), checking whether either node's LDK considered
  its channel usable (both mint invoices carrying hints, so both do), and inspecting the scorer. All
  of it was wasted: `ldk-pay` returning `pending` already proved a route existed, because a
  pathfinding failure surfaces as an error from the send call rather than an accepted payment.
  The reason string describes the **retry**, which had no alternative path — not the first attempt,
  which failed for an entirely different reason.
- **Suspecting the probabilistic scorer's learned liquidity bounds.** Plausible — the wallet had
  accumulated outbound failures on its only first hop — but the first failure happened on a
  process with a freshly constructed graph and scorer, both of which are in-memory only.
- **Restarting the wallet daemon.** The channel manager is restored from persisted state including
  its best block, and nothing feeds the tip on startup, so a restart preserves the stale anchor
  exactly. Verified: the payment failed identically after a restart.
- **Assuming the earlier `Failed to find route` on an external invoice explained this too.** It did
  not; that one is correct-by-design (the fork implements only intra-Ark payments with routing
  hints, and the deployed server has no CLN backend configured). Two different failures wearing
  similar words.
- **Adding a second wallet was necessary but not sufficient.** The stack genuinely lacked any
  counterparty to route to, and building one was required — but it only revealed this bug rather
  than fixing it, because it produced a *fresh* sender that worked and left the old sender's
  failure unexplained.

## Solution

**Diagnosis first: compare two senders against the same tip.** The measurement that isolates it,
using the wallet's own payment records and the server's `update_add_htlc` trace:

| Sender | HTLC `cltv_expiry` | Chain tip at send | Budget | Outcome |
|---|---|---|---|---|
| Fresh channel (minutes old) | 3316143 | 3315468 | **+675** | forwarded, settled |
| Stale channel (~5.5h old) | 3315477 | 3315462 | **+15** | `CLTVExpiryTooSoon` |
| Same, minutes later | 3315437 | 3315466 | **−29** | already expired on arrival |
| Stale channel, after re-anchoring | 3316180 | 3315474 | **+706** | forwarded, settled |

Invoices in this flow demand `min_final_cltv_expiry_delta: 314` and their route hint advertises a
314-block delta, so a healthy sender emits roughly `tip + 628` plus LDK's shadow-route padding. Both
stale samples land ~670 blocks below that, and dividing by the network's block time gives the age of
the channel — which is where the anchor was last set.

**Remedy applied:** any event that calls the client's virtual-confirmation path re-anchors the node's
best block at the current tip, because it feeds `best_block_updated` with
`max(fed_height, current_best_block)`. Opening a second channel did it here, and the identical
payment that had failed three times settled immediately afterwards — first over REST, then through
the app's own send path (7,000 sat, recipient reporting `claimed 7000000msat`). A channel refresh
re-anchors the same way and is the better lever on a wallet with one channel, since it does not
consume funds to open something new.

**Durable fix (not yet written):** the client needs a periodic tip feed. Grepping the fork — whose
authoritative remote, branch, and commit live in `rust/fork-pins.toml`, quoted there rather than here
so this doc does not go stale on the next pin bump — shows the only calls that advance the embedded
node's chain height are the daemon's channel-ready path, the channel-refresh path, and the exit
driver's confirmation feed — all lifecycle events, none of them a clock. A wallet
that opens a channel and then sits idle drifts without bound. Citations name crates and symbols
rather than paths because the fork lives in a sibling checkout: `ClientNode::apply_virtual_confirmation`
and `ClientNode::feed_tx_confirmation` in `bark-lightning` are the two entry points; a poll-driven
call to the former (or a dedicated tip tick) alongside the existing daemon sync loop is the shape.

## Why This Works

An embedded LDK node in this design has no chain source of its own. Confirmations and the current
height are **fed to it by the application** — that is deliberate, and it is what allows a channel
funded by a never-broadcast transaction chain to exist at all. The cost of that design is that the
node's sense of "now" is only as fresh as the last feed, and an outgoing HTLC's expiry is computed
from it: `expiry = anchored_height + Σ(hop deltas) + final delta`. Anchor the height in the past and
every expiry lands in the past by the same margin, no matter how correct the route.

The receiving asymmetry follows from the same arithmetic in the other direction. An incoming HTLC is
checked against the node's own height to confirm it has enough time left to resolve; a stale height
makes the deadline look *further* away, so inbound HTLCs are accepted rather than refused. The bug is
therefore invisible on the receive path and total on the send path — the exact signature that made it
look like a routing or liquidity problem.

The server is not being strict for its own sake: an HTLC landing on an Ark-protected channel must
carry enough budget to survive two serial exit delays on a force-close before its absolute expiry, or
the counterparty's timeout branch can win the race against the receiver's success branch. Rejecting a
short-budget HTLC is the correct behavior, and it is the only component in the path positioned to
notice.

## Prevention

- **Treat a terminal failure reason as possibly describing the last attempt, not the first.** When a
  payment API accepts a payment and *then* reports a failure, the reported reason belongs to whatever
  the retry logic did last. `pending` on the accept call is evidence that pathfinding succeeded —
  reason strings that contradict that should be distrusted rather than investigated.
- **When two peers disagree about a payment, read the peer that refused it.** The client had no
  useful log; the server had the exact cause at `TRACE`. Capturing the server's log *across* a
  deliberately triggered payment (start the log stream, send, wait, stop) is what surfaced it, and is
  cheap enough to be a first move rather than a last resort.
- **Isolate with a control that differs in one variable.** A fresh sender and a stale sender, same
  invoice shape, same server, same tip, is what turned a vague "sends don't work" into a measurable
  ~670-block offset. Prefer building the control over reasoning about the failing case.
- **Assert the anchor, don't infer it.** The fed chain height is not exposed by any read surface
  today, so it can only be derived by arithmetic from an emitted HTLC. Exposing it (a debug field on
  the channel read, or a log line each time it advances) would collapse this diagnosis to one glance.
  The general form of the trap is already in this project's runbook (`docs/gateway/local-mutinynet.md`):
  a component answering requests happily while silently serving a stale view of the chain.
- **A regression test needs an aging step, not just a payment.** Open a channel, advance the chain
  well past the route's total CLTV delta without touching the channel, then pay. A test that pays
  immediately after opening passes on the broken code — which is exactly why the smoke script's
  pay stage never caught this.
- **Upstreaming note:** the contract this violates is one the upstream harness already pins.
  `bark-channels`' release-contract tests (`ark-bitcoin/bark!2321`) explicitly cover
  application-fed confirmations and channel readiness gated on them. The LDK side is correct; the
  client simply stops feeding, which is worth raising alongside those tests rather than as a separate
  bug.

## Related Issues

- #32 — the settlement-truth work that made this diagnosable: the app polls a payment to a terminal
  state instead of trusting acknowledgement, so it reported the failure honestly rather than showing
  "Sent."
- #35, #36 — surfacing gaps found while proving the settled payment in the app (channel payments
  absent from Activity; channel liquidity absent from the headline balance).
- `docs/solutions/integration-issues/bind-address-advertised-as-the-dialable-peer-address.md` — the
  sibling failure on the same stack, and the same lesson in a different costume: a healthy-looking
  surface hiding a stalled chain view.
- `docs/gateway/local-mutinynet.md` — the channel-payment smoke checklist, which needs the aging
  step described under Prevention.
- `CONCEPTS.md` — CLTV budget; Application-fed chain view.
