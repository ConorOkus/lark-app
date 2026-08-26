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

### Server-bridged Lightning receive
Being paid over Lightning by having the Ark server take the incoming HTLC and hand the value over as VTXOs. The wallet mints an invoice the server stands behind, and the wallet's own upkeep pass claims the result; bark calls this offchain boarding, because it is a way of acquiring a balance rather than a way of spending one.

Distinct from paying and being paid over a [Channel](#channel), which is the same outcome reached without the server in the path. The two differ in what they require rather than in what the holder sees: a channel receive needs inbound capacity the wallet had to acquire, while a server-bridged receive needs only a reachable server — which is why it is the only one of the two available to a wallet with no funds at all.

Its availability is a property of the server's configuration, not of the protocol. A server that demands proof of an existing VTXO before preparing a claim cannot serve an empty wallet, and the claim path carries no way to satisfy that demand by other means.

### Exiting
The state a wallet is in while it leaves the Ark unilaterally — a property of the wallet, not a screen it happens to be showing. An exiting wallet accepts no sends or receives, keeps its funding intent disarmed, and resumes its progress on every app open until every VTXO has been claimed on-chain.

The mode exists because the underlying process outlives any one session: it crosses the exit delta and cannot be completed in a sitting, and nothing runs while the app is closed. Two consequences follow. Leaving the mode is possible only by finishing it — a broadcast transaction cannot be recalled, so an exit that has started has no honest cancel — and an exit that cannot progress therefore holds the wallet indefinitely, reported as stalled and retried rather than abandoned.

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

The library it loads is a build artifact the build system neither produces nor tracks, so a run that executes every assertion may still have exercised an earlier build of the core. Running this lane is therefore two steps rather than one — rebuild, then test — which is the order the automated pipeline uses by construction and a local run has to reproduce deliberately.

Its coverage boundary is an enumeration rather than a property the lane derives: which wallet operations need a signing counterparty and which are answerable locally is written down by hand, so an operation newly added to the wallet's surface sits outside the lane until someone classifies it. A green run means every operation anyone listed still behaves as listed — never that everything the wallet can now do was checked.

### Live lane
The opt-in verification lane that runs against real infrastructure, covering the money-bearing behavior the pure-local lane cannot: a funded balance and a successful spend. It is gated off by default and skips visibly rather than passing silently, so a routine run never implies coverage it did not provide.

### Exit drill
The standing, re-runnable proof that a wallet can leave the Ark without the Ark server: board, exit, claim, withdraw, run end to end with the server deliberately stopped. It is kept rather than discarded after it first passes, because the claim it backs is a public one and the engine behind it is a pinned fork that moves.

The drill is what separates a property that was demonstrated once from a property that stays true. It is the exit-path counterpart to the fork pin's contract suite, so a bark upgrade that breaks unilateral exit fails visibly instead of silently invalidating what the app tells users.

## Dependencies

### Fork pin
The recorded remote, branch, and exact commit of an external fork this project builds against, kept as the single source both the build scripts and CI read. Pinning makes a fork upgrade a reviewable one-line change gated by the test suite, rather than depending on whichever checkout happens to exist on a given machine.
