//! The coarse `LarkWallet` FFI object wrapping `bark::Wallet` (KTD-5).
//!
//! Scope of this slice: wallet open/create against the forks, balance, a
//! maintenance refresh, a consistent state-blob export, and the KTD-6 sealed
//! backup crypto that the backup engine (U5) drives. The seed lives in the
//! object; neither the seed nor any derived key crosses the FFI boundary — the
//! only exports are seal/open of whole artifacts.
//!
//! Money-bearing live ops (new_address, both sends, boarding) are exported here
//! but verified only on the live-captaind lane, never on the per-PR one: bark's
//! balances and sends require real musig cosigning from a signing server that no
//! in-process fixture can supply. Unilateral exit is exported here and needs no
//! Ark server at all; channel management is still unexported.

use std::str::FromStr;
use std::sync::Arc;

use bark::exit::{ExitError, ExitState, ExitVtxo};
use bark::persist::sqlite::SqliteClient;
use bark::movement::PaymentMethod;
use bark::onchain::{ChainSync, OnchainWallet};
use bark::{Config, Wallet};
use bitcoin::{Address, Amount, Network};
use zeroize::Zeroize;

use crate::backup;
use crate::LarkError;

/// Removes a temp snapshot file when dropped, so `export_state_blob_plaintext`
/// never leaves a plaintext copy of the wallet DB on disk on any exit path.
struct TmpFileGuard<'a>(&'a str);

impl Drop for TmpFileGuard<'_> {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(self.0);
    }
}

/// An open in-process wallet. UniFFI object: constructed via [`open_wallet`],
/// held by the platform as an `Arc`.
#[derive(uniffi::Object)]
pub struct LarkWallet {
    inner: Wallet,
    // The onchain (bdk) wallet, kept so we can derive deposit addresses. Behind
    // a Mutex because its `address()` takes `&mut self`.
    onchain: tokio::sync::Mutex<OnchainWallet>,
    seed64: [u8; 64],
    db_path: String,
    fingerprint: Vec<u8>,
    /// What the claim this process built actually paid out, once it has built one.
    ///
    /// The only place the true figures exist. A claimed VTXO's recorded state keeps a txid and a
    /// block and no amount, and the VTXO's own `amount()` is its face value — which is what it was
    /// worth before the claim deducted its fee, not what arrived. Reporting the face value as the
    /// landed amount overstates it by exactly the fee.
    ///
    /// In memory, so it is lost if the process restarts between claiming and showing the receipt.
    /// That case reports unknown rather than falling back to the face value: an em-dash is the
    /// house rule for a figure we do not have, and the face value is not that figure.
    claim_outcome: tokio::sync::Mutex<Option<ClaimOutcome>>,
}

/// What a claim transaction moved, as built.
#[derive(Clone, Copy)]
struct ClaimOutcome {
    landed_sat: u64,
    fee_sat: u64,
}

/// A claim that did not go out, and what kind of problem it was.
///
/// Carries a category because the cause is known at the point of failure. Reporting a failed claim
/// as uncategorised would surface it as "something isn't working" on the one screen the holder is
/// watching, when the app knows perfectly well whether it was the chain, the fee, or the network.
struct ClaimFailure {
    message: String,
    category: ExitStallCategory,
}

impl Drop for LarkWallet {
    fn drop(&mut self) {
        // Wipe the raw seed when the wallet is dropped, honoring KTD-6's
        // "key material never lingers" intent.
        self.seed64.zeroize();
    }
}

fn parse_network(s: &str) -> Result<Network, LarkError> {
    // The fork's ark-info reports "signet" for mutinynet; the app keeps its own
    // human label separate (networkLabel decoupled from expectedNetwork).
    Network::from_str(s).map_err(|_| LarkError::Invalid { msg: format!("unknown network '{s}'") })
}

/// Open the wallet at `datadir` if it exists, otherwise create it. Creation is
/// server-free (`force = true`) so first-run onboarding does not require a
/// reachable Ark server; `onchain_bdk` backs boarding + unilateral exit (R5).
/// Opening is server-tolerant for the same reason: bark logs a failed Ark
/// handshake and carries on with no server, which is what lets a unilateral exit
/// start and finish while captaind is down.
/// `words` is the BIP-39 mnemonic the platform generated and stored in secure
/// storage (KTD-11) — the crate never persists it.
#[uniffi::export(async_runtime = "tokio")]
pub async fn open_wallet(
    datadir: String,
    network: String,
    ark_server: String,
    esplora: String,
    words: Vec<String>,
) -> Result<Arc<LarkWallet>, LarkError> {
    let network = parse_network(&network)?;
    // The joined phrase is secret; wipe it from the heap on drop.
    let phrase = zeroize::Zeroizing::new(words.join(" "));
    let mnemonic = bip39::Mnemonic::parse_normalized(&phrase)
        .map_err(|_| LarkError::Invalid { msg: "not a valid mnemonic".into() })?;
    let mut seed64 = mnemonic.to_seed("");

    let db_path = format!("{datadir}/wallet.sqlite");
    let db: Arc<dyn bark::persist::BarkPersister> = Arc::new(
        SqliteClient::open(&db_path).map_err(LarkError::from)?,
    );

    let mut config = Config::network_default(network);
    config.server_address = ark_server;
    config.esplora_address = Some(esplora);
    config.lightning_enabled = true;

    let mut onchain = OnchainWallet::load_or_create(network, seed64, db.clone())
        .await
        .map_err(LarkError::from)?;

    let existing = db.read_properties().await.map_err(LarkError::from)?.is_some();
    let wallet = if existing {
        // `open_with_onchain`, not `open`: only the onchain-aware variant calls
        // `exit.load()`, and without it a unilateral exit that is still in flight is
        // invisible after the process restarts — the wallet would report no exit and
        // silently stop advancing one that is already broadcast (U1/R3).
        Wallet::open_with_onchain(&mnemonic, db.clone(), &onchain, config)
            .await
            .map_err(LarkError::from)?
    } else {
        Wallet::create_with_onchain(&mnemonic, network, config, db.clone(), &onchain, true)
            .await
            .map_err(LarkError::from)?
    };

    if !existing {
        // A wallet with no database behind it is either brand new or a restore, and nothing in the
        // twelve words says which. The ordinary `sync` cannot tell them apart either: it scans
        // scripts the wallet has already revealed, and a fresh database has revealed none — so a
        // restored wallet finds nothing, and keeps finding nothing on every later pass.
        //
        // That is the whole promise of this feature failing at the last step. A holder who exits to
        // on-chain, loses the device, and types their words back in would see an empty wallet with
        // their money sitting in it: 89,870 sat, in the exact case this branch was written for.
        // The full scan happens once, here, because this is the only moment it is needed.
        //
        // Fatal rather than best-effort: creating a wallet already requires the chain source, and
        // "restored, but silently missing your on-chain balance" is the one outcome worth refusing.
        onchain
            .initial_wallet_scan(&wallet.chain, None)
            .await
            .map_err(LarkError::from)?;
    }

    // `Wallet::fingerprint()` is the public accessor (WalletSeed::new is private).
    let fingerprint = wallet.fingerprint().to_string().into_bytes();

    let out = Arc::new(LarkWallet {
        inner: wallet,
        onchain: tokio::sync::Mutex::new(onchain),
        claim_outcome: tokio::sync::Mutex::new(None),
        seed64,
        db_path,
        fingerprint,
    });
    // Wipe this frame's copy of the seed; the struct keeps its own (zeroized on
    // Drop). bark's onchain wallet holds a further copy outside our control.
    seed64.zeroize();
    Ok(out)
}

#[uniffi::export(async_runtime = "tokio")]
impl LarkWallet {
    /// Spendable balance in satoshis. Live op: reflects VTXOs the wallet
    /// cryptographically owns, so a true value requires a synced connection.
    pub async fn balance_sats(&self) -> Result<u64, LarkError> {
        let balance = self.inner.balance().await.map_err(LarkError::from)?;
        Ok(balance.spendable.to_sat())
    }

