# Bitcoin Inquisition node joined to the public mutinynet (custom 30s-block
# signet). RPC is bound for Fly's private network only (never published publicly).
#
# MUST be benthecarman's Inquisition fork, NOT upstream bitcoin-inquisition:
# mutinynet never retargets difficulty (nBits is pinned at the signet powLimit
# 0x1e0377ae at every height). Only this fork implements -signetblocktime, which
# is what suppresses retargeting. Upstream Inquisition logs "Ignoring unknown
# configuration value signet.signetblocktime" and silently runs with 600s
# spacing, so it syncs happily inside a retarget window and then rejects the
# first block on a 2016 boundary with "bad-diffbits, incorrect proof of work"
# (it demands a 4x-harder target). That wedges the node permanently.
FROM debian:bookworm-slim
ARG MUTINYNET_RELEASE=mutinynet-inq-29
ARG MUTINYNET_ASSET=bitcoin-d091f70435c9-x86_64-linux-gnu.tar.gz
ARG MUTINYNET_SHA256=9ec137bbaf7c3187eb138745f77dab5d50e668dd2e0649e46a0bd760415bdf0d
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL "https://github.com/benthecarman/bitcoin/releases/download/${MUTINYNET_RELEASE}/${MUTINYNET_ASSET}" -o /tmp/inq.tar.gz \
    && echo "${MUTINYNET_SHA256}  /tmp/inq.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/inq.tar.gz -C /tmp \
    && cp /tmp/bitcoin-*/bin/bitcoind /tmp/bitcoin-*/bin/bitcoin-cli /usr/local/bin/ \
    && rm -rf /tmp/inq.tar.gz /tmp/bitcoin-* \
    && apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*
COPY deploy/fly/bitcoind-entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh
# P2P (signet 38333) reachable; RPC 38332 stays private (not published in fly.toml).
EXPOSE 38332 38333
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
