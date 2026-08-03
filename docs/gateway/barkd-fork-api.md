# barkd fork REST contract (LARK channel bridge, 0.1.0-beta.6)

Wire contract for Greg's forked barkd (`gitlab.com/gsanders87/bark`, channel-bridge branches;
binaries built from `5020b4c5`). The spec is served by the daemon itself at
`GET /api-docs/openapi.json` and vendored verbatim at
[`barkd-fork-openapi-0.1.0-beta.6.json`](barkd-fork-openapi-0.1.0-beta.6.json)
(captured live from barkd alice, 2026-07-29). The stock 0.4.0 contract the app
also speaks is documented in [`barkd-api.md`](barkd-api.md).

The fork predates the 0.4.0 REST reshuffle, so the two surfaces differ in both
paths and capabilities. `BarkdApiVariant` in the app selects between them at
compile time; `BarkdCapabilities` encodes the differences below.

## Endpoint diff vs stock 0.4.0

| App-used endpoint (0.4.0) | Fork (0.1.0-beta.6) |
|---|---|
| `GET /ping` | same |
| `GET /api/v1/wallet/balance` | same (fork `pending_exit_sat` is nullable) |
| `GET /api/v1/wallet/vtxos` | same |
| `GET /api/v1/wallet/ark-info` | same path; adds required `supports_channels`, drops `fees` |
| `GET /api/v1/wallet/connected` | same |
| `POST /api/v1/wallet/send` | same |
| `POST /api/v1/wallet/refresh/all` | same |
| `GET /api/v1/bitcoin/tip` | same |
| `POST /api/v1/wallet/create` | same path, **different body**: `{ark_server, chain_source, network, mnemonic?, birthday_height?}` |
| `GET /api/v1/history` | **moved** → `GET /api/v1/wallet/history` (same `Movement` schema; `/wallet/movements` is a sibling) |
| `POST /api/v1/wallet/bip321` | **absent** — mint a raw ark address via `POST /api/v1/wallet/addresses/next` (`{address}`) and build `bitcoin:?ark=<addr>` client-side |
| `GET /api/v1/wallet/mnemonic` | **absent** — backup words unavailable on this surface |
| `POST /api/v1/notifications/wait` | **absent** — pure polling only |

## Capabilities (what `BarkdCapabilities` encodes)

| Capability | Stock 0.4.0 | Fork beta.6 |
|---|---|---|
| `historyPath` | `/api/v1/history` | `/api/v1/wallet/history` |
| `hasBip321` | yes | no (addresses/next + client URI) |
| `hasMnemonic` | yes | no (words-unavailable notice) |
| `hasNotifications` | yes (long-poll) | no (poll-only) |
| `hasChannels` | no | yes |
| create body | `{network, esplora{url}, …}` | `{ark_server, chain_source, network}` |

## Channel surface (fork-only, consumed read-only this milestone)

- `GET /api/v1/lightning/channels` → `[LightningChannelInfo]`:
  `channel_id` (hex 32B), `counterparty` (node pubkey), `capacity_sat`,
  `local_balance_msat`, `is_usable`, `is_channel_ready`,
  `expiry_height?` (backing-VTXO expiry; nullable),
  `force_close_spend_delay?` (static CSV parameter, **not** a close-in-progress
  signal — force-closed channels leave this list and become exits).
- `GET /api/v1/lightning/channels/balance` → `LightningBalanceInfo{balance_sat}`
  (total local balance across usable channels).
- Not consumed: `channels/open`, per-channel `refresh`,
  `{channel_id}/force-close`, `offboard` (see the plan's Scope Boundaries).

## LDK payment surface (fork-only, consumed by channel send/receive)

- `POST /api/v1/lightning/channels/ldk-pay` → `LdkPayRequest{bolt11}` →
  `LdkPaymentInfo{payment_hash, status, detail?}`. **No amount field:** the
  invoice's own amount is what gets paid, which is why the app parses it before
  routing here. A 200 means *accepted*, not settled.
- `GET /api/v1/lightning/channels/ldk-payment/{hash}` → `LdkPaymentInfo`. The
  settlement handle: polled until `status` is terminal.
- `POST /api/v1/lightning/channels/ldk-invoice` →
  `LdkInvoiceRequest{amount_sat, expiry_secs?}` →
  `LdkInvoiceInfo{bolt11, payment_hash}`. `amount_sat` is required, so an
  amountless Get paid cannot use this route.
- `GET /api/v1/lightning/channels/ldk-payments` → `[LdkPaymentEntry]`
  (`payment_hash`, `status`, `direction`, `amount_msat`, `detail?`). Decoded and
  fixture-pinned; not read by the send or receive paths, which hold a hash.

`status` is `pending` | `claimable` | `sent` | `claimed` | `failed`, documented
in prose rather than as an enum. The app maps `sent`/`claimed` to success,
`failed` to failure, and **everything else — including an unrecognized value —
to non-terminal**, so a new status can only make it wait longer.

`detail` is the **payment preimage** once sent (and a failure reason once
failed). Treat it like the mnemonic: never logged, never surfaced.

Every route here answers `500 {"message": "… LDK node not initialized"}` on a
barkd built without a live LDK node — which the deployed Fly stack is, despite
its ark-info reporting `supports_channels: true`. The app treats that exact body
as "attempted nothing" and may fall back to the Ark path in place; any *other*
`ldk-pay` failure refuses without retrying, because it cannot prove the payment
was not attempted.

## Other fork-only surface (documented, not consumed)

`/api/v1/boards/` (list, `board-amount`, `board-all`), `/api/v1/wallet/rounds`
(pending-round list), `POST /api/v1/wallet/sync` + `/api/v1/onchain/sync`
(explicit sync nudges — see the local-mutinynet runbook for why these matter
operationally), `/api/v1/exits/*`, `/api/v1/onchain/*`.

## Observed live values (2026-07-29, local mutinynet stack)

Fork ark-info: `network: signet` (mutinynet is a signet variant — the id does
not discriminate mutinynet), `supports_channels: true`, `round_interval: 30s`,
`required_board_confirmations: 3`, `vtxo_expiry_delta: 8640`,
`min_board_amount: 20000 sat`.