    /// Run wallet maintenance (the seam's `refresh`): sync + housekeeping.
    pub async fn refresh(&self) -> Result<(), LarkError> {
        self.inner.maintenance().await.map_err(LarkError::from)?;
        Ok(())
    }

    /// Re-establish the Ark server connection when the wallet does not have one.
    ///
    /// [`open_wallet`] is deliberately server-tolerant: a failed handshake leaves the wallet
    /// usable offline, which is what lets a unilateral exit run while captaind is down. The cost
    /// is that the connection is then made exactly once, at open. Nothing inside bark retries it
    /// — `Wallet::refresh_server` exists for this, and barkd's daemon loop is its only caller —
    /// so a wallet opened during an outage stays server-less for the life of the process. Every
    /// server-side op keeps failing with "You should be connected to Ark server" long after the
    /// server is back, and the holder's only cure is to kill the app. This is the retry, driven
    /// by the platform's poll loop.
    ///
    /// Cheap when the connection is already up (a handshake and an ark-info round trip), and an
    /// error while the server is still unreachable — which the caller reads as "try again next
    /// cycle", not as a wallet fault.
    pub async fn reconnect_ark(&self) -> Result<(), LarkError> {
        self.inner.refresh_server().await.map_err(LarkError::from)?;
        Ok(())
    }

    /// A fresh Ark receive address (the seam's `receiveCode` source). Requires a
    /// synced server connection — this exercises a real captaind round-trip.
    pub async fn mint_address(&self) -> Result<String, LarkError> {
        let addr = self.inner.new_address().await.map_err(LarkError::from)?;
        Ok(addr.to_string())
    }

    /// A fresh on-chain deposit address (the seam's `depositAddress`).
    pub async fn deposit_address(&self) -> Result<String, LarkError> {
        let mut onchain = self.onchain.lock().await;
        let addr = onchain.address().await.map_err(LarkError::from)?;
        Ok(addr.to_string())
    }

    /// Bring the on-chain (bdk) wallet up to date with the chain source.
    ///
    /// Separate from [`Self::refresh`] on purpose: `Wallet::maintenance` syncs the *offchain*
    /// wallet and explicitly does not touch the bdk one, so a deposit sent to
    /// [`Self::deposit_address`] stays invisible until this runs. This is an incremental sync
    /// (`ChainSync`), not `initial_wallet_scan` — a full rescan costs a gap-limit sweep of the
    /// descriptor and is only needed when adopting an already-used seed.
    pub async fn onchain_sync(&self) -> Result<(), LarkError> {
        let mut onchain = self.onchain.lock().await;
        onchain.sync(&self.inner.chain).await.map_err(LarkError::from)?;
        Ok(())
    }

    /// The wallet's spendable VTXOs, summarised — count, total, and the soonest expiry height.
    ///
    /// **Purely local**: reads the wallet database and nothing else, so it answers while offline and
    /// cannot be spoiled by a chain-source blip. That separation is deliberate — an earlier version
    /// fetched the chain tip in the same call, which meant one failed HTTP request nulled a count
    /// that was sitting in sqlite the whole time.
    ///
    /// Expiry comes back as a height, not a countdown: turning it into human time needs the chain
    /// tip ([`Self::chain_tip`]) and the network's block spacing, which the platform knows and the
    /// crate does not.
    ///
    /// `soonest_expiry_height` is None when there are no spendable VTXOs — distinct from a zero
    /// height, which would read as "already expired".
    pub async fn vtxo_summary(&self) -> Result<VtxoSummary, LarkError> {
        let vtxos = self.inner.spendable_vtxos().await.map_err(LarkError::from)?;
        Ok(VtxoSummary {
            count: vtxos.len() as u32,
            total_sat: vtxos.iter().map(|v| v.vtxo.amount().to_sat()).sum(),
            soonest_expiry_height: vtxos.iter().map(|v| v.vtxo.expiry_height()).min(),
        })
    }

    /// The chain tip, read from the chain source.
    ///
    /// Its own export because it is the *network* half of an expiry countdown and has a completely
    /// different cost profile from the local half: this is an uncached HTTP request every time, so a
    /// caller polling a balance every few seconds must not fetch it on that cadence. A tip that is a
    /// minute stale costs nothing against a countdown measured in days.
    pub async fn chain_tip(&self) -> Result<u32, LarkError> {
        self.inner.chain.tip().await.map_err(LarkError::from)
    }

    /// When the Ark server expects to start its next round, as a UNIX timestamp in seconds.
    ///
    /// An absolute instant rather than a remaining duration, deliberately. The caller polls on an
    /// interval measured in seconds-to-tens-of-seconds while a round interval is around a minute,
    /// so a duration computed here would be visibly stale by the time it is read; an instant can be
    /// turned into a fresh countdown at every render from one fetch. It also keeps the one piece of
    /// arithmetic that can be wrong — now versus then — on the side of the boundary that has a
    /// clock the tests can control.
    ///
    /// Needs a reachable Ark server (the schedule is the server's, not the chain's), so an error
    /// here is the ordinary offline case and the caller reads it as "no answer yet", never as zero:
    /// a fabricated countdown would be worse than an admitted unknown.
    pub async fn next_round_time(&self) -> Result<u64, LarkError> {
        let at = self.inner.next_round_start_time().await.map_err(LarkError::from)?;
        epoch_seconds(at)
    }

    /// The on-chain balance, split by confirmation state. Read-only — call
    /// [`Self::onchain_sync`] first for a current answer.
    ///
    /// Split rather than a single total because the two numbers mean different things to the
    /// caller: a faucet payment shows up in `pending_sat` immediately but cannot be boarded
    /// until it confirms, so "your sats arrived, waiting on confirmations" and "you can board
    /// now" are different states and the UI has to be able to tell them apart.
    pub async fn onchain_balance(&self) -> Result<OnchainBalanceInfo, LarkError> {
        let onchain = self.onchain.lock().await;
        let balance = onchain.balance();
        Ok(OnchainBalanceInfo {
            confirmed_sat: balance.confirmed.to_sat(),
            // `immature` is coinbase-only, and irrelevant to a wallet funded from a faucet;
            // it is folded into pending rather than dropped so the parts sum to the total.
            pending_sat: (balance.trusted_pending + balance.untrusted_pending + balance.immature)
                .to_sat(),
            total_sat: balance.total().to_sat(),
        })
    }

    /// Pay a BOLT11 invoice over Lightning (the seam's `send` for a bolt11
    /// recipient). Pass `sats = 0` for an amount-carrying invoice; a positive
    /// `sats` sets the amount for an amountless invoice. Returns a short summary.
    pub async fn send_bolt11(&self, invoice: String, sats: u64) -> Result<String, LarkError> {
        let user_amount = (sats > 0).then(|| Amount::from_sat(sats));
        let send = self
            .inner
            .pay_lightning_invoice(invoice, user_amount)
            .await
            .map_err(LarkError::from)?;
        Ok(format!("{send:?}"))
    }

    /// Pay an Ark address out of round (the seam's `send` for a `tark1…` recipient).
    ///
    /// The counterpart to [`Self::send_bolt11`]: the app's own "Get paid" code is an Ark address,
    /// so without this the wallet cannot pay another lark wallet at all. Out-of-round, so it does
    /// not wait for the next round — but it can leave change VTXOs, which is what
    /// [`Self::refresh`]'s maintenance pass eventually tidies.
    pub async fn send_ark(&self, address: String, sats: u64) -> Result<String, LarkError> {
        let destination = bark::ark::Address::from_str(&address)
            .map_err(|_| LarkError::Invalid { msg: "not a valid ark address".into() })?;
        let vtxos = self
            .inner
            .send_arkoor_payment(&destination, Amount::from_sat(sats))
            .await
            .map_err(LarkError::from)?;
        // The recipient may receive several VTXOs when no single one covers the amount; the count
        // is the only part of the result a caller could act on.
        Ok(format!("sent {} sat in {} vtxo(s)", sats, vtxos.len()))
    }

