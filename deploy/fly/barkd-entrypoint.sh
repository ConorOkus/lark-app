#!/usr/bin/env bash
# Serve an existing bark wallet datadir over barkd's REST API.
#
# Deliberately does NOT create a wallet. The datadir is migrated in from the
# local runbook (see docs/deploy/fly-barkd.md) because it holds the seed and the
# VTXOs; creating one here on an empty volume would silently produce a *second*,
# empty wallet and the app would adopt it and report a zero balance — a failure
# that looks like "the funds vanished" rather than "wrong wallet".
set -euo pipefail

DATA_DIR="${DATA_DIR:-/data/bark}"
PORT="${BARKD_PORT:-3011}"

# Maintenance mode: hold the VM up with the datadir idle and no sqlite lock, so
# the wallet can be streamed onto the volume. Same toggle as the bitcoind image:
# `fly secrets set MAINTENANCE=1` / `fly secrets unset MAINTENANCE` (the unset
# restarts straight back into barkd).
#
# MUST precede the wallet guard below. The first deploy necessarily lands on an
# empty volume, so a guard that ran first would exit(1) and reboot-loop the VM —
# making the transfer window it tells you to use unreachable.
if [ "${MAINTENANCE:-0}" = "1" ]; then
  echo "MAINTENANCE mode: barkd not started; VM idle for datadir transfer."
  exec sleep infinity
fi

# Refuse to start on an unmigrated volume. barkd would otherwise sit there
# answering requests about a wallet that does not exist.
if [ ! -f "$DATA_DIR/db.sqlite" ]; then
  echo "ERROR: no wallet at $DATA_DIR (expected db.sqlite)." >&2
  echo "       The datadir must be migrated in before first start; barkd will not" >&2
  echo "       create one here. See docs/deploy/fly-barkd.md (Migrating the wallet)." >&2
  echo "       Set MAINTENANCE=1 to hold the VM open for the transfer." >&2
  exit 1
fi

# 0.0.0.0, not loopback: Fly's proxy reaches the VM over its private address, so
# a loopback bind is unreachable and presents as a connection refused at the edge.
exec barkd --datadir "$DATA_DIR" --host 0.0.0.0 --port "$PORT"
