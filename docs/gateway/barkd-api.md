# barkd API — the subset Lark uses

Pinned version pair: **bark / captaind 0.4.0** (`barkd REST API 0.4.0`). The full contract is
vendored at [`barkd-openapi-0.4.0.json`](barkd-openapi-0.4.0.json); the typed client is
`xyz.lark.app.core.gateway.BarkdApi`, and its test fixtures are string-validated against the
vendored spec (`BarkdFixtureSpecTest`, plan R14) so drift fails the build.

This documents the **stock** surface (`BarkdApiVariant.STOCK_0_4`). The LARK channel-bridge
fork speaks an older, partly different surface with a channel API — see
[`barkd-fork-api.md`](barkd-fork-api.md) (`BarkdApiVariant.FORK_BETA6`).

## Auth

The API is secured with `Authorization: Bearer <base64url auth token>` (OpenAPI `bearer`
scheme). barkd can also run with `--no-auth`, which is what `NoAuth` models. Auth is applied
in exactly one place (`AuthDecorator.decorate`) so the real token decorator drops in without
touching call sites. The Authorization header and mnemonic bodies are never logged — the
client installs no logging plugin at all.

## Endpoints used (barkd 0.4.0)

| Call | Route | Shape (one line) |
| --- | --- | --- |
| `ping()` | `GET /ping` | 200 `pong`; the only route not under `/api/v1`; unauthenticated reachability probe |
| `walletExists()` | `GET /api/v1/wallet` | `{fingerprint: string?}` — null means no wallet yet |
| `balance()` | `GET /api/v1/wallet/balance` | `{spendable_sat, pending_lightning_send_sat, claimable_lightning_receive_sat, pending_in_round_sat, pending_board_sat, pending_exit_sat?}` (all sats) |
| `vtxos()` | `GET /api/v1/wallet/vtxos` | `[{id, amount_sat, expiry_height, state:{type}, …}]` — non-spent VTXOs by default |
| `history()` | `GET /api/v1/history` | `[Movement]` — `{id, status, subsystem, intended_balance_sat, effective_balance_sat, offchain_fee_sat, sent_to, received_on, input_vtxos, output_vtxos, exited_vtxos, time, metadata?}` |
| `bip321(req)` | `POST /api/v1/wallet/bip321` | req `{amount_sat?, label?, message?, onchain?}` → `{bip321, ark?, bolt11?, onchain?}` |
| `send(req)` | `POST /api/v1/wallet/send` | req `{destination, amount_sat?, comment?}` → `{message}` |
| `refreshAll()` | `POST /api/v1/wallet/refresh/all` | `PendingRoundInfo {id, status:{status}, …}` (kept shallow) |
| `createWallet(req)` | `POST /api/v1/wallet/create` | req `{network, mnemonic?, ark_server?, birthday_height?}` → `{fingerprint}` |
| `mnemonic()` | `GET /api/v1/wallet/mnemonic` | `{mnemonic}` — never log this body |
| `waitNotifications(since?)` | `GET /api/v1/notifications/wait?since=` | long poll → `{notifications: [WalletNotification], last_pushed_at?}` |
| `connected()` | `GET /api/v1/wallet/connected` | `{connected: bool}` |
| `tip()` | `GET /api/v1/bitcoin/tip` | `{tip_height}` |
| `arkInfo()` | `GET /api/v1/wallet/ark-info` | `{network, round_interval, vtxo_expiry_delta, vtxo_exit_delta, …}` (kept shallow) |

`WalletNotification` is a tagged union on `type`: `movement-created` / `movement-updated`
(both carry a `movement`) and `channel-lagging` (notifications were dropped; resync).

## Result mapping

Every call returns `BarkdResult<T>`: `Ok(value)`; `HttpError(status, body)` for non-2xx
(401/403 surface as `isAuthRequired`) and for 2xx bodies that fail to decode (`body` is a
`contract error: …` description); `Unreachable(message)` for connect/timeout/DNS failures.
No Ktor exception escapes `BarkdApi`.

## Deliberately avoided / deferred

- **Deprecated aliases we skip:** `GET /api/v1/wallet/movements` and
  `GET /api/v1/wallet/history` — both deprecated in 0.4.0 in favor of `GET /api/v1/history`.
- **Deferred shapes:** the exit (`/api/v1/exits/*`) and offboard
  (`/api/v1/wallet/offboard/*`, `/api/v1/boards/*`) surfaces are not modelled yet; their
  DTOs land when the core grows those flows. `Movement.metadata` stays schemaless
  (`JsonObject`) per contract.