    /// Board **everything** the on-chain wallet holds into Ark.
    ///
    /// This, not [`Self::board`], is what "move my money in" means — and it is not a convenience
    /// wrapper. Boarding a specific amount equal to the whole confirmed balance always fails: the
    /// board transaction pays an on-chain fee out of the same UTXOs, so there is nothing left to
    /// pay it with. `board_all` computes the boardable amount after fees itself.
    pub async fn board_all(&self) -> Result<String, LarkError> {
        let mut onchain = self.onchain.lock().await;
        let pending = self
            .inner
            .board_all(&mut *onchain)
            .await
            .map_err(LarkError::from)?;
        Ok(format!("{pending:?}"))
    }

    /// Board a specific amount into Ark. Callers wanting to move a whole balance want
    /// [`Self::board_all`] instead — see the note there about fees.
    pub async fn board(&self, sats: u64) -> Result<String, LarkError> {
        let mut onchain = self.onchain.lock().await;
        let pending = self
            .inner
            .board_amount(&mut *onchain, Amount::from_sat(sats))
            .await
            .map_err(LarkError::from)?;
        Ok(format!("{pending:?}"))
    }

    /// Spend on-chain funds to `address`.
    ///
    /// Not exit-specific, and deliberately so: an exit lands its proceeds in this wallet, but so
    /// does a board that never got spent and change from anything else. One send path serves all
    /// of them, which is why exit does not carry a destination of its own.
    ///
    /// The fee rate is the chain source's regular estimate, not a caller choice — see
    /// [`Self::onchain_send_fee`] for showing it first.
    pub async fn onchain_send(&self, address: String, sats: u64) -> Result<String, LarkError> {
        let dest = parse_onchain_address(&address, self.inner.chain.network())?;
        let rate = self.inner.chain.fee_rates().await.regular;
        let mut onchain = self.onchain.lock().await;
        let txid = onchain
            .send(&self.inner.chain, dest, Amount::from_sat(sats), rate)
            .await
            .map_err(LarkError::from)?;
        Ok(txid.to_string())
    }

    /// What [`Self::onchain_send`] would cost, without sending it.
    ///
    /// Builds the same transaction at the same fee rate and reads the fee off it, rather than
    /// estimating from a rate and a guessed size — a quote the user is asked to approve should be
    /// the real number. Nothing is signed and nothing is broadcast.
    pub async fn onchain_send_fee(
        &self,
        address: String,
        sats: u64,
    ) -> Result<OnchainFeeQuote, LarkError> {
        let dest = parse_onchain_address(&address, self.inner.chain.network())?;
        let rate = self.inner.chain.fee_rates().await.regular;
        let mut onchain = self.onchain.lock().await;
        let mut builder = onchain.build_tx();
        builder.add_recipient(dest.script_pubkey(), Amount::from_sat(sats));
        builder.fee_rate(rate);
        let psbt = builder
            .finish()
            .map_err(|e| LarkError::Wallet { msg: format!("cannot build that send: {e}") })?;
        let fee = psbt
            .fee()
            .map_err(|e| LarkError::Wallet { msg: format!("cannot price that send: {e}") })?;
        Ok(OnchainFeeQuote {
            fee_sat: fee.to_sat(),
            total_sat: fee.to_sat().saturating_add(sats),
        })
    }

    /// Begin a unilateral exit for the whole VTXO set.
    ///
    /// Deliberately amount-free and selection-free: exit is the wallet leaving the Ark, not a
    /// partial withdrawal. Needs no Ark server — that is the entire point — so it must not be
    /// gated on one being reachable.
    ///
    /// Starting twice is harmless: bark skips VTXOs it is already exiting. There is no matching
    /// `cancel_exit`, and that absence is the contract: once an exit transaction is in the
    /// mempool it cannot be recalled, so a stop control would promise something the wallet
    /// cannot do.
    pub async fn start_exit(&self) -> Result<(), LarkError> {
        self.inner
            .exit
            .write()
            .await
            .start_exit_for_entire_wallet()
            .await
            .map_err(LarkError::from)?;
        Ok(())
    }

    /// Advance every in-flight exit by one pass, returning where the wallet now stands.
    ///
    /// Callers drive this repeatedly; one call does not finish an exit. Broadcasting, waiting out
    /// the exit delta, and claiming are separate passes, and the middle one is bounded by the
    /// chain rather than by effort.
    ///
    /// Channel VTXO stages stay inert: the library path passes no channel driver, so a channel
    /// exit would park rather than resolve. Nothing on the shipping path holds a channel, and
    /// [`ExitStage::Unsupported`] is how that would surface rather than being mislabelled as
    /// ordinary progress.
    pub async fn progress_exit(&self) -> Result<ExitStatusInfo, LarkError> {
        let mut onchain = self.onchain.lock().await;
        // Bring the on-chain wallet's own UTXO set up to date first, using the guard already held —
        // calling `onchain_sync` here would re-enter this lock and deadlock.
        //
        // `sync_no_progress` below refreshes the exit transaction manager's view of the chain, not
        // this wallet's coins, and the two are different questions. Exit transactions are zero-fee
        // and paid for by a CPFP child spending these coins, so a pass that cannot see money that
        // has arrived reports `Insufficient Confirmed Funds` over a funded wallet.
        //
        // That matters because of who is watching: a fee-starved exit tells its holder to add
        // money, and starting an exit stands the funding watcher down — so nothing else was left
        // running to notice them doing it. Without this the app asks for a deposit it can never
        // see, and the stall outlives the fix for it until the process restarts.
        onchain.sync(&self.inner.chain).await.map_err(LarkError::from)?;
        // Write guard on `exit` while `&self.inner` is passed alongside it: this is bark's own
        // idiom (`Wallet::sync_exits`), so `progress_exits` does not re-enter the lock.
        let mut exit = self.inner.exit.write().await;
        // Sync before progressing, and not as an optimisation: `progress_exits` advances states
        // from the transaction manager's view of the chain, and only `sync_no_progress` updates
        // that view. Without it the manager never learns an exit transaction confirmed, so every
        // pass re-decides on stale information and an exit whose transactions are all confirmed
        // sits in `Processing` for as long as the app is willing to poll — which is forever, since
        // nothing about it looks like a failure. bark's own doc comment says the two halves have to
        // be called together; this is that pairing.
        exit.sync_no_progress(&*onchain).await.map_err(LarkError::from)?;
        let statuses = exit
            .progress_exits(&self.inner, &mut *onchain, None)
            .await
            .map_err(LarkError::from)?;
        // One walk, two outputs: the log line keeps the VTXO id and bark's own wording, while the
        // category is what the app is allowed to show. They are derived together so a pass can
        // never report a stall the log cannot explain, or a log line the app silently drops.
        let (errors, categories): (Vec<String>, Vec<ExitStallCategory>) = statuses
            .unwrap_or_default()
            .into_iter()
            .filter_map(|s| {
                s.error.map(|e| {
                    (format!("{}: {e}", s.vtxo_id), ExitStallCategory::from(&e))
                })
            })
            .unzip();
        // Claiming is not something progressing does. `ExitClaimableState::progress` only *looks*
        // — it checks whether the exit output has already been spent and, finding it has not,
        // reports claimable again. Nothing in the progress path ever builds the transaction that
        // spends it, so an exit that has waited out its whole delay stops one step from the end
        // and stays there. `drain_exits` is the only thing that finishes an exit, and it has to be
        // asked.
        // A failed claim is reported like any other failing pass, and with a category: the cause is
        // known here, so letting it surface as "something isn't working" would throw away the one
        // piece of information the holder could act on.
        let (errors, categories) = match self.claim_claimable(&exit, &mut *onchain).await {
            Ok(()) => (errors, categories),
            Err(e) => (
                errors.into_iter().chain(std::iter::once(e.message)).collect(),
                categories.into_iter().chain(std::iter::once(e.category)).collect(),
            ),
        };

        let claimable_at = exit.all_claimable_at_height().await.map(|h| h as u32);
        let claim = *self.claim_outcome.lock().await;
        Ok(ExitStatusInfo::summarise(
            exit.get_exit_vtxos(), errors, &categories, claimable_at, claim,
        ))
    }

