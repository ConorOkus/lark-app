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

/// Probes for the U2 async blocker: an async export that touches **no runtime**.
///
/// The wallet's async surface completes on iOS from a detached task but hangs on
/// Android from anything other than the instrumentation thread
/// (`docs/ffi/kotlin-bindings-status.md`). Two very different things could cause
/// that, and they need different fixes:
///
/// 1. UniFFI's Kotlin foreign-future machinery cannot deliver its continuation
///    callback off the calling thread at all, or
/// 2. only futures parked on the tokio reactor fail to wake the foreign side.
///
/// This future is `Ready` on first poll and is exported *without*
/// `async_runtime = "tokio"`, so it exercises path 1 alone: the poll/continuation
/// round-trip, with no reactor, no waker from a Rust-owned thread, and no I/O.
/// If this hangs off-thread the fault is in the bindings; if it completes, the
/// fault is in how a tokio-parked future wakes the foreign continuation.
///
/// Diagnostic surface, not seam surface — delete once the blocker is resolved.
#[uniffi::export]
pub async fn async_probe_no_runtime(value: u32) -> u32 {
    value
}

/// The other half of the probe: identical shape, but parked on the tokio timer.
///
/// Reaching the sleep's wake path means the reactor must wake the foreign
/// continuation from a thread the caller never entered — the exact condition
/// [`async_probe_no_runtime`] deliberately avoids. Kept trivial (no network, no
/// sockets, no bark) so a difference between the two isolates the runtime rather
/// than anything the wallet does.
#[uniffi::export(async_runtime = "tokio")]
pub async fn async_probe_tokio_timer(millis: u32) -> u32 {
    tokio::time::sleep(std::time::Duration::from_millis(millis as u64)).await;
    millis
}

/// Third probe: parks on the tokio **I/O driver** rather than the timer.
///
/// [`async_probe_tokio_timer`] proves a timer wake reaches the foreign
/// continuation, but the timer and the I/O driver are separate wake paths inside
/// tokio, and the wallet's chain source uses the I/O one (reqwest sockets). This
/// opens a TCP connection and nothing else — no TLS, no HTTP, no bark — so a
/// difference from the timer probe isolates the driver.
///
/// Diagnostic surface, not seam surface — delete once the blocker is resolved.
#[uniffi::export(async_runtime = "tokio")]
pub async fn async_probe_tokio_tcp(addr: String) -> Result<u32, LarkError> {
    let stream = tokio::net::TcpStream::connect(&addr)
        .await
        .map_err(|e| LarkError::Invalid { msg: format!("connect {addr}: {e}") })?;
    drop(stream);
    Ok(1)
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
