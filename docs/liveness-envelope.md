# The liveness envelope: how long a lark wallet can be left closed

Keys on the device changed what "offline" costs. With the hosted gateway, a daemon was always
running and refreshing VTXOs whether or not anyone opened the app. With the in-process core, nothing
runs while LARK is closed — no background refresh exists yet (issue #28) — so **the VTXO lifetime is
literally how long a user may ignore the app before the server can sweep their money.**

This is the number that decides whether a TestFlight build is safe to hand out, so it is written
down rather than left implicit.

## The numbers on the team's mutinynet stack

Source: `deploy/fly/captaind.toml.template`. mutinynet targets 30-second blocks.

| Parameter | Value | In time |
| --- | --- | --- |
| `vtxo_lifetime` | 60,480 blocks | **21 days** |
| `vtxo_exit_delta` | 144 blocks | ~72 minutes |
| `round_interval` | 30s | — |
| `required_board_confirmations` | 3 | ~90 seconds |
| `min_board_amount` | 20,000 sat | — |

The app warns before the deadline: `STALE_THRESHOLD_DIVISOR` in `GatewayLarkCore` puts the
"needs a moment" card at the last eighth of the window, i.e. **~2.6 days before expiry**.

**The lifetime has a hard ceiling of 65,535 blocks**, because `vtxo_lifetime` is a `BlockDelta` =
`u16` (`bitcoin-ext/src/lib.rs`). A larger value does not clamp — it fails to deserialize and
captaind will not start. At 30-second blocks that ceiling is ~22.75 days, so **22 days is the most
this network can ever offer**, and 21 is the round number below it. Worth knowing that upstream's
default of `6*24*30` is written for 10-minute blocks; on mutinynet the same count is 36 hours.

## The bound

**Safe to leave closed: about 18 days.** Derived as the 21-day lifetime, minus the ~2.6 days of
warning window the app wants in order to have told the user anything at all, minus a margin for the
worst-case unilateral exit (measured at ~2h45m in the M2 planning work) and a few confirmations.

Opening the app and letting a refresh complete resets the clock. In practice a tester who opens LARK
once a week is never near the edge.

## Why 21 days and not the 72 hours it was

`vtxo_lifetime` was 8,640 blocks. On a 30-second chain that is **72 hours** — so a tester who
downloaded the build on Friday and came back on Tuesday could find their money gone, having done
nothing wrong. Worse, the app's own warning window would be ~9 hours, which is not a warning anyone
sees on a phone they check daily.

The value only binds newly created VTXOs, so raising it does not rescue anything already in flight.

It cannot be raised further: see the u16 ceiling above. If 22 days ever stops being enough, the fix is
background refresh, not a bigger number.

## What is still missing

- **Background refresh** (#28). Until it exists, this bound is enforced by the user remembering to
  open an app, which is the weakest possible mechanism. `BGTaskScheduler` would shorten the exposure
  but cannot be relied on — iOS grants background time at its discretion — so a long lifetime stays
  necessary either way.
- **Unilateral exit** (#19) is no longer part of this list, and the run that moved it is worth
  recording. On 2026-08-17 the drill walked a 90,000 sat VTXO from `Processing` to `Claimed` in
  79m56s with `lark-captaind` scaled to zero — claim `abddf2bd…` at block 3352883, 89,870 sat
  landed on-chain, 130 sat fee. So the server's continued operation is no longer part of the
  envelope: a holder can leave without it, demonstrated rather than asserted.

  Three things had to be fixed before that run could pass, and none of them were visible from a
  green test suite. The Ark connect had no bound, so a server that accepted the connection and then
  said nothing — which is what Fly's edge does with the machine scaled to zero — hung the wallet
  open forever, and a wallet that cannot open cannot reach the exit. `progress_exit` never synced
  the on-chain wallet, so a fee-starved exit could not see the deposit it had just asked for.
  And the drill itself reached for the exit's own CPFP reserve when a resumed exit made the
  off-chain balance read zero.
- **The deadline is visible but passive.** Settings → Advanced now shows the VTXO count and the
  soonest expiry as a real countdown (`block 3,377,744 · in 20 days`), computed at the network's
  actual block spacing — so a user *can* see their own deadline, but only if they go looking. The
  wallet's own warning is still just the "needs a moment" card at the last eighth of the window
  (~2.6 days). Nothing pushes a notification, and nothing warns on the home screen before that.

## After changing the parameter

`vtxo_lifetime` lives in the Fly captaind config, so it takes a redeploy:

```sh
# from the repo root, with the Fly app already set up (docs/deploy/fly-mutinynet-stack.md)
fly deploy -c deploy/fly/fly.captaind.toml
```

Then confirm the running daemon took it — the fork rejects unknown keys outright, so a typo shows up
as a failed boot rather than a silently ignored value:

```sh
fly logs -a lark-captaind | grep -i vtxo_lifetime
```