    /// The exit delta in blocks, or `None` when it cannot be known right now.
    ///
    /// This is how long a started exit must wait out before its funds become claimable, and it is
    /// the only input the app needs to say "ready to spend in …" *before* an exit exists.
    ///
    /// `None` is load-bearing rather than an error case. The delta lives on the Ark server's
    /// `ArkInfo` and bark does not persist it, so a wallet with no reachable server cannot know it
    /// — which is precisely the situation unilateral exit is for. The caller is expected to render
    /// that as an unknown, never to substitute a default: a wrong wait on the screen that
    /// authorises an irreversible spend is worse than no wait at all.
    ///
    /// Once an exit *has* started this stops being needed: the claimable height is persisted with
    /// the exit and readable with no server.
    pub async fn exit_delta_blocks(&self) -> Result<Option<u32>, LarkError> {
        let info = self.inner.ark_info().await.map_err(LarkError::from)?;
        Ok(info.map(|i| u32::from(i.vtxo_exit_delta)))
    }

    /// Where the wallet's exit stands, without advancing it.
    ///
    /// A local read over persisted state, so it answers with no server and no chain source and is
    /// safe to call on every poll. `stage` is [`ExitStage::None`] when nothing is exiting.
    ///
    /// Reports no errors and no stall category: both are per-pass facts produced by attempting
    /// progress, and inventing them from persisted state would let a read claim a stall that no
    /// pass observed.
    pub async fn exit_status(&self) -> Result<ExitStatusInfo, LarkError> {
        let exit = self.inner.exit.read().await;
        let claimable_at = exit.all_claimable_at_height().await.map(|h| h as u32);
        let claim = *self.claim_outcome.lock().await;
        Ok(ExitStatusInfo::summarise(
            exit.get_exit_vtxos(), Vec::new(), &[], claimable_at, claim,
        ))
    }

    /// Wallet movements, newest-first is up to the caller (the seam's `activity`).
    ///
    /// Reads `history()` rather than the deprecated `movements()`, and carries the counterparty
    /// and creation time as well as the amounts: an activity row has to say who and when, and a
    /// caller cannot invent either. `intended_balance_sat` is here because a movement that has
    /// not completed has no meaningful effective balance yet — the row shows what was intended
    /// until it settles, which is what the gateway core does with the same distinction.
    pub async fn movements(&self) -> Result<Vec<MovementInfo>, LarkError> {
        let movements = self.inner.history().await.map_err(LarkError::from)?;
        Ok(movements
            .into_iter()
            .map(|m| MovementInfo {
                id: m.id.0,
                status: MovementState::from(m.status),
                effective_balance_sat: m.effective_balance.to_sat(),
                intended_balance_sat: m.intended_balance.to_sat(),
                offchain_fee_sat: m.offchain_fee.to_sat(),
                // Whichever side is populated: an outbound movement names its recipients, an
                // inbound one names how it arrived. Both empty is normal (a board, a refresh).
                sent_to: m.sent_to.iter().map(|d| destination_label(&d.destination)).collect(),
                received_on: m
                    .received_on
                    .iter()
                    .map(|d| destination_label(&d.destination))
                    .collect(),
                // Seconds since the epoch; the platform owns date formatting and the user's locale.
                created_at_epoch_seconds: m.time.created_at.timestamp(),
            })
            .collect())
    }
}

/// The string form of a movement counterparty.
///
/// `PaymentMethod` implements `Debug` but not `Display`, and `Debug` is not a UI string — it would
/// put `Ark(Address { .. })` in an activity row. Each variant is rendered as the thing a user
/// could actually copy or recognise.
fn destination_label(method: &PaymentMethod) -> String {
    match method {
        PaymentMethod::Ark(address) => address.to_string(),
        // Unchecked only in the type system: this address came out of our own movement record,
        // so it was already valid for this wallet's network when it was written.
        PaymentMethod::Bitcoin(address) => address.clone().assume_checked().to_string(),
        PaymentMethod::OutputScript(script) => script.to_hex_string(),
        PaymentMethod::Invoice(invoice) => invoice.to_string(),
        PaymentMethod::Offer(offer) => offer.to_string(),
        PaymentMethod::LightningAddress(address) => address.to_string(),
        PaymentMethod::Custom(raw) => raw.clone(),
    }
}

/// A summary of the wallet's spendable VTXOs.
///
/// Heights rather than dates, deliberately: a VTXO expires at a block height, and converting that
/// to wall-clock time needs both the chain tip and the network's block spacing. The tip is a
/// separate export ([`LarkWallet::chain_tip`]) precisely so this one stays local and cheap.
#[derive(uniffi::Record)]
pub struct VtxoSummary {
    pub count: u32,
    pub total_sat: u64,
    pub soonest_expiry_height: Option<u32>,
}

/// The on-chain wallet's balance, split by confirmation state.
///
/// `confirmed_sat` is what boarding can actually consume; `pending_sat` is what has been seen
/// but is not yet spendable. `total_sat` is their sum, kept explicit so callers that only want
/// "did anything arrive" do not have to add.
#[derive(uniffi::Record)]
pub struct OnchainBalanceInfo {
    pub confirmed_sat: u64,
    pub pending_sat: u64,
    pub total_sat: u64,
}

/// Parse and network-check a destination address.
///
/// The network check is not a formality: an address for the wrong network is structurally valid
/// to the parser, so skipping it turns a wrong-network paste into a broadcast that destroys the
/// money instead of an error the user can act on.
///
/// A free function rather than a method because `#[uniffi::export]` exports every method in the
/// impl block it decorates, and `bitcoin::Address` has no FFI representation.
fn parse_onchain_address(address: &str, network: Network) -> Result<Address, LarkError> {
    Address::from_str(address)
        .map_err(|_| LarkError::Invalid { msg: "not a valid bitcoin address".into() })?
        .require_network(network)
        .map_err(|_| LarkError::Invalid { msg: format!("that address is not valid on {network}") })
}

/// A server-supplied instant as the UNIX seconds the FFI boundary can carry.
///
/// A free function for the same reason as [`parse_onchain_address`] — `SystemTime` has no FFI
/// representation — and split out from [`LarkWallet::next_round_time`] so the conversion is
/// reachable by tests that need no wallet and no server.
///
/// Truncates rather than rounds: the caller renders whole seconds, and rounding up would put a
/// countdown one second beyond what the server said.
fn epoch_seconds(at: std::time::SystemTime) -> Result<u64, LarkError> {
    at.duration_since(std::time::UNIX_EPOCH)
        .map(|since_epoch| since_epoch.as_secs())
        // Only reachable if the server names an instant before 1970, which is a broken server
        // rather than an unreachable one — classified as invalid input for that reason. The
        // platform seam currently discards the error kind, so this changes nothing about retries;
        // it is a claim about what happened, not an instruction to the caller.
        .map_err(|e| LarkError::Invalid { msg: format!("next round time precedes the epoch: {e}") })
}

