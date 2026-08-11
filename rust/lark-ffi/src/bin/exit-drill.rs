//! The unilateral exit drill: proof that a wallet can leave the Ark without the Ark server.
//!
//! The app makes a promise — "you don't need permission from anyone to do this" — and this is
//! what keeps it honest. It walks board -> exit -> claim -> withdraw against a real stack and
//! prints every state transition, so the property can be demonstrated rather than asserted.
//!
//! It is kept rather than discarded after it first passes. The exit engine comes from a pinned
//! fork (`rust/fork-pins.toml`) that moves, and this is the only thing that would notice a bump
//! breaking exit. That is the same discipline the fork pin's contract suite already applies,
//! extended to the one property the README promises publicly.
//!
//! Run it twice: once against a healthy stack, and once with captaind stopped. The second run is
//! the one that matters — every step here must work with no Ark server reachable.
//!
//! ## Configuration
//!
//! | Variable | Meaning |
//! | --- | --- |
//! | `LARK_DRILL_DATADIR` | wallet directory (required) |
//! | `LARK_DRILL_MNEMONIC` | 12-word BIP-39 phrase (required) |
//! | `LARK_DRILL_ESPLORA` | chain source URL (required) |
//! | `LARK_DRILL_ARK` | Ark server URL; may be unreachable, that is the point |
//! | `LARK_DRILL_NETWORK` | defaults to `signet` |
//! | `LARK_DRILL_WITHDRAW` | on-chain address to sweep to; omitted skips the withdraw phase |
//! | `LARK_DRILL_POLL_SECS` | seconds between progress passes (default 30) |
//! | `LARK_DRILL_BUDGET_MINS` | give up after this long (default 300) |
//!
//! ## Exit codes
//!
//! `0` passed, `1` failed, `2` skipped. Skip is its own code rather than a quiet success because
//! a lane that skips silently reports green while proving nothing — the failure documented in
//! `docs/solutions/test-failures/silently-skipped-test-lane-passes-ci.md`.

use std::env;
use std::process::ExitCode;
use std::time::{Duration, Instant};

use lark_ffi::{open_wallet, ExitStage, LarkWallet};

const PASS: u8 = 0;
const FAIL: u8 = 1;
const SKIP: u8 = 2;

/// Consecutive errored passes before the drill calls an exit stalled and gives up.
///
/// The app never gives up — it reports the stall and keeps retrying, because there is no honest
/// way out of the exiting state. A drill is not the app: it has a person waiting on it, so it
/// stops and says what it saw.
const STALL_LIMIT: u32 = 3;

#[tokio::main]
async fn main() -> ExitCode {
    let started = Instant::now();
    match run(started).await {
        Ok(()) => {
            report(started, "PASS", "the wallet left the Ark and the funds are on-chain");
            ExitCode::from(PASS)
        }
        Err(Outcome::Skip(why)) => {
            report(started, "SKIP", &why);
            eprintln!("note: a skip is not a pass — nothing about unilateral exit was proven.");
            ExitCode::from(SKIP)
        }
        Err(Outcome::Fail(why)) => {
            report(started, "FAIL", &why);
            ExitCode::from(FAIL)
        }
    }
}

enum Outcome {
    /// The drill could not run. Environmental, not evidence about exit.
    Skip(String),
    /// The drill ran and exit did not work.
    Fail(String),
}

async fn run(started: Instant) -> Result<(), Outcome> {
    let cfg = Config::from_env()?;

    // Preflight is the skip boundary. Opening the wallet is the first thing that touches the
    // stack, so a failure here is about configuration or the environment, not about exit.
    // Everything after this line is evidence, and any failure past it is a real one.
    let wallet = open_wallet(
        cfg.datadir.clone(),
        cfg.network.clone(),
        cfg.ark.clone(),
        cfg.esplora.clone(),
        cfg.mnemonic.clone(),
    )
    .await
    .map_err(|e| Outcome::Skip(format!("could not open a wallet against this stack: {e}")))?;

    let tip = wallet
        .chain_tip()
        .await
        .map_err(|e| Outcome::Skip(format!("chain source unreachable: {e}")))?;
    log(started, tip, "opened the wallet");

    ensure_something_to_exit(&wallet, &cfg, started).await?;
    drive_exit_to_claimed(&wallet, &cfg, started).await?;
    withdraw(&wallet, &cfg, started).await?;
    Ok(())
}

