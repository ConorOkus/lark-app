#!/usr/bin/env bash
# Render captaind.toml from the baked template + env, run first-boot `create`
# once (guarded by a marker on the persistent volume), then `start`.
#
# Env (Fly secrets): PG_HOST PG_PORT PG_NAME PG_USER PG_PASS
#   BITCOIND_URL BITCOIND_RPC_USER BITCOIND_RPC_PASS
#   LDK_ANNOUNCE_ADDRESS (optional) — the host:port clients should dial to reach
#     the embedded LDK node, e.g. the dedicated IPv4 as "203.0.113.10:9735".
#     Unset means "advertise the bind address", which is only correct when the
#     server actually owns its public address locally.
set -euo pipefail

DATA_DIR="${DATA_DIR:-/data/captaind}"
mkdir -p "$DATA_DIR"
CONF="$DATA_DIR/captaind.toml"

# Render config from the template (values may contain '/', use a non-slash delim).
sed \
  -e "s|__PG_HOST__|${PG_HOST}|g" \
  -e "s|__PG_PORT__|${PG_PORT:-5432}|g" \
  -e "s|__PG_NAME__|${PG_NAME:-captaind}|g" \
  -e "s|__PG_USER__|${PG_USER:-captaind}|g" \
  -e "s|__PG_PASS__|${PG_PASS}|g" \
  -e "s|__BITCOIND_URL__|${BITCOIND_URL}|g" \
  -e "s|__BITCOIND_RPC_USER__|${BITCOIND_RPC_USER}|g" \
  -e "s|__BITCOIND_RPC_PASS__|${BITCOIND_RPC_PASS}|g" \
  -e "s|__LDK_ANNOUNCE_ADDRESS__|${LDK_ANNOUNCE_ADDRESS:-}|g" \
  /etc/captaind/captaind.toml.template > "$CONF"

# An unset announce address must not leave `announce_address = ""` behind: captaind
# parses it as a SocketAddr and would refuse to start. Drop the line so the config
# falls back to advertising listen_address, which is the documented default.
if [ -z "${LDK_ANNOUNCE_ADDRESS:-}" ]; then
  sed -i '/^announce_address = ""$/d' "$CONF"
fi

echo "captaind.toml rendered (secrets redacted):"
sed -E 's/(password|rpc_pass) = .*/\1 = "***"/' "$CONF"

# First-boot create (initializes DB schema + server wallet/mnemonic). Guard with
# a marker so restarts don't re-create. captaind create needs postgres reachable.
if [ ! -f "$DATA_DIR/.created" ]; then
  echo "first boot: captaind create"
  captaind create -C "$CONF"
  touch "$DATA_DIR/.created"
fi

exec captaind start -C "$CONF"
