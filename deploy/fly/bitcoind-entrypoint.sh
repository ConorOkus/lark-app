#!/usr/bin/env bash
# Run a Bitcoin Inquisition node on the public mutinynet. Params match the local
# runbook (signetblocktime=30 + mutinynet signetchallenge + seed node). RPC auth
# from env; RPC binds all interfaces but is only reachable on Fly's private 6PN
# (the port is not published publicly in fly.bitcoind.toml).
set -euo pipefail

DATA_DIR="${DATA_DIR:-/data/bitcoin}"
mkdir -p "$DATA_DIR"
CONF="$DATA_DIR/bitcoin.conf"

# Maintenance mode: keep the VM up with the datadir idle (no bitcoind lock) so a
# snapshot can be written to the volume. Toggle with `fly secrets set/unset
# MAINTENANCE=1` (unset triggers a restart back into normal bitcoind mode).
if [ "${MAINTENANCE:-0}" = "1" ]; then
  echo "MAINTENANCE mode: bitcoind not started; VM idle for volume transfer."
  exec sleep infinity
fi

# Fail fast if the binary predates -signetblocktime (i.e. someone swapped in
# upstream bitcoin-inquisition). Without it bitcoind IGNORES the setting with only
# a log line, runs at 600s spacing, and then wedges forever on the first 2016-block
# retarget boundary with "bad-diffbits" — a failure that appears hours later and
# looks like a peer/sync problem. Cheaper to refuse to boot.
# Capture into a variable rather than piping to `grep -q`: grep exits at the first
# match and closes the pipe, bitcoind takes SIGPIPE, and `set -o pipefail` would
# report that as the check failing.
BITCOIND_HELP="$(bitcoind -help -help-debug 2>&1 || true)"
case "$BITCOIND_HELP" in
  *-signetblocktime*) ;;
  *)
    echo "FATAL: bitcoind does not support -signetblocktime; mutinynet needs" >&2
    echo "       benthecarman/bitcoin (see bitcoind.Dockerfile), not upstream." >&2
    echo "       (bitcoind -help returned ${#BITCOIND_HELP} bytes)" >&2
    exit 1
    ;;
esac

# Bitcoin Core requires network-specific settings (rpcbind, rpcallowip, server,
# etc.) inside the [signet] section — only the network selector stays global.
# Fly's .internal (6PN) is IPv6-only, so RPC must bind IPv6 too; the RPC port is
# never published publicly (fly.bitcoind.toml has no [[services]] for 38332).
cat > "$CONF" <<EOF
signet=1
[signet]
server=1
txindex=1
dbcache=${DBCACHE:-1000}
rpcuser=${BITCOIND_RPC_USER}
rpcpassword=${BITCOIND_RPC_PASS}
# Bind ONLY the IPv6 wildcard: on Linux [::] is dual-stack and accepts both the
# IPv6 6PN (captaind at lark-bitcoind.internal) AND IPv4 loopback (bitcoin-cli).
# Binding 0.0.0.0 as well makes the subsequent [::] bind fail (port collision),
# leaving RPC IPv4-only — unreachable over Fly's IPv6-only private net.
rpcbind=[::]:38332
rpcallowip=0.0.0.0/0
rpcallowip=::/0
signetchallenge=512102f7561d208dd9ae99bf497273e16f389bdbd6c4742ddb8e6b216e64fa2928ad8f51ae
signetblocktime=30
addnode=45.79.52.207:38333
EOF

echo "bitcoin.conf rendered (rpcpassword redacted):"
sed 's/^rpcpassword=.*/rpcpassword=***/' "$CONF"

exec bitcoind -datadir="$DATA_DIR" -conf="$CONF"