/// Board on-chain funds if the wallet has nothing off-chain to exit.
///
/// A board is only spendable once it has confirmed *and* the wallet has registered it, and
/// registration happens inside maintenance rather than on a balance read — so this polls
/// `refresh` rather than waiting on confirmations alone.
async fn ensure_something_to_exit(
    wallet: &LarkWallet,
    cfg: &Config,
    started: Instant,
) -> Result<(), Outcome> {
    let balance = wallet.balance_sats().await.map_err(fail("could not read the balance"))?;
    if balance > 0 {
        log(started, 0, &format!("{balance} sat off-chain already — no boarding needed"));
        return Ok(());
    }

    wallet.onchain_sync().await.map_err(fail("could not sync the on-chain wallet"))?;
    let onchain = wallet.onchain_balance().await.map_err(fail("could not read on-chain funds"))?;
    if onchain.confirmed_sat == 0 {
        return Err(Outcome::Skip(format!(
            "nothing to exit: no VTXOs and no confirmed on-chain funds \
             ({} sat pending). Fund {} and re-run.",
            onchain.pending_sat, cfg.datadir,
        )));
    }

    log(started, 0, &format!("boarding {} sat", onchain.confirmed_sat));
    wallet.board_all().await.map_err(fail("boarding failed"))?;

    let deadline = Instant::now() + cfg.budget;
    while Instant::now() < deadline {
        wallet.refresh().await.map_err(fail("maintenance failed while registering the board"))?;
        let balance = wallet.balance_sats().await.map_err(fail("could not read the balance"))?;
        if balance > 0 {
            log(started, 0, &format!("board registered — {balance} sat spendable"));
            return Ok(());
        }
        tokio::time::sleep(cfg.poll).await;
    }
    Err(Outcome::Fail("the board never became spendable within the budget".into()))
}

/// Start the exit and drive it to `Claimed`, printing every stage change.
async fn drive_exit_to_claimed(
    wallet: &LarkWallet,
    cfg: &Config,
    started: Instant,
) -> Result<(), Outcome> {
    let status = wallet.exit_status().await.map_err(fail("could not read exit status"))?;
    if status.stage == ExitStage::None {
        wallet.start_exit().await.map_err(fail("could not start the exit"))?;
        log(started, 0, "started a unilateral exit for the whole wallet");
    } else {
        // Resuming is normal, not exceptional: an exit outlives any one run of anything.
        log(started, 0, &format!("resuming an exit already at {:?}", status.stage));
    }

    let deadline = Instant::now() + cfg.budget;
    let mut last_stage = ExitStage::None;
    let mut consecutive_errors = 0u32;

    while Instant::now() < deadline {
        let status = wallet.progress_exit().await.map_err(fail("a progress pass failed"))?;
        let tip = wallet.chain_tip().await.unwrap_or(0);

        if status.stage != last_stage {
            log(
                started,
                tip,
                &format!(
                    "{:?} — {} vtxo(s), {} claimed, {} sat",
                    status.stage, status.vtxo_count, status.claimed_count, status.total_sat,
                ),
            );
            last_stage = status.stage;
        }

        if status.errors.is_empty() {
            consecutive_errors = 0;
        } else {
            consecutive_errors += 1;
            log(started, tip, &format!("pass reported: {}", status.errors.join("; ")));
            if consecutive_errors >= STALL_LIMIT {
                return Err(Outcome::Fail(format!(
                    "exit stalled — {STALL_LIMIT} consecutive failing passes: {}",
                    status.errors.join("; "),
                )));
            }
        }

        match status.stage {
            ExitStage::Claimed => {
                log(started, tip, "every VTXO claimed — the wallet has left the Ark");
                return Ok(());
            }
            ExitStage::Unsupported => {
                return Err(Outcome::Fail(
                    "the exit reached a channel stage this build cannot advance".into(),
                ));
            }
            _ => tokio::time::sleep(cfg.poll).await,
        }
    }

    Err(Outcome::Fail(format!("exit did not finish within {} minutes", cfg.budget.as_secs() / 60)))
}

