//! Binding generator entrypoint (UniFFI 0.28 library mode).
//!
//! Usage:
//!   cargo run --bin uniffi-bindgen -- generate \
//!     --library target/debug/liblark_ffi.dylib \
//!     --language kotlin --out-dir <path>
fn main() {
    uniffi::uniffi_bindgen_main()
}