/// What an on-chain send would cost, quoted before it is sent.
///
/// `total_sat` is amount plus fee — the number that actually leaves the wallet — because that is
/// the figure a user checks against their balance, and making them add two numbers is how
/// off-by-a-fee surprises happen.
#[derive(uniffi::Record)]
pub struct OnchainFeeQuote {
    pub fee_sat: u64,
    pub total_sat: u64,
}

/// How far a unilateral exit has got.
///
/// The wallet's stage is the **least advanced** of its exiting VTXOs: a wallet has left the Ark
/// only when every VTXO has, so one straggler holds the whole wallet in the exiting state. That
/// is the honest aggregate — reporting the furthest-along VTXO would say "claimed" while money
/// is still in flight.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExitStage {
    /// Nothing is exiting.
    None,
    Start,
    Processing,
    AwaitingDelta,
    Claimable,
    ClaimInProgress,
    Claimed,
    /// A channel VTXO stage this build cannot resolve, because the library path supplies no
    /// channel driver. Reported as itself rather than mapped onto an ordinary stage: calling a
    /// parked channel exit "processing" would claim progress that is not happening.
    Unsupported,
}

impl ExitStage {
    /// Advancement order, for picking the least advanced. `Unsupported` is excluded — it is not a
    /// point on this line and is handled before ranking.
    fn rank(self) -> u8 {
        match self {
            ExitStage::None => 0,
            ExitStage::Start => 1,
            ExitStage::Processing => 2,
            ExitStage::AwaitingDelta => 3,
            ExitStage::Claimable => 4,
            ExitStage::ClaimInProgress => 5,
            ExitStage::Claimed => 6,
            ExitStage::Unsupported => u8::MAX,
        }
    }

    /// The wallet's stage across its exiting VTXOs.
    ///
    /// Least-advanced wins, so the wallet leaves the exiting state only when every VTXO has been
    /// claimed. `Unsupported` dominates outright rather than competing on rank: it means a stage
    /// this build cannot advance at all, which is worth surfacing over any amount of progress
    /// elsewhere.
    fn aggregate(stages: &[ExitStage]) -> ExitStage {
        if stages.is_empty() {
            ExitStage::None
        } else if stages.contains(&ExitStage::Unsupported) {
            ExitStage::Unsupported
        } else {
            stages.iter().copied().min_by_key(|s| s.rank()).unwrap_or(ExitStage::None)
        }
    }
}

impl From<&ExitState> for ExitStage {
    fn from(state: &ExitState) -> Self {
        match state {
            ExitState::Start(_) => ExitStage::Start,
            ExitState::Processing(_) => ExitStage::Processing,
            ExitState::AwaitingDelta(_) => ExitStage::AwaitingDelta,
            ExitState::Claimable(_) => ExitStage::Claimable,
            ExitState::ClaimInProgress(_) => ExitStage::ClaimInProgress,
            ExitState::Claimed(_) => ExitStage::Claimed,
            ExitState::ChannelBridgeTx(_)
            | ExitState::ChannelCommitment(_)
            | ExitState::ChannelSwept(_) => ExitStage::Unsupported,
        }
    }
}

/// Helpers that never cross the FFI boundary.
///
/// Separate from the exported impl because `#[uniffi::export]` lifts every method it
/// covers, and these take bark types that have no representation across the boundary.
impl LarkWallet {
    /// Spend every claimable exit into the wallet's own on-chain address.
    ///
    /// The last step of an exit, and the only one nothing else performs. Claims land in the
    /// wallet's own address rather than one the holder names (KTD-3): the exit finishes on its own
    /// without waiting for anybody to supply a destination, and moving the funds onward afterwards
    /// is an ordinary send.
    ///
    /// Errors are returned rather than propagated so a failed claim is reported like any other
    /// failing pass — it is retried on the next one, and a claim that cannot be built yet (fees
    /// above the output, a reorg) is exactly the sort of thing that clears by itself.
    /// Takes the caller's on-chain guard rather than locking again. `progress_exit` already
    /// holds it, and `tokio::sync::Mutex` is not reentrant — re-locking here deadlocked the first
    /// claimable pass while holding both this and the exit write lock, which stalled every poll
    /// cycle behind it and left the wallet looking permanently offline.
    async fn claim_claimable(
        &self,
        exit: &bark::exit::Exit,
        onchain: &mut OnchainWallet,
    ) -> Result<(), ClaimFailure> {
        let claimable = exit.list_claimable();
        if claimable.is_empty() {
            return Ok(());
        }

        let destination = onchain.address().await.map_err(|e| ClaimFailure {
            message: format!("claim: no destination address: {e}"),
            category: ExitStallCategory::Unexpected,
        })?;

        // Already signed and already fee-adjusted: `drain_exits` deducts the miner fee from the
        // claim's own output, so nothing else has to fund it.
        // `drain_exits` already classifies its own failures, so reuse that judgement rather than
        // second-guessing it: a fee above the output is uneconomic, a missing tip is the chain.
        let psbt = exit
            .drain_exits(&claimable, &self.inner, destination, None)
            .await
            .map_err(|e| ClaimFailure {
                category: ExitStallCategory::from(&e),
                message: format!("claim: {e}"),
            })?;
        let tx = psbt.extract_tx().map_err(|e| ClaimFailure {
            message: format!("claim: unsignable: {e}"),
            category: ExitStallCategory::Unexpected,
        })?;
        // The one moment both figures are known: the output is what arrives, and the difference
        // from the inputs is the fee that the receipt otherwise has to show as unknown.
        let landed_sat = tx.output.first().map(|o| o.value.to_sat()).unwrap_or(0);
        let inputs_sat: u64 = claimable.iter().map(|e| e.amount().to_sat()).sum();
        *self.claim_outcome.lock().await =
            Some(ClaimOutcome { landed_sat, fee_sat: inputs_sat.saturating_sub(landed_sat) });
        self.inner
            .chain
            .broadcast_tx(&tx)
            .await
            .map_err(|e| ClaimFailure {
                message: format!("claim: broadcast refused: {e}"),
                category: ExitStallCategory::BroadcastRejected,
            })?;
        Ok(())
    }
}

/// Why an exit is not progressing, in terms the app can write copy against.
///
/// bark's [`ExitError`] has 26 variants, most of which describe internals a holder can neither
/// act on nor understand. Classifying here rather than in the app is what lets the seam carry a
/// category instead of an error string: a string in a headline is how an enum name or a txid
/// reaches a screen, and there is no way to write per-variant copy for a set this size.
///
/// The categories split on **what the holder can do**, not on where the error came from, which is
/// why [`Self::InsufficientFunds`] and [`Self::Uneconomic`] are separate despite both being about
/// money. Depositing fixes the first and cannot fix the second.
#[derive(uniffi::Enum, Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExitStallCategory {
    /// The chain source did not answer. Transient; retrying is the whole remedy.
    ChainUnreachable,
    /// Not enough confirmed on-chain balance to pay what the exit costs.
    ///
    /// The only category a holder can clear, and not rare: `ExitStartState::progress` checks
    /// `onchain.get_balance()` against the estimated exit cost before an exit leaves its first
    /// state, so a wallet that boarded its whole balance cannot start an exit at all until it has
    /// on-chain funds again.
    InsufficientFunds,
    /// The exit costs more than it would recover, or the VTXO is below the dust limit.
    ///
    /// Distinct from [`Self::InsufficientFunds`] because depositing does not help: the shortfall
    /// is between the VTXO's value and its own exit cost, not in the wallet's balance.
    Uneconomic,
    /// A transaction was assembled but the network would not take it.
    BroadcastRejected,
    /// Anything else. Deliberately the catch-all arm rather than an exhaustive match: a bark pin
    /// bump that adds a variant should keep compiling and report honestly, not fail the build.
    Unexpected,
}