/// Sweep the claimed funds to a holder-named address, proving the escape hatch end to end.
async fn withdraw(wallet: &LarkWallet, cfg: &Config, started: Instant) -> Result<(), Outcome> {
    let Some(address) = cfg.withdraw.clone() else {
        log(started, 0, "no LARK_DRILL_WITHDRAW set — stopping at claimed, funds are on-chain");
        return Ok(());
    };

    wallet.onchain_sync().await.map_err(fail("could not sync the on-chain wallet"))?;
    let onchain = wallet.onchain_balance().await.map_err(fail("could not read on-chain funds"))?;
    if onchain.confirmed_sat == 0 {
        return Err(Outcome::Fail(
            "the exit claimed but no confirmed on-chain funds appeared".into(),
        ));
    }

    // Quote first, then send what the quote priced: the drill exercises the same two-step the
    // user gets, rather than a shortcut that would not prove the quote works.
    let quote = wallet
        .onchain_send_fee(address.clone(), onchain.confirmed_sat)
        .await
        .map_err(fail("could not price the withdrawal"))?;
    let amount = onchain.confirmed_sat.saturating_sub(quote.fee_sat);
    if amount == 0 {
        return Err(Outcome::Fail(format!(
            "the fee ({} sat) consumes the whole {} sat balance",
            quote.fee_sat, onchain.confirmed_sat,
        )));
    }

    log(started, 0, &format!("withdrawing {amount} sat to {address} (fee {} sat)", quote.fee_sat));
    let txid = wallet
        .onchain_send(address, amount)
        .await
        .map_err(fail("the withdrawal failed"))?;
    log(started, 0, &format!("withdrawal broadcast: {txid}"));
    Ok(())
}

struct Config {
    datadir: String,
    mnemonic: Vec<String>,
    esplora: String,
    ark: String,
    network: String,
    withdraw: Option<String>,
    poll: Duration,
    budget: Duration,
}

impl Config {
    fn from_env() -> Result<Self, Outcome> {
        Ok(Config {
            datadir: required("LARK_DRILL_DATADIR")?,
            mnemonic: required("LARK_DRILL_MNEMONIC")?
                .split_whitespace()
                .map(str::to_string)
                .collect(),
            esplora: required("LARK_DRILL_ESPLORA")?,
            // Not required: a run with captaind stopped still needs an address to fail to reach.
            ark: env::var("LARK_DRILL_ARK").unwrap_or_default(),
            network: env::var("LARK_DRILL_NETWORK").unwrap_or_else(|_| "signet".into()),
            withdraw: env::var("LARK_DRILL_WITHDRAW").ok().filter(|s| !s.is_empty()),
            poll: Duration::from_secs(number("LARK_DRILL_POLL_SECS", 30)),
            budget: Duration::from_secs(number("LARK_DRILL_BUDGET_MINS", 300) * 60),
        })
    }
}

fn required(key: &str) -> Result<String, Outcome> {
    env::var(key)
        .ok()
        .filter(|v| !v.is_empty())
        .ok_or_else(|| Outcome::Skip(format!("{key} is not set")))
}

fn number(key: &str, fallback: u64) -> u64 {
    env::var(key).ok().and_then(|v| v.parse().ok()).unwrap_or(fallback)
}

/// Turn a wallet error into a drill failure. Past preflight, nothing is environmental.
fn fail(what: &str) -> impl Fn(lark_ffi::LarkError) -> Outcome + '_ {
    move |e| Outcome::Fail(format!("{what}: {e}"))
}

fn log(started: Instant, tip: u32, message: &str) {
    let elapsed = started.elapsed().as_secs();
    let at = if tip > 0 { format!("block {tip}") } else { "—".to_string() };
    println!("[{:02}:{:02}] {at:>14}  {message}", elapsed / 60, elapsed % 60);
}

fn report(started: Instant, verdict: &str, detail: &str) {
    let elapsed = started.elapsed().as_secs();
    println!("\n{verdict} after {:02}:{:02} — {detail}", elapsed / 60, elapsed % 60);
}
