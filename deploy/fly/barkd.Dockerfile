# barkd — the bark wallet daemon's REST surface, built from the same pinned fork
# as captaind. Clones both forks at their fork-pins.toml SHAs (both public) and
# builds the `barkd` binary. Build context is the lark-app repo root (only the
# entrypoint is copied from context; the forks are cloned in-image).
#
#   docker build -f deploy/fly/barkd.Dockerfile -t lark-barkd:dev .
#
# SECURITY: this daemon has NO authentication — barkd's entire option set is
# --datadir/--port/--host. It holds the wallet seed and can spend. Deployed
# behind a public address (a deliberate, accepted trade for a mutinynet tester
# wallet), anyone who finds the URL can drain it and read the mnemonic. Do not
# reuse this shape for anything holding real value; see docs/deploy/fly-barkd.md.

FROM rust:1-bookworm AS builder
RUN apt-get update && apt-get install -y --no-install-recommends \
      protobuf-compiler pkg-config libssl-dev clang build-essential git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
# Keep in sync with rust/fork-pins.toml.
ARG BARK_SHA=38304d95f2bba98edee2560f2fc2a36f16f0d84f
ARG RL_SHA=0a0a489d634724708dbb194adf849151d2306cb4

# Sibling layout: bark path-deps ../rust-lightning/lightning.
RUN git clone https://github.com/instagibbs/rust-lightning.git rust-lightning \
    && git -C rust-lightning checkout "$RL_SHA"
RUN git clone https://gitlab.com/ConorOkus/bark.git bark \
    && git -C bark checkout "$BARK_SHA"

WORKDIR /src/bark
# Debug build, matching the local runbook (target/debug/barkd) and the captaind
# image: the app is pinned to this fork's exact REST shape (BarkdApiVariant.FORK_BETA6),
# so "same binary as the one the contract was measured against" matters more than speed.
RUN cargo build --bin barkd

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates libssl3 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=builder /src/bark/target/debug/barkd /usr/local/bin/barkd
COPY deploy/fly/barkd-entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh
EXPOSE 3011
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
