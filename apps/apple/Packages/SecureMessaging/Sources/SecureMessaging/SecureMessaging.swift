// The real engine (`LiveMLSEngine`, `makeMLSEngine`) joins this target in M5, behind
// `#if canImport(MLSFFIBindings)`. Until then it only re-exports the Linux-testable core,
// so `import SecureMessaging` already gives plans 6-8 the contract and `FakeMLSEngine`.
@_exported import SecureMessagingCore
