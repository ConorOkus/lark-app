# Concepts

Shared domain vocabulary for this project — entities, named processes, and status concepts with project-specific meaning. Seeded with core domain vocabulary, then accretes as ce-compound and ce-compound-refresh process learnings; direct edits are fine. Glossary only, not a spec or catch-all.

## Funds

### VTXO
An off-chain output the wallet cryptographically owns, held within the Ark rather than on the blockchain. The wallet's spendable balance is the sum of its VTXOs, so a wallet with none reports zero even when it holds on-chain coins.

VTXOs expire. A wallet that stays offline past its expiry window risks losing the ability to spend them unilaterally, which is why refresh cadence and offline tolerance are treated as safety properties rather than conveniences.

### Board
The act of moving on-chain funds into the Ark, producing spendable VTXOs. Boarding is how an otherwise-empty wallet acquires a balance.

A board is an on-chain transaction and pays a miner fee out of the very coins it moves, so the amount boarded is always less than the balance boarded *from* — and asking to board a whole balance as a named amount cannot succeed, because nothing would remain to pay the fee. Boarding a whole balance is therefore the engine's arithmetic to do, not the caller's.

Confirmation is necessary but not sufficient for the funds to become spendable: a confirmed board also has to be **registered** by the wallet, which happens during its periodic upkeep rather than on a balance read. A wallet that only reads its balance can hold a confirmed board indefinitely without ever showing it.

### Ark server
The server a wallet must reach to perform Ark operations — minting a receive address and spending among them. It participates in signing, so no local stand-in can substitute for it: operations that need one either reach a real server or honestly fail.

Distinct from the **chain source**, the ordinary blockchain data provider a wallet reads for genesis, tip, and fee information. A wallet can do a surprising amount with a chain source alone — create itself, report a balance, derive an on-chain deposit address — and the split between the two is what makes a server-free test lane possible.

## Channels

### Channel
A Lightning channel the wallet holds itself, funded by a VTXO rather than by an on-chain transaction. It is what lets the wallet pay and be paid over Lightning without routing through the Ark server's own bridge, and it is the project's central differentiator.

Because the funding is a VTXO, the channel inherits that VTXO's expiry: a channel is a claim with a deadline, not a standing arrangement. A channel can also exist on paper while being unusable — funding unconfirmed, or the peer offline — so readiness and usability are tracked separately from existence.

### Outbound and inbound capacity
The two directions a channel's balance can be spent. Outbound is the wallet's own side, the amount it can pay out. Inbound is the counterparty's side, the amount it can receive.

The asymmetry is the point: a channel the wallet funded itself starts with everything outbound and **nothing** inbound, so a freshly funded channel can pay but cannot be paid. Inbound capacity only appears once value has moved out. Neither figure is reported directly — both are derived from capacity against the local balance.

### Acknowledgement versus settlement
The distinction between a payment the server has accepted and one that has actually completed. An accepted payment can still fail later, so treating acknowledgement as settlement is what lets a wallet claim money moved when it did not.

Which of the two a given path can prove is a property of that path: some return only a message, while others return a payment handle whose terminal state can be polled. A path that cannot prove settlement is expected to say less, not to guess.

Acknowledgement also carries information the eventual failure reason can lose: because an accepted payment has already been routed, a later terminal reason may describe only the final retry rather than the attempt that actually failed. The accept and the failure are separate facts, and the earlier one can outrank the later one when diagnosing.

### CLTV budget
The block-height headroom an HTLC carries so that every hop, and the exit path behind an Ark-funded channel, can still resolve in time. The sender computes it from its own view of the chain tip plus the deltas each hop demands.

The floor is higher than on an ordinary Lightning channel: resolving an HTLC after a force-close crosses the channel's exit delays in series before the HTLC's own deadline, so too small a budget lets a counterparty's timeout branch beat the receiver's success branch. A hop that receives an HTLC below the floor is expected to refuse it rather than forward it, and that refusal is the only signal that a sender's budget arithmetic is wrong.

### Application-fed chain view
The chain height and confirmations an embedded channel node knows only because the wallet hands them to it. The node has no chain source of its own — a deliberate consequence of funding channels from a transaction chain that is never broadcast, since there is nothing on-chain for a node to observe.

The tradeoff is that the node's sense of the present is exactly as fresh as the last feed, and it is used to compute outgoing HTLC deadlines. A view that stops advancing does not announce itself: sends fail as though misrouted, while receives keep working, because a stale height makes an incoming deadline look further away rather than nearer. Distinct from the **chain source**, which is the wallet's own upstream blockchain data provider.

### Bind address versus announce address
The two addresses a Lightning peer needs, answering different questions: which local sockets it accepts connections on, and what a stranger should dial to reach it. Clients dial the announced address verbatim, so it is the announcement — not the binding — that determines whether a peer is reachable at all.

The two coincide only when the host owns its public address locally. Behind any forwarding layer they must differ, and the public address is typically not bindable there at all, so a deployment that can only express one of the two cannot be made reachable.

## Verification lanes

### Pure-local lane
The verification lane that runs on every change: the real in-process wallet exercised with a stubbed chain source and no Ark server. It covers everything a zero-balance wallet can prove — lifecycle, guards, locally derived addresses — and deliberately excludes anything requiring a signing counterparty.

Because it must run everywhere, it skips itself when its native library cannot load. On a lane that is supposed to prove the wallet works, that skip is required to become a failure instead — otherwise a green run asserts nothing.

### Live lane
The opt-in verification lane that runs against real infrastructure, covering the money-bearing behavior the pure-local lane cannot: a funded balance and a successful spend. It is gated off by default and skips visibly rather than passing silently, so a routine run never implies coverage it did not provide.

## Dependencies

### Fork pin
The recorded remote, branch, and exact commit of an external fork this project builds against, kept as the single source both the build scripts and CI read. Pinning makes a fork upgrade a reviewable one-line change gated by the test suite, rather than depending on whichever checkout happens to exist on a given machine.
