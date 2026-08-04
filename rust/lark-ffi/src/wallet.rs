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
//! in-process fixture can supply. Unilateral exit and channel management are
//! still unexported.

use std::str::FromStr;
use std::sync::Arc;

use bark::persist::sqlite::SqliteClient;
use bark::movement::PaymentMethod;
use bark::onchain::{ChainSync, OnchainWallet};
use bark::{Config, Wallet};
use bitcoin::{Amount, Network};
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

    let onchain = OnchainWallet::load_or_create(network, seed64, db.clone())
        .await
        .map_err(LarkError::from)?;

    let wallet = if db.read_properties().await.map_err(LarkError::from)?.is_some() {
        Wallet::open(&mnemonic, db.clone(), config).await.map_err(LarkError::from)?
    } else {
        Wallet::create_with_onchain(&mnemonic, network, config, db.clone(), &onchain, true)
            .await
            .map_err(LarkError::from)?
    };

    // `Wallet::fingerprint()` is the public accessor (WalletSeed::new is private).
    let fingerprint = wallet.fingerprint().to_string().into_bytes();

    let out = Arc::new(LarkWallet {
        inner: wallet,
        onchain: tokio::sync::Mutex::new(onchain),
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
