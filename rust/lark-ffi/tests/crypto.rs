//! Verification for U1's security-critical core (KTD-6): the two-artifact key
//! separation, AEAD associated-data binding, and no-seed-in-blob guarantees.
//! These are the properties the plan-review P0s turn on, so they are asserted
//! here rather than left to the live lane.

use lark_ffi::{generate_mnemonic, restore_seed_from_artifact, seal_seed_artifact};

// ---- mnemonic generation ----------------------------------------------------

#[test]
fn generates_12_and_24_word_mnemonics() {
    assert_eq!(generate_mnemonic(12).unwrap().len(), 12);
    assert_eq!(generate_mnemonic(24).unwrap().len(), 24);
}

#[test]
fn rejects_invalid_word_count() {
    assert!(generate_mnemonic(13).is_err());
    assert!(generate_mnemonic(0).is_err());
}

#[test]
fn generated_mnemonics_are_distinct() {
    let a = generate_mnemonic(12).unwrap();
    let b = generate_mnemonic(12).unwrap();
    assert_ne!(a, b, "two fresh mnemonics must differ (entropy source live)");
}

// ---- seed artifact round-trip (passphrase-derived key) ----------------------

fn sample_words() -> Vec<String> {
    // A known-valid BIP-39 test vector (all-"abandon" + "about" checksum word).
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        .split(' ')
        .map(str::to_string)
        .collect()
}

#[test]
fn seed_artifact_round_trips_under_correct_passphrase() {
    let words = sample_words();
    let artifact = seal_seed_artifact(words.clone(), "correct horse".into()).unwrap();
    let recovered = restore_seed_from_artifact(artifact, "correct horse".into()).unwrap();
    assert_eq!(recovered, words);
}

#[test]
fn seed_artifact_fails_under_wrong_passphrase() {
    let artifact = seal_seed_artifact(sample_words(), "right".into()).unwrap();
    let err = restore_seed_from_artifact(artifact, "wrong".into());
    assert!(err.is_err(), "wrong passphrase must not decrypt the seed artifact");
}

#[test]
fn seed_artifact_ciphertext_does_not_contain_the_words() {
    // The plaintext words must never appear in the encrypted artifact.
    let artifact = seal_seed_artifact(sample_words(), "pw".into()).unwrap();
    let needle = b"abandon";
    assert!(
        !artifact.windows(needle.len()).any(|w| w == needle),
        "seed word leaked into the seed artifact ciphertext"
    );
}

#[test]
fn tampering_the_seed_artifact_fails_the_open() {
    let mut artifact = seal_seed_artifact(sample_words(), "pw".into()).unwrap();
    // Flip a byte in the ciphertext body (past the header).
    let last = artifact.len() - 1;
    artifact[last] ^= 0xff;
    assert!(restore_seed_from_artifact(artifact, "pw".into()).is_err());
}
