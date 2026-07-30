//! The coarse `LarkWallet` FFI object wrapping `bark::Wallet` (KTD-5).
//!
//! Scope of this slice: wallet open/create against the forks, balance, a
//! maintenance refresh, a consistent state-blob export, and the KTD-6 sealed
//! backup crypto that the backup engine (U5) drives. The seed lives in the
//! object; neither the seed nor any derived key crosses the FFI boundary — the
//! only exports are seal/open of whole artifacts.
//!
//! Money-bearing live ops (new_address, send, unilateral exit, channel
//! management) are wired in the follow-up slice against the live-captaind test
//! lane (the plan's revised U2 test split puts those off the per-PR unit lane,
//! since bark's balances/sends require real musig cosigning from a signing
//! server that no in-process fixture can supply).

use std::str::FromStr;
use std::sync::Arc;

use bark::persist::sqlite::SqliteClient;
use bark::onchain::OnchainWallet;
use bark::{Config, Wallet};
use bitcoin::Network;

use crate::backup;
use crate::LarkError;

/// An open in-process wallet. UniFFI object: constructed via [`open_wallet`],
/// held by the platform as an `Arc`.
#[derive(uniffi::Object)]
pub struct LarkWallet {
    inner: Wallet,
    seed64: [u8; 64],
    db_path: String,
    fingerprint: Vec<u8>,
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
    let mnemonic = bip39::Mnemonic::parse_normalized(&words.join(" "))
        .map_err(|_| LarkError::Invalid { msg: "not a valid mnemonic".into() })?;
    let seed64 = mnemonic.to_seed("");

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

    Ok(Arc::new(LarkWallet { inner: wallet, seed64, db_path, fingerprint }))
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
        let tmp = format!("{}.snapshot-{}", self.db_path, std::process::id());
        let _ = std::fs::remove_file(&tmp);
        {
            let conn = rusqlite::Connection::open(&self.db_path)
                .map_err(|e| LarkError::Wallet { msg: e.to_string() })?;
            conn.execute("VACUUM INTO ?1", [&tmp])
                .map_err(|e| LarkError::Wallet { msg: e.to_string() })?;
        }
        let bytes = std::fs::read(&tmp).map_err(|e| LarkError::Wallet { msg: e.to_string() })?;
        let _ = std::fs::remove_file(&tmp);
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
