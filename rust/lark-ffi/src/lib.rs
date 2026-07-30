//! lark-ffi: in-process Rust core for the lark wallet (M2).
//!
//! A coarse UniFFI surface (KTD-5) mirroring the `LarkCore` seam, wrapping
//! `bark::Wallet` in-process with its own tokio runtime (the bark-ffi
//! precedent). Kotlin (JNA) bindings drive it on Android; Swift bindings on iOS.
//!
//! This crate deliberately keeps *derived key material inside Rust* — the FFI
//! exposes only the sealed backup operations (KTD-6), never a `deriveBackupKey`.

mod backup;
mod wallet;

pub use wallet::LarkWallet;

uniffi::setup_scaffolding!();

// Async FFI methods are annotated `#[uniffi::export(async_runtime = "tokio")]`;
// UniFFI's tokio integration owns the runtime, so the crate does not build one.

/// Top-level FFI error. Coarse by design — the seam maps these to health/errors,
/// and the backup variant never distinguishes wrong-passphrase from tampering.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum LarkError {
    #[error("wallet error: {msg}")]
    Wallet { msg: String },
    #[error("backup error: {msg}")]
    Backup { msg: String },
    #[error("invalid input: {msg}")]
    Invalid { msg: String },
}

impl From<anyhow::Error> for LarkError {
    fn from(e: anyhow::Error) -> Self {
        LarkError::Wallet { msg: format!("{e:#}") }
    }
}

impl From<backup::BackupError> for LarkError {
    fn from(e: backup::BackupError) -> Self {
        LarkError::Backup { msg: e.to_string() }
    }
}

/// Generate a fresh BIP-39 mnemonic (12 or 24 words). The platform persists the
/// returned words in secure storage (KTD-11) — the crate does not store the
/// seed itself.
#[uniffi::export]
pub fn generate_mnemonic(word_count: u8) -> Result<Vec<String>, LarkError> {
    if word_count != 12 && word_count != 24 {
        return Err(LarkError::Invalid { msg: "word_count must be 12 or 24".into() });
    }
    let mnemonic = bip39::Mnemonic::generate(word_count as usize)
        .map_err(|e| LarkError::Invalid { msg: e.to_string() })?;
    Ok(mnemonic.words().map(|w| w.to_string()).collect())
}

/// During restore, recover the seed words from an opt-in seed artifact using the
/// user passphrase. Returns the words so the caller can create/open the wallet;
/// this is the one place the seed legitimately crosses the boundary (the caller
/// is bootstrapping the wallet from it).
#[uniffi::export]
pub fn restore_seed_from_artifact(artifact: Vec<u8>, passphrase: String) -> Result<Vec<String>, LarkError> {
    // Wipe the recovered entropy from the heap once the mnemonic is built; the
    // `Mnemonic` itself zeroizes on drop via bip39's `zeroize` feature.
    let entropy = zeroize::Zeroizing::new(backup::open_seed_artifact(&artifact, passphrase.as_bytes())?);
    let mnemonic = bip39::Mnemonic::from_entropy(&entropy)
        .map_err(|_| LarkError::Invalid { msg: "artifact did not contain a valid mnemonic".into() })?;
    Ok(mnemonic.words().map(|w| w.to_string()).collect())
}

/// Seal seed words into an opt-in, passphrase-protected artifact (KTD-6). Free
/// function so it is callable without a live wallet; the material sealed is the
/// mnemonic entropy, never a seed-derived key.
#[uniffi::export]
pub fn seal_seed_artifact(words: Vec<String>, passphrase: String) -> Result<Vec<u8>, LarkError> {
    // Both the joined phrase and the derived entropy are secret; wipe them from
    // the heap on drop rather than leaving the seed in freed memory.
    let phrase = zeroize::Zeroizing::new(words.join(" "));
    let mnemonic = bip39::Mnemonic::parse_normalized(&phrase)
        .map_err(|_| LarkError::Invalid { msg: "not a valid mnemonic".into() })?;
    let entropy = zeroize::Zeroizing::new(mnemonic.to_entropy());
    Ok(backup::seal_seed_artifact(&entropy, passphrase.as_bytes())?)
}