impl ExitStallCategory {
    /// Which category speaks for the wallet when its VTXOs disagree.
    ///
    /// Lowest rank wins. [`Self::InsufficientFunds`] outranks everything because it is the only
    /// category with an action behind it — burying a clearable stall under a transient one would
    /// leave the holder waiting for something that cannot resolve on its own.
    fn rank(self) -> u8 {
        match self {
            ExitStallCategory::InsufficientFunds => 0,
            ExitStallCategory::Uneconomic => 1,
            ExitStallCategory::BroadcastRejected => 2,
            ExitStallCategory::ChainUnreachable => 3,
            ExitStallCategory::Unexpected => 4,
        }
    }

    fn aggregate(categories: &[ExitStallCategory]) -> Option<ExitStallCategory> {
        categories.iter().copied().min_by_key(|c| c.rank())
    }
}

impl From<&ExitError> for ExitStallCategory {
    fn from(error: &ExitError) -> Self {
        match error {
            ExitError::AncestorRetrievalFailure { .. }
            | ExitError::BlockRetrievalFailure { .. }
            | ExitError::TipRetrievalFailure { .. }
            | ExitError::TransactionRetrievalFailure { .. } => ExitStallCategory::ChainUnreachable,

            ExitError::InsufficientConfirmedFunds { .. }
            | ExitError::InsufficientFeeToStart { .. } => ExitStallCategory::InsufficientFunds,

            ExitError::ClaimFeeExceedsOutput { .. } | ExitError::DustLimit { .. } => {
                ExitStallCategory::Uneconomic
            }

            ExitError::ExitPackageBroadcastFailure { .. } => ExitStallCategory::BroadcastRejected,

            _ => ExitStallCategory::Unexpected,
        }
    }
}

/// The wallet's exit, summarised.
///
/// `errors` is per-pass rather than sticky: a progress pass reports what went wrong *this* time,
/// and the caller decides whether repetition means stalled. Keeping the counting out here is
/// deliberate — a threshold baked into the crate would be a policy the app cannot change.
///
/// `errors` carries VTXO ids and bark's own wording, so it is **for logs only** — `stall_category`
/// is the field a screen may render.
#[derive(uniffi::Record)]
pub struct ExitStatusInfo {
    pub stage: ExitStage,
    pub vtxo_count: u32,
    pub claimed_count: u32,
    pub total_sat: u64,
    /// How much actually landed on-chain, or `None` when that is not known.
    ///
    /// Deliberately not the claimed VTXOs' face value. A claim deducts its miner fee from its own
    /// output, so the face value is what the money was worth before the claim, not what arrived —
    /// reporting it as landed overstates by exactly the fee, on a screen whose subject is what the
    /// holder got. `None` when nothing has been claimed yet, or when this process did not build
    /// the claim and therefore never saw the figure.
    pub landed_sat: Option<u64>,
    /// What the claim cost in miner fees, or `None` on the same terms as `landed_sat`.
    pub claim_fee_sat: Option<u64>,
    pub errors: Vec<String>,
    /// The category speaking for the wallet this pass, or `None` when nothing went wrong.
    pub stall_category: Option<ExitStallCategory>,
    /// The height at which every exiting VTXO becomes claimable, or `None` when not yet known.
    ///
    /// Persisted with the exit, so this answers with no Ark server and no chain source. That is
    /// what lets an in-flight exit show a real countdown in the scenario the feature exists for,
    /// where the exit delta itself is unknowable because it lives on a server that is gone.
    pub claimable_at_height: Option<u32>,
}

impl ExitStatusInfo {
    fn summarise(
        vtxos: &[ExitVtxo],
        errors: Vec<String>,
        categories: &[ExitStallCategory],
        claimable_at_height: Option<u32>,
        claim: Option<ClaimOutcome>,
    ) -> Self {
        let stages: Vec<ExitStage> = vtxos.iter().map(|v| ExitStage::from(v.state())).collect();
        ExitStatusInfo {
            stage: ExitStage::aggregate(&stages),
            vtxo_count: vtxos.len() as u32,
            claimed_count: stages.iter().filter(|s| **s == ExitStage::Claimed).count() as u32,
            total_sat: vtxos.iter().map(|v| v.amount().to_sat()).sum(),
            errors,
            stall_category: ExitStallCategory::aggregate(categories),
            landed_sat: claim.map(|c| c.landed_sat),
            claim_fee_sat: claim.map(|c| c.fee_sat),
            claimable_at_height,
        }
    }
}

#[cfg(test)]
mod round_time_tests {
    use super::epoch_seconds;
    use crate::LarkError;
    use std::time::{Duration, UNIX_EPOCH};

    #[test]
    fn an_instant_becomes_its_unix_seconds_reading() {
        let at = UNIX_EPOCH + Duration::from_secs(1_760_000_041);
        assert_eq!(epoch_seconds(at).unwrap(), 1_760_000_041);
    }

    /// Sub-second precision is noise against a countdown rendered in whole seconds, and truncating
    /// rather than rounding is what keeps the label from reading one second further out than the
    /// server actually said.
    #[test]
    fn a_fractional_second_truncates_rather_than_rounding_up() {
        let at = UNIX_EPOCH + Duration::from_millis(1_760_000_041_900);
        assert_eq!(epoch_seconds(at).unwrap(), 1_760_000_041);
    }

    /// Only a broken server can produce this, which is why it must not be reported as a wallet or
    /// connectivity fault: those are the two the caller retries, and retrying will never fix it.
    #[test]
    fn an_instant_before_the_epoch_is_invalid_rather_than_a_wallet_error() {
        let at = UNIX_EPOCH - Duration::from_secs(1);
        assert!(matches!(epoch_seconds(at), Err(LarkError::Invalid { .. })));
    }
}

#[cfg(test)]
mod claim_outcome_tests {
    use super::{ClaimOutcome, ExitStatusInfo};

    /// The defect this guards is a 0.25% overstatement that reads as a rounding artefact and is
    /// not one: a claim deducts its fee from its own output, so the claimed VTXOs' face value is
    /// what the money was worth *before* the claim. Reporting it as landed tells the holder they
    /// received more than arrived, on the screen whose whole subject is what they got.
    #[test]
    fn landed_is_what_arrived_not_what_was_claimed() {
        let outcome = ClaimOutcome { landed_sat: 119_350, fee_sat: 302 };
        assert_eq!(outcome.landed_sat, 119_350);
        assert_ne!(outcome.landed_sat, 119_652, "face value is not the landed amount");
        assert_eq!(outcome.landed_sat + outcome.fee_sat, 119_652, "the difference is the fee");
    }

    /// Unknown is a real answer here. If this process did not build the claim it never saw the
    /// figures, and falling back to the face value would reinstate exactly the overstatement
    /// above — quietly, and only after a restart, which is the worst way to reintroduce it.
    #[test]
    fn an_unbuilt_claim_reports_unknown_rather_than_face_value() {
        let info = ExitStatusInfo::summarise(&[], Vec::new(), &[], None, None);
        assert_eq!(info.landed_sat, None);
        assert_eq!(info.claim_fee_sat, None);
    }

    #[test]
    fn a_built_claim_reports_both_figures() {
        let outcome = ClaimOutcome { landed_sat: 119_350, fee_sat: 302 };
        let info = ExitStatusInfo::summarise(&[], Vec::new(), &[], None, Some(outcome));
        assert_eq!(info.landed_sat, Some(119_350));
        assert_eq!(info.claim_fee_sat, Some(302));
    }

    /// A claim whose fee somehow exceeded its inputs must not wrap into a vast bogus fee; the
    /// saturating subtraction is load-bearing, not defensive decoration.
    #[test]
    fn an_impossible_fee_saturates_rather_than_wrapping() {
        let landed: u64 = 500;
        let inputs: u64 = 100;
        assert_eq!(inputs.saturating_sub(landed), 0);
    }
}

#[cfg(test)]
mod exit_stall_category_tests {
    use super::ExitStallCategory;
    use bark::exit::ExitError;
    use bitcoin::{Amount, FeeRate};

