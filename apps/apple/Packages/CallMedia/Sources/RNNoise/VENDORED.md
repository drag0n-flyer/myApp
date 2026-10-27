# Vendored RNNoise

- Upstream: https://github.com/xiph/rnnoise
- Pinned commit: `70f1d256acd4b34a572f999a05c87bf00b67730d` (2025-02-22), the same pin as the call lab (`tools/call-lab/Packages/RNNoiseLab`, see its `VENDOR.md` for file lists and model hashes).
- License: BSD-3-Clause, full text in `LICENSE` (unmodified from the pinned commit's `COPYING`). The in-app license notice must include it.
- Files are unmodified copies of the lab's vendored `src/` and `include/` files, including the small `rnnoise_data.h`. Built without autotools `config.h`; `NDEBUG` and `RNNOISE_EXPORT=` are defined in `Package.swift`.
- Model weights: `rnnoise_data.c` (78 MB) is NOT committed (git-ignored). Run `scripts/fetch-rnnoise-model.sh` once per checkout (also in CI before `swift test`). It verifies the archive SHA-256 `0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37`.
- On arm64, `vec.h` selects NEON kernels; Linux x86 tests do not exercise that path.
