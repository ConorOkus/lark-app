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
| `vtxo_lifetime` | 86,400 blocks | **~30 days** |
| `vtxo_exit_delta` | 144 blocks | ~72 minutes |
| `round_interval` | 30s | — |
| `required_board_confirmations` | 3 | ~90 seconds |
| `min_board_amount` | 20,000 sat | — |

The app warns before the deadline: `STALE_THRESHOLD_DIVISOR` in `GatewayLarkCore` puts the
"needs a moment" card at the last eighth of the window, i.e. **~3.75 days before expiry**.

## The bound

**Safe to leave closed: about 25 days.** Derived as the 30-day lifetime, minus the ~3.75 days of
warning window the app wants in order to have told the user anything at all, minus a margin for the
worst-case unilateral exit (measured at ~2h45m in the M2 planning work) and a few confirmations.

Opening the app and letting a refresh complete resets the clock. In practice a tester who opens LARK
once a week is never near the edge.

## Why 30 days and not the 72 hours it was

`vtxo_lifetime` was 8,640 blocks. On a 30-second chain that is **72 hours** — so a tester who
downloaded the build on Friday and came back on Tuesday could find their money gone, having done
nothing wrong. Worse, the app's own warning window would be ~9 hours, which is not a warning anyone
sees on a phone they check daily.

The value only binds newly created VTXOs, so raising it does not rescue anything already in flight.

## What is still missing

- **Background refresh** (#28). Until it exists, this bound is enforced by the user remembering to
  open an app, which is the weakest possible mechanism. `BGTaskScheduler` would shorten the exposure
  but cannot be relied on — iOS grants background time at its discretion — so a long lifetime stays
  necessary either way.
- **Unilateral exit** (#19) is still a stub. If the server vanished, the exit path a user would need
  is not in the app. That makes the server's continued operation part of the envelope, not a
  fallback: this is a test network, and that trade is acceptable here and nowhere else.
- **No VTXO expiry display.** The in-process core cannot list VTXOs yet, so Advanced shows an
  em-dash where the soonest expiry would go. A user cannot currently see their own deadline.

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