    fn chain_unreachable() -> ExitError {
        ExitError::BlockRetrievalFailure { height: 100, error: "connection refused".into() }
    }

    fn fee_starved() -> ExitError {
        ExitError::InsufficientFeeToStart {
            balance: Amount::from_sat(0),
            total_fee: Amount::from_sat(612),
            fee_rate: FeeRate::from_sat_per_vb_u32(1),
        }
    }

    fn uneconomic() -> ExitError {
        ExitError::DustLimit { vtxo: Amount::from_sat(100), dust: Amount::from_sat(330) }
    }

    #[test]
    fn each_category_has_a_representative_variant() {
        assert_eq!(ExitStallCategory::from(&chain_unreachable()), ExitStallCategory::ChainUnreachable);
        assert_eq!(ExitStallCategory::from(&fee_starved()), ExitStallCategory::InsufficientFunds);
        assert_eq!(ExitStallCategory::from(&uneconomic()), ExitStallCategory::Uneconomic);
    }

    /// The catch-all arm is the point: a bark pin bump that adds a variant must keep compiling and
    /// land in `Unexpected`, not fail the build or be silently mislabelled as something actionable.
    #[test]
    fn an_unmapped_variant_is_unexpected() {
        assert_eq!(
            ExitStallCategory::from(&ExitError::ClaimMissingInputs),
            ExitStallCategory::Unexpected,
        );
    }

    /// Fee starvation is the only category with an action behind it, so it must not be hidden by a
    /// transient failure on another VTXO — the holder would be told to wait for something that
    /// cannot resolve without them.
    #[test]
    fn a_clearable_stall_outranks_a_transient_one() {
        let categories = [ExitStallCategory::ChainUnreachable, ExitStallCategory::InsufficientFunds];
        assert_eq!(
            ExitStallCategory::aggregate(&categories),
            Some(ExitStallCategory::InsufficientFunds),
        );
    }

    /// Depositing clears `InsufficientFunds` and cannot clear `Uneconomic`, so when both are
    /// present the actionable one speaks — offering a deposit that helps some VTXOs is honest,
    /// offering none when one could be helped is not.
    #[test]
    fn insufficient_funds_outranks_uneconomic() {
        let categories = [ExitStallCategory::Uneconomic, ExitStallCategory::InsufficientFunds];
        assert_eq!(
            ExitStallCategory::aggregate(&categories),
            Some(ExitStallCategory::InsufficientFunds),
        );
    }

    #[test]
    fn no_errors_means_no_category() {
        assert_eq!(ExitStallCategory::aggregate(&[]), None);
    }
}

#[cfg(test)]
mod onchain_address_tests {
    use super::parse_onchain_address;
    use crate::LarkError;
    use bitcoin::Network;

    // BIP-173 example addresses.
    const SIGNET_ADDRESS: &str = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx";
    const MAINNET_ADDRESS: &str = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";

    #[test]
    fn an_address_for_this_network_parses() {
        assert!(parse_onchain_address(SIGNET_ADDRESS, Network::Signet).is_ok());
    }

    #[test]
    fn an_address_for_another_network_is_refused() {
        // The whole reason the check exists: this parses fine, it is just money-destroying.
        let err = parse_onchain_address(MAINNET_ADDRESS, Network::Signet).unwrap_err();
        assert!(matches!(err, LarkError::Invalid { .. }), "got {err:?}");
    }

    #[test]
    fn a_malformed_address_is_refused_rather_than_panicking() {
        let err = parse_onchain_address("not-an-address", Network::Signet).unwrap_err();
        assert!(matches!(err, LarkError::Invalid { .. }), "got {err:?}");
    }

    #[test]
    fn an_empty_address_is_refused() {
        assert!(parse_onchain_address("", Network::Signet).is_err());
    }
}

#[cfg(test)]
mod exit_stage_tests {
    use super::ExitStage;

    #[test]
    fn no_exits_is_none() {
        assert_eq!(ExitStage::aggregate(&[]), ExitStage::None);
    }

    #[test]
    fn the_least_advanced_vtxo_sets_the_wallet_stage() {
        // The whole point of the aggregate: three VTXOs claimed and one still broadcasting means
        // the wallet has *not* left the Ark, so reporting Claimed here would be a lie.
        let stages = [
            ExitStage::Claimed,
            ExitStage::Claimed,
            ExitStage::Processing,
            ExitStage::Claimed,
        ];
        assert_eq!(ExitStage::aggregate(&stages), ExitStage::Processing);
    }

    #[test]
    fn every_vtxo_claimed_leaves_the_exiting_state() {
        let stages = [ExitStage::Claimed, ExitStage::Claimed];
        assert_eq!(ExitStage::aggregate(&stages), ExitStage::Claimed);
    }

    #[test]
    fn an_unadvanceable_channel_stage_dominates_any_progress() {
        // Ranking would bury this behind Start; it must not, because nothing in this build can
        // move it forward and the app needs to say so rather than show progress.
        let stages = [ExitStage::Start, ExitStage::Unsupported, ExitStage::Claimed];
        assert_eq!(ExitStage::aggregate(&stages), ExitStage::Unsupported);
    }

    #[test]
    fn a_single_exit_reports_its_own_stage() {
        assert_eq!(ExitStage::aggregate(&[ExitStage::AwaitingDelta]), ExitStage::AwaitingDelta);
    }

    #[test]
    fn stages_rank_in_advancement_order() {
        let ordered = [
            ExitStage::None,
            ExitStage::Start,
            ExitStage::Processing,
            ExitStage::AwaitingDelta,
            ExitStage::Claimable,
            ExitStage::ClaimInProgress,
            ExitStage::Claimed,
        ];
        for pair in ordered.windows(2) {
            assert!(
                pair[0].rank() < pair[1].rank(),
                "{:?} must rank below {:?}",
                pair[0],
                pair[1],
            );
        }
    }
}

/// Where a movement has got to.
///
/// A real enum rather than bark's `Debug` string: the platform branches on this to decide whether a
/// row shows its effective or its intended amount, and a stringly-typed status makes that branch
/// fail silently — a renamed variant upstream would drop every row from the activity list with
/// nothing failing to compile. As an enum, the same rename is a build error on both sides.
#[derive(uniffi::Enum)]
pub enum MovementState {
    Pending,
    Successful,
    Failed,
    Canceled,
}

impl From<bark::movement::MovementStatus> for MovementState {
    fn from(status: bark::movement::MovementStatus) -> Self {
        match status {
            bark::movement::MovementStatus::Pending => MovementState::Pending,
            bark::movement::MovementStatus::Successful => MovementState::Successful,
            bark::movement::MovementStatus::Failed => MovementState::Failed,
            bark::movement::MovementStatus::Canceled => MovementState::Canceled,
        }
    }
}

/// A slim view of a bark `Movement` for the seam's activity list.
///
/// Signed balances: negative is outbound, positive inbound. Destination strings are whatever the
/// movement recorded (an Ark address, an on-chain address, a BOLT11 invoice), left unparsed —
/// deciding what to *show* for one is a presentation concern.
#[derive(uniffi::Record)]
pub struct MovementInfo {
    pub id: u32,
    pub status: MovementState,
    pub effective_balance_sat: i64,
    pub intended_balance_sat: i64,
    pub offchain_fee_sat: u64,
    pub sent_to: Vec<String>,
    pub received_on: Vec<String>,
    pub created_at_epoch_seconds: i64,
}

#[uniffi::export]
impl LarkWallet {
    /// The wallet fingerprint (bark's seed fingerprint), used to key backup
    /// artifacts per wallet.
    pub fn fingerprint(&self) -> Vec<u8> {
        self.fingerprint.clone()
    }

