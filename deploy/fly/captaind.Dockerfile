# captaind (bark-server) built from Greg's fork with embedded LDK channels.
# Clones both forks at their fork-pins.toml SHAs (both public) and builds the
# `captaind` binary. Build context is the lark-app repo root (only the entrypoint
# is copied from context; the forks are cloned in-image).
#
#   docker build -f deploy/fly/captaind.Dockerfile -t lark-captaind:dev .

FROM rust:1-bookworm AS builder
RUN apt-get update && apt-get install -y --no-install-recommends \
      protobuf-compiler pkg-config libssl-dev libpq-dev clang build-essential git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
# Keep in sync with rust/fork-pins.toml.
ARG BARK_SHA=f05e944d930d9484195c1bbac22f05c2900e6eb6
ARG RL_SHA=0a0a489d634724708dbb194adf849151d2306cb4

# Sibling layout: bark path-deps ../rust-lightning/lightning.
RUN git clone https://github.com/instagibbs/rust-lightning.git rust-lightning \
    && git -C rust-lightning checkout "$RL_SHA"
# The pinned SHA lives on ConorOkus/bark (gsanders87/bark, the upstream fork,
# no longer has it — its branch advanced past this commit). Keep in sync with
# rust/fork-pins.toml (which needs correcting to this remote).
RUN git clone https://gitlab.com/ConorOkus/bark.git bark \
    && git -C bark checkout "$BARK_SHA"

WORKDIR /src/bark
# Debug build: matches the local runbook (target/debug/captaind) and compiles far
# faster than release — fine for a mutinynet tester stack.
RUN cargo build -p bark-server --bin captaind

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates libssl3 libpq5 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=builder /src/bark/target/debug/captaind /usr/local/bin/captaind
COPY deploy/fly/captaind.toml.template /etc/captaind/captaind.toml.template
COPY deploy/fly/captaind-entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh
# gRPC (public, TLS-terminated by Fly), LDK p2p.
EXPOSE 3535 9735
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
