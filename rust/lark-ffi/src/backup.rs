//! Backup crypto — the KTD-6 two-artifact design (circularity resolution).
//!
//! Two artifacts with *different trust models*:
//!   * **state blob** — rusqlite snapshot + channel state + descriptors, NO seed.
//!     Encrypted under a key derived from the wallet seed by HKDF-SHA256 on a
//!     dedicated info string. Safe to auto-back-up precisely because the payload
//!     contains no seed.
//!   * **seed artifact** — the encrypted seed, opt-in, encrypted under a key
//!     derived from a user passphrase by Argon2id. NEVER a seed-derived key
//!     (that would be cryptographically circular and worthless).
//!
//! Both use XChaCha20-Poly1305 (24-byte nonce) with the full metadata header
//! bound as AEAD associated data, so a cloud attacker who relabels a blob's
//! version or fingerprint makes the open fail (defeats the R9 downgrade vector).
//!
//! These are the *sealed* operations of KTD-6: derived key material never leaves
//! this module. The FFI surface exposes seal/open, never `deriveBackupKey`.

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305, XNonce,
};
use hkdf::Hkdf;
use rand::RngCore;
use sha2::Sha256;
use zeroize::Zeroize;

pub(crate) const FORMAT_VERSION: u16 = 1;
pub(crate) const ARTIFACT_STATE_BLOB: u8 = 1;
pub(crate) const ARTIFACT_SEED: u8 = 2;

const HKDF_INFO_STATE: &[u8] = b"lark/state-blob/v1";
const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 24;
const KEY_LEN: usize = 32;

// Argon2id parameters (KTD-6): m >= 64 MiB, t >= 3, p >= 4. Pinned and recorded
// in the artifact header so a future hardening bump can re-encrypt on next
// passphrase entry without stranding old artifacts.
const ARGON_M_KIB: u32 = 65_536; // 64 MiB
const ARGON_T: u32 = 3;
const ARGON_P: u32 = 4;

/// Backup crypto failures. Deliberately coarse — callers must not be able to
/// distinguish "wrong passphrase" from "tampered ciphertext" from the outside,
/// both surface as `Decrypt`.
#[derive(Debug, thiserror::Error)]
pub enum BackupError {
    #[error("decryption failed (wrong key/passphrase or tampered data)")]
    Decrypt,
    #[error("malformed artifact: {0}")]
    Malformed(&'static str),
    #[error("unsupported format version {0} (this build supports up to {1})")]
    UnsupportedFormat(u16, u16),
    #[error("wrong artifact type: expected {expected}, found {found}")]
    WrongArtifact { expected: u8, found: u8 },
    #[error("crypto init failed")]
    CryptoInit,
}

/// Metadata bound to the state blob as AEAD associated data. Any mismatch on
/// decrypt (a relabelled version, a swapped fingerprint) fails the open.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StateBlobMeta {
    pub version: u64,
    pub wallet_fingerprint: Vec<u8>,
}

fn derive_state_key(seed: &[u8], salt: &[u8]) -> Result<[u8; KEY_LEN], BackupError> {
    let hk = Hkdf::<Sha256>::new(Some(salt), seed);
    let mut key = [0u8; KEY_LEN];
    hk.expand(HKDF_INFO_STATE, &mut key)
        .map_err(|_| BackupError::CryptoInit)?;
    Ok(key)
}

fn derive_passphrase_key(
    passphrase: &[u8],
    salt: &[u8],
    m_kib: u32,
    t: u32,
    p: u32,
) -> Result<[u8; KEY_LEN], BackupError> {
    use argon2::{Algorithm, Argon2, Params, Version};
    let params = Params::new(m_kib, t, p, Some(KEY_LEN)).map_err(|_| BackupError::CryptoInit)?;
    let argon = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut key = [0u8; KEY_LEN];
    argon
        .hash_password_into(passphrase, salt, &mut key)
        .map_err(|_| BackupError::CryptoInit)?;
    Ok(key)
}