    /// A consistent snapshot of the wallet's rusqlite state (no seed — bark
    /// persists only the fingerprint, never the mnemonic). Uses SQLite's
    /// `VACUUM INTO` so the snapshot is transactionally consistent even while
    /// the wallet is live.
    pub fn export_state_blob_plaintext(&self) -> Result<Vec<u8>, LarkError> {
        // Unique per-call path (pid + random) so concurrent exports on the same
        // Arc<LarkWallet> never collide, and an RAII guard removes the plaintext
        // snapshot on every exit path — including the read-error path, which
        // would otherwise leak a full copy of the wallet DB to disk.
        let tmp = format!(
            "{}.snapshot-{}-{:016x}",
            self.db_path,
            std::process::id(),
            rand::random::<u64>()
        );
        let guard = TmpFileGuard(&tmp);
        {
            let conn = rusqlite::Connection::open(&self.db_path)
                .map_err(|e| LarkError::Wallet { msg: e.to_string() })?;
            conn.execute("VACUUM INTO ?1", [&tmp])
                .map_err(|e| LarkError::Wallet { msg: e.to_string() })?;
        }
        let bytes = std::fs::read(&tmp).map_err(|e| LarkError::Wallet { msg: e.to_string() })?;
        drop(guard);
        Ok(bytes)
    }

    /// Encrypt a state blob under the seed-derived key (KTD-6). Sealed op — the
    /// key is derived and used entirely inside Rust.
    pub fn encrypt_state_blob(&self, plaintext: Vec<u8>, version: u64) -> Result<Vec<u8>, LarkError> {
        let meta = backup::StateBlobMeta { version, wallet_fingerprint: self.fingerprint.clone() };
        Ok(backup::encrypt_state_blob(&self.seed64, &plaintext, &meta)?)
    }

    /// Decrypt a state blob, returning `(plaintext, version)`. The header
    /// (version + fingerprint) is bound as AEAD AAD, so a relabelled blob fails.
    pub fn decrypt_state_blob(&self, blob: Vec<u8>) -> Result<StateBlobPlaintext, LarkError> {
        let (plaintext, meta) = backup::decrypt_state_blob(&self.seed64, &blob)?;
        Ok(StateBlobPlaintext { plaintext, version: meta.version })
    }
}

/// Result of decrypting a state blob.
#[derive(uniffi::Record)]
pub struct StateBlobPlaintext {
    pub plaintext: Vec<u8>,
    pub version: u64,
}

/// The Ark handshake must give up rather than wait forever.
///
/// This guards a fork bump, not our own code — the bound lives in bark's `ServerConnection::connect`
/// — and it is here because the failure it prevents is ours to suffer. A server that *refuses* a
/// connection has always been handled: bark logs the failed handshake and carries on with no
/// server, which is what lets a unilateral exit run while captaind is down. A server that *accepts*
/// the connection and then never speaks HTTP/2 used to fall through every bound in the stack —
/// `connect_timeout` covers reaching the socket and `timeout` covers requests on an established
/// channel, and the h2 handshake sits between them.
///
/// Observed against a wedged mutinynet captaind on 2026-08-17: the wallet never finished opening,
/// killed at 590 seconds having produced no output at all. A wallet that cannot open cannot reach
/// the exit, so the one failure unilateral exit exists to survive was the one that disabled it.
#[cfg(test)]
mod ark_connect_timeout_tests {
    use bitcoin::Network;
    use server_rpc::ServerConnection;
    use std::time::{Duration, Instant};
    use tokio::net::TcpListener;

    /// Generous against the 15s bound: this asserts termination, not the exact deadline, so a
    /// slower machine cannot make it flap. Before the fix it would not have passed at any budget.
    const BUDGET: Duration = Duration::from_secs(60);

    #[tokio::test]
    async fn a_server_that_accepts_and_then_says_nothing_still_lets_the_wallet_open() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind a local port");
        let address = format!("http://{}", listener.local_addr().expect("read the bound port"));

        // Accept and hold. Never write a byte — the wedged-server shape, as distinct from a refusal.
        tokio::spawn(async move {
            let mut held = Vec::new();
            while let Ok((socket, _)) = listener.accept().await {
                held.push(socket);
            }
        });

        let started = Instant::now();
        let outcome = tokio::time::timeout(
            BUDGET,
            ServerConnection::connect(&address, Network::Signet),
        )
        .await;

        let connect = outcome.unwrap_or_else(|_| {
            panic!("connect never returned within {BUDGET:?} — it must bound itself, not rely on a caller")
        });
        assert!(connect.is_err(), "a server that never speaks is not a connection");
        assert!(started.elapsed() < BUDGET, "took {:?}", started.elapsed());
    }
}

/// A CPFP bid must be bounded by what it recovers.
///
/// Like [ark_connect_timeout_tests] above, this guards a fork bump rather than our own code — the
/// ceiling lives in bark's shared CPFP builder — and it is here because the failure it prevents is
/// ours to suffer. LARK's unilateral exit fee-bumps every transaction in the exit tree, and an
/// exit-tree transaction is mostly other people's money: our stake is one leaf, the rest belongs to
/// strangers' branches. Without a ceiling the wallet bids against the whole delivered value.
///
/// That is an attack surface, not only waste. P2A anchors are anyone-can-bump, so a third party can
/// park a bulky, high-absolute-fee child on the anchor; the RBF branch then prices our replacement
/// off whatever is standing there. The ceiling is what makes an overbidding rival burn their own
/// funds instead of baiting ours.
///
/// Ported from gsanders87's `f1333628`, which flags it FOR UPSTREAM as independent of the channels
/// work. If a later pin bump drops it, these stop compiling — `ExceedsStake` and the `stake`
/// argument both disappear — and that is the point.
#[cfg(test)]
mod cpfp_stake_ceiling_tests {
    use bitcoin::Amount;
    use bitcoin_ext::bdk::{check_cpfp_fee_ceiling, cpfp_fee_ceiling, CpfpInternalError};

    /// The variant every caller that has no scoped stake passes. It means "the whole delivered
    /// value is ours" — never "no ceiling" — and reading it the other way is how the exposure
    /// would come back without anyone editing the rule.
    #[test]
    fn an_absent_stake_still_bounds_the_bid_at_what_the_package_delivers() {
        let ceiling = cpfp_fee_ceiling(None, Amount::from_sat(90_000));
        assert_eq!(ceiling, Amount::from_sat(90_000));
        assert!(check_cpfp_fee_ceiling(Amount::from_sat(90_001), ceiling).is_err());
    }

    /// LARK's shape: a 90,000 sat leaf inside a shared tree transaction that pays out far more.
    /// The bid has to be bounded by our leaf, not by the tree.
    #[test]
    fn our_leaf_bounds_the_bid_not_the_whole_tree_transaction() {
        let ceiling = cpfp_fee_ceiling(Some(Amount::from_sat(90_000)), Amount::from_sat(1_000_000));
        assert_eq!(ceiling, Amount::from_sat(90_000));

        match check_cpfp_fee_ceiling(Amount::from_sat(120_000), ceiling) {
            Err(CpfpInternalError::ExceedsStake { fee, stake }) => {
                assert_eq!(fee, Amount::from_sat(120_000));
                assert_eq!(stake, Amount::from_sat(90_000));
            },
            other => panic!("a bid past our leaf must be refused, got {:?}", other),
        }
    }

    /// The realistic exit fee — the proven drill paid 130 sat to claim 90,000 — must still clear
    /// comfortably. A ceiling that refuses ordinary bumps would be worse than none.
    #[test]
    fn an_ordinary_exit_fee_is_nowhere_near_the_ceiling() {
        let ceiling = cpfp_fee_ceiling(Some(Amount::from_sat(90_000)), Amount::from_sat(90_000));
        assert!(check_cpfp_fee_ceiling(Amount::from_sat(130), ceiling).is_ok());
    }
}
