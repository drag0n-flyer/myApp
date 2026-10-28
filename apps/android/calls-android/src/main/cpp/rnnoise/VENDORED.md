# Vendored RNNoise

- Upstream: https://github.com/xiph/rnnoise
- Pinned commit: `70f1d256acd4b34a572f999a05c87bf00b67730d` (2025-02-22), the same pin and the same files as the iPhone copy, `apps/apple/Packages/CallMedia/Sources/RNNoise` (see its `VENDORED.md`).
- License: BSD-3-Clause, full text in `LICENSE` (unmodified from the pinned commit's `COPYING`). The in-app license notice must include it.
- Files are unmodified copies of the iPhone copy's files. Built by `../CMakeLists.txt` without autotools `config.h`; `NDEBUG` and `RNNOISE_EXPORT=` are defined there.
- Model weights: `rnnoise_data.c` (78 MB) is NOT committed (git-ignored). Run `scripts/android-fetch-rnnoise-model.sh` once per checkout (also in CI before building). It verifies the same archive SHA-256 as the iPhone script, `0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37`.
- On arm64-v8a and armeabi-v7a, `vec.h` selects NEON kernels.
