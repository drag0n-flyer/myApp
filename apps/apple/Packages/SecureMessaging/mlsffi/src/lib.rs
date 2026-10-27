//! Thin UniFFI layer over mls-rs. No protocol logic of its own.

pub mod engine;
pub mod error;
pub mod storage;

uniffi::setup_scaffolding!();
