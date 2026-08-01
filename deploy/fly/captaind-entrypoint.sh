#!/usr/bin/env bash
# Render captaind.toml from the baked template + env, run first-boot `create`
# once (guarded by a marker on the persistent volume), then `start`.
#
# Env (Fly secrets): PG_HOST PG_PORT PG_NAME PG_USER PG_PASS
#   BITCOIND_URL BITCOIND_RPC_USER BITCOIND_RPC_PASS
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
  /etc/captaind/captaind.toml.template > "$CONF"

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
