# Vendored RNNoise (lab only)

- Upstream: https://github.com/xiph/rnnoise
- Pinned commit: `70f1d256acd4b34a572f999a05c87bf00b67730d` (2025-02-22)
- License: BSD-3-Clause, full text in `COPYING` (copied unmodified from the pinned commit)
- Compiled without upstream's autotools `config.h` (no `HAVE_CONFIG_H`); generic C path, no x86 SIMD sources, no runtime CPU dispatch. Only `NDEBUG` is defined.
- Source files are unmodified copies from upstream `src/` (and `include/`).

## Model weights

- `model_version` at the pinned commit: `0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37`
- Archive: https://media.xiph.org/rnnoise/models/rnnoise_data-0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37.tar.gz
  - SHA-256 of the archive: `0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37` (equals the model version, as upstream's `download_model.sh` checks)
- Extracted `rnnoise_data.c`: 78,003,012 bytes, SHA-256 `d6021b7697677c4d2274c912975e143765552b0e6f25500aa660fdb4a9849be5`
- `rnnoise_data.c` is NOT committed (78 MB). It is git-ignored. Run `scripts/fetch-model.sh` once after checkout; it verifies the archive SHA-256 and writes `Sources/CRNNoise/rnnoise_data.c`. `swift test` fails to link until then. `rnnoise_data.h` (small) is committed.

## Files vendored (under `Sources/CRNNoise/`)

C sources: `celt_lpc.c`, `denoise.c`, `kiss_fft.c`, `nnet.c`, `nnet_default.c`, `parse_lpcnet_weights.c`, `pitch.c`, `rnn.c`, `rnnoise_tables.c`, plus fetched `rnnoise_data.c`.

Headers: `arch.h`, `celt_lpc.h`, `common.h`, `cpu_support.h`, `denoise.h`, `_kiss_fft_guts.h`, `kiss_fft.h`, `nnet.h`, `nnet_arch.h`, `opus_types.h`, `pitch.h`, `rnn.h`, `rnnoise_data.h`, `vec.h`, `vec_avx.h`, `vec_neon.h`, `x86/x86_arch_macros.h`, `x86/x86cpu.h`, `include/rnnoise.h` (public).

Not vendored: training code, `dump_features.c`, `dump_rnnoise_tables.c`, `write_weights.c`, `x86/*.c`, `rnnoise_data_little.*`.

## Updating

Change the commit, re-copy the files above, update `scripts/fetch-model.sh` and the hashes here. Ask the owner first (plan 8 owns RNNoise in the product).