fn seal(key: &[u8; KEY_LEN], nonce: &[u8; NONCE_LEN], aad: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, BackupError> {
    let cipher = XChaCha20Poly1305::new_from_slice(key).map_err(|_| BackupError::CryptoInit)?;
    cipher
        .encrypt(XNonce::from_slice(nonce), Payload { msg: plaintext, aad })
        .map_err(|_| BackupError::CryptoInit)
}

fn open(key: &[u8; KEY_LEN], nonce: &[u8; NONCE_LEN], aad: &[u8], ciphertext: &[u8]) -> Result<Vec<u8>, BackupError> {
    let cipher = XChaCha20Poly1305::new_from_slice(key).map_err(|_| BackupError::CryptoInit)?;
    cipher
        .decrypt(XNonce::from_slice(nonce), Payload { msg: ciphertext, aad })
        .map_err(|_| BackupError::Decrypt)
}

// ---- State blob (seed-derived key) -----------------------------------------

/// Encrypt a state blob under a seed-derived key. `seed` is the 64-byte BIP-39
/// seed; it is used only to derive the key (HKDF) and is never included in the
/// output. Header layout (all big-endian), which is also the AEAD AAD:
///   format_version:u16 | artifact_type:u8 | version:u64 |
///   fp_len:u8 | fingerprint | salt:16 | nonce:24
pub fn encrypt_state_blob(seed: &[u8], plaintext: &[u8], meta: &StateBlobMeta) -> Result<Vec<u8>, BackupError> {
    if meta.wallet_fingerprint.len() > u8::MAX as usize {
        return Err(BackupError::Malformed("fingerprint too long"));
    }
    let mut salt = [0u8; SALT_LEN];
    let mut nonce = [0u8; NONCE_LEN];
    let mut rng = rand::thread_rng();
    rng.fill_bytes(&mut salt);
    rng.fill_bytes(&mut nonce);

    let mut header = Vec::new();
    header.extend_from_slice(&FORMAT_VERSION.to_be_bytes());
    header.push(ARTIFACT_STATE_BLOB);
    header.extend_from_slice(&meta.version.to_be_bytes());
    header.push(meta.wallet_fingerprint.len() as u8);
    header.extend_from_slice(&meta.wallet_fingerprint);
    header.extend_from_slice(&salt);
    header.extend_from_slice(&nonce);

    let mut key = derive_state_key(seed, &salt)?;
    let ct = seal(&key, &nonce, &header, plaintext);
    key.zeroize();
    let ct = ct?;

    let mut out = header;
    out.extend_from_slice(&ct);
    Ok(out)
}

/// Decrypt a state blob under the seed-derived key, returning the plaintext and
/// the authenticated metadata parsed from the header. The header is bound as
/// AAD, so a tampered version/fingerprint fails the open rather than returning
/// altered metadata.
pub fn decrypt_state_blob(seed: &[u8], blob: &[u8]) -> Result<(Vec<u8>, StateBlobMeta), BackupError> {
    let mut c = Cursor::new(blob);
    let format_version = c.u16()?;
    if format_version > FORMAT_VERSION {
        return Err(BackupError::UnsupportedFormat(format_version, FORMAT_VERSION));
    }
    let artifact_type = c.u8()?;
    if artifact_type != ARTIFACT_STATE_BLOB {
        return Err(BackupError::WrongArtifact { expected: ARTIFACT_STATE_BLOB, found: artifact_type });
    }
    let version = c.u64()?;
    let fp_len = c.u8()? as usize;
    let fingerprint = c.take(fp_len)?.to_vec();
    let salt = c.take(SALT_LEN)?.to_vec();
    let nonce_bytes = c.take(NONCE_LEN)?.to_vec();
    let header_len = c.pos;
    let ciphertext = c.rest();

    let mut nonce = [0u8; NONCE_LEN];
    nonce.copy_from_slice(&nonce_bytes);
    let aad = &blob[..header_len];

    let mut key = derive_state_key(seed, &salt)?;
    let pt = open(&key, &nonce, aad, ciphertext);
    key.zeroize();
    let pt = pt?;

    Ok((pt, StateBlobMeta { version, wallet_fingerprint: fingerprint }))
}

// ---- Seed artifact (passphrase-derived key) --------------------------------

/// Seal the seed under a passphrase-derived (Argon2id) key. Header/AAD layout:
///   format_version:u16 | artifact_type:u8 | m_kib:u32 | t:u32 | p:u32 |
///   salt:16 | nonce:24
pub fn seal_seed_artifact(seed_material: &[u8], passphrase: &[u8]) -> Result<Vec<u8>, BackupError> {
    let mut salt = [0u8; SALT_LEN];
    let mut nonce = [0u8; NONCE_LEN];
    let mut rng = rand::thread_rng();
    rng.fill_bytes(&mut salt);
    rng.fill_bytes(&mut nonce);

    let mut header = Vec::new();
    header.extend_from_slice(&FORMAT_VERSION.to_be_bytes());
    header.push(ARTIFACT_SEED);
    header.extend_from_slice(&ARGON_M_KIB.to_be_bytes());
    header.extend_from_slice(&ARGON_T.to_be_bytes());
    header.extend_from_slice(&ARGON_P.to_be_bytes());
    header.extend_from_slice(&salt);
    header.extend_from_slice(&nonce);

    let mut key = derive_passphrase_key(passphrase, &salt, ARGON_M_KIB, ARGON_T, ARGON_P)?;
    let ct = seal(&key, &nonce, &header, seed_material);
    key.zeroize();
    let ct = ct?;

    let mut out = header;
    out.extend_from_slice(&ct);
    Ok(out)
}

/// Open a seed artifact with the passphrase. The Argon2id parameters in the
/// header are enforced to equal the pinned v1 constants before the KDF runs
/// (a future params change is a format-version bump, not a header free-for-all)
/// so a tampered header cannot drive a memory-exhaustion DoS on restore.
pub fn open_seed_artifact(blob: &[u8], passphrase: &[u8]) -> Result<Vec<u8>, BackupError> {
    let mut c = Cursor::new(blob);
    let format_version = c.u16()?;
    if format_version > FORMAT_VERSION {
        return Err(BackupError::UnsupportedFormat(format_version, FORMAT_VERSION));
    }
    let artifact_type = c.u8()?;
    if artifact_type != ARTIFACT_SEED {
        return Err(BackupError::WrongArtifact { expected: ARTIFACT_SEED, found: artifact_type });
    }
    let m_kib = c.u32()?;
    let t = c.u32()?;
    let p = c.u32()?;
    // The Argon2 cost params must be verified BEFORE deriving the key, because
    // derivation runs before the AEAD tag can authenticate the header. A
    // tampered/corrupt artifact could otherwise set m_kib near the argon2 max
    // and OOM-crash the device on every restore attempt. For format v1 the
    // params are fixed, so anything other than the pinned values is rejected.
    if (m_kib, t, p) != (ARGON_M_KIB, ARGON_T, ARGON_P) {
        return Err(BackupError::Malformed("unexpected argon2 parameters"));
    }
    let salt = c.take(SALT_LEN)?.to_vec();
    let nonce_bytes = c.take(NONCE_LEN)?.to_vec();
    let header_len = c.pos;
    let ciphertext = c.rest();

    let mut nonce = [0u8; NONCE_LEN];
    nonce.copy_from_slice(&nonce_bytes);
    let aad = &blob[..header_len];

    let mut key = derive_passphrase_key(passphrase, &salt, m_kib, t, p)?;
    let pt = open(&key, &nonce, aad, ciphertext);
    key.zeroize();
    pt
}

// ---- tiny byte-cursor (no external dep) ------------------------------------

struct Cursor<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Cursor { buf, pos: 0 }
    }
    fn take(&mut self, n: usize) -> Result<&'a [u8], BackupError> {
        let end = self.pos.checked_add(n).ok_or(BackupError::Malformed("length overflow"))?;
        if end > self.buf.len() {
            return Err(BackupError::Malformed("truncated artifact"));
        }
        let s = &self.buf[self.pos..end];
        self.pos = end;
        Ok(s)
    }
    fn u8(&mut self) -> Result<u8, BackupError> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16, BackupError> {
        Ok(u16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn u32(&mut self) -> Result<u32, BackupError> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn u64(&mut self) -> Result<u64, BackupError> {
        Ok(u64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }
    fn rest(&self) -> &'a [u8] {
        &self.buf[self.pos..]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seed(byte: u8) -> [u8; 64] {
        [byte; 64]
    }
    fn meta() -> StateBlobMeta {
        StateBlobMeta { version: 7, wallet_fingerprint: b"fp-abc".to_vec() }
    }

    #[test]
    fn state_blob_round_trips_and_recovers_metadata() {
        let s = seed(1);
        let blob = encrypt_state_blob(&s, b"sqlite-snapshot-bytes", &meta()).unwrap();
        let (pt, m) = decrypt_state_blob(&s, &blob).unwrap();
        assert_eq!(pt, b"sqlite-snapshot-bytes");
        assert_eq!(m, meta());
    }

    #[test]
    fn state_blob_fails_under_a_different_seed() {
        let blob = encrypt_state_blob(&seed(1), b"x", &meta()).unwrap();
        assert!(matches!(decrypt_state_blob(&seed(2), &blob), Err(BackupError::Decrypt)));
    }

    #[test]
    fn tampering_the_version_in_the_header_fails_the_open() {
        // version is bound as AAD; relabelling it (the R9 downgrade vector) must
        // fail the AEAD rather than silently returning altered metadata.
        let s = seed(3);
        let mut blob = encrypt_state_blob(&s, b"payload", &meta()).unwrap();
        // The version u64 sits at offset 3 (u16 format + u8 type). Bump it.
        blob[3] ^= 0x01;
        assert!(matches!(decrypt_state_blob(&s, &blob), Err(BackupError::Decrypt)));
    }

    #[test]
    fn state_key_and_seed_artifact_key_never_cross_decrypt() {
        // Encrypt a state blob (seed-derived) and a seed artifact (passphrase-
        // derived); neither opener accepts the other's ciphertext.
        let s = seed(4);
        let state = encrypt_state_blob(&s, b"state", &meta()).unwrap();
        let sealed = seal_seed_artifact(b"entropy-bytes", b"passphrase").unwrap();

        // state blob is not a seed artifact (wrong artifact type is caught first)
        assert!(open_seed_artifact(&state, b"passphrase").is_err());
        // seed artifact is not a state blob
        assert!(decrypt_state_blob(&s, &sealed).is_err());
    }

    #[test]
    fn seed_artifact_round_trips_and_wrong_passphrase_fails() {
        let sealed = seal_seed_artifact(b"my-entropy", b"hunter2").unwrap();
        assert_eq!(open_seed_artifact(&sealed, b"hunter2").unwrap(), b"my-entropy");
        assert!(matches!(open_seed_artifact(&sealed, b"nope"), Err(BackupError::Decrypt)));
    }

    #[test]
    fn state_blob_never_embeds_the_seed() {
        // The seed derives the key but must never appear in the output.
        let s = seed(0xAB);
        let blob = encrypt_state_blob(&s, b"payload", &meta()).unwrap();
        assert!(
            !blob.windows(8).any(|w| w == &s[..8]),
            "seed bytes leaked into the state blob"
        );
    }

    #[test]
    fn seed_artifact_rejects_out_of_policy_argon_params_before_kdf() {
        // A tampered header with a huge memory cost must be rejected as
        // Malformed (cheap) rather than driving a multi-GiB Argon2 allocation.
        let mut blob = seal_seed_artifact(b"entropy", b"pw").unwrap();
        // m_kib is the u32 right after format(2)+type(1): offset 3..7.
        blob[3..7].copy_from_slice(&0xFFFF_FFFFu32.to_be_bytes());
        assert!(matches!(open_seed_artifact(&blob, b"pw"), Err(BackupError::Malformed(_))));
    }

    #[test]
    fn truncated_artifacts_error_rather_than_panic() {
        // The exact variant depends on how far the header parses before running
        // out of bytes; the property under test is "errors, never panics".
        assert!(decrypt_state_blob(&seed(1), b"short").is_err());
        assert!(open_seed_artifact(b"short", b"pw").is_err());
        // A well-formed-but-truncated state-blob header must be Malformed.
        let mut blob = encrypt_state_blob(&seed(1), b"x", &meta()).unwrap();
        blob.truncate(blob.len() - 4);
        assert!(matches!(decrypt_state_blob(&seed(1), &blob), Err(BackupError::Decrypt) | Err(BackupError::Malformed(_))));
    }
}
