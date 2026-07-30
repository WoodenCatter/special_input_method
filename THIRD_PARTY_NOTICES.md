# Third-party notices

This project includes the following third-party components for offline Chinese
next-text prediction. No network prediction service is used.

## Trime native library and JNI facade

- Project: Trime (Tongwenfeng)
- Source: https://github.com/osfans/trime
- Release / commit: v3.3.11 / `e4e67cdb`
- Files used:
  - `app/src/main/jniLibs/*/librime_jni.so` from the official v3.3.11 per-ABI APKs retained in `D:\Android_Programs\inputds_old`
  - Minimal ABI-compatible Kotlin declarations under `app/src/main/java/com/osfans/trime/`
- License: GNU GPL v3 (upstream files use GPL-3.0-only and GPL-3.0-or-later SPDX identifiers)
- Changes: native libraries are unmodified; the Kotlin surface was reduced to the methods and value types required by prediction.
- License text: `LICENSES/TRIME-GPL-3.0.txt`
- Native SHA-256:
  - arm64-v8a: `B09B6C4CA880F8949FC9D496A5AAABACE0106D174921BABCE34F95C4DF6474EA`
  - armeabi-v7a: `E87AB70F4A75D4FCA2315CBE2D65B5E660C34B69450FEC6C78266D6A9EAE6152`
  - x86: `00D2A729753BA47D27C969E77B6A32955285890B95AF2D0FAACDD00E82D669B7`
  - x86_64: `411C96017404DA8D3005311D3CB335730C104834525332BA420830CC0946498A`

The copied library was statically inspected and contains `PredictDb`,
`Predictor`, `predictor`, and `predict_translator` symbols. It therefore
contains the actual librime-predict plugin, not only base librime.

## librime

- Project: librime
- Source: https://github.com/rime/librime
- Commit bundled by the Trime release: `33e78140250125871856cdc5b42ddc6a5fcd3cd4`
- Runtime version reported by the copied native binary: 1.17.0
- Files used: statically linked inside `librime_jni.so`
- License: BSD 3-Clause
- Changes: none to the binary
- License text: `LICENSES/LIBRIME-BSD-3-CLAUSE.txt`

## librime-predict

- Project: librime-predict
- Source: https://github.com/rime/librime-predict
- Commit bundled by the Trime release: `67c2881914a0242fc14ec7d6877782771b75800c`
- Files used: plugin code statically linked inside `librime_jni.so`
- License: BSD 3-Clause
- Changes: none to the binary
- License text: `LICENSES/LIBRIME-PREDICT-BSD-3-CLAUSE.txt`

## Official librime-predict data-1.0 database

- Project: librime-predict prediction database
- Source: https://github.com/rime/librime-predict/releases/tag/data-1.0
- Release / commit: data-1.0 / `dbe622f`
- File used: `app/src/main/assets/rime_prediction/predict.db`
- Upstream description: prediction database made from Rime Essay + octagram
- File SHA-256: `2A5A2B7C77F8F3D7C0836DFC8FD8B791AC2574D8BD93A3A2BAAAE1EE4861F5BE`
- Size: 7,529,528 bytes
- License treatment: librime-predict code is BSD 3-Clause; the underlying
  Rime Essay data is LGPL v3, so this redistributed database is documented
  conservatively as LGPL v3 data.
- Changes: none
- License text: `LICENSES/RIME-DATA-LGPL-3.0.txt`

## OpenCC

- Project: Open Chinese Convert (OpenCC)
- Source: https://github.com/BYVoid/OpenCC
- Commit bundled by the Trime release: `907bfcbbd3aae86ff04bc8eaca67c8af03108ddf`
- Files used:
  - OpenCC code statically linked inside `librime_jni.so`
  - `s2t.json`, `t2s.json`, `STCharacters.txt`, `STPhrases.txt`,
    `TSCharacters.txt`, and `TSPhrases.txt` under
    `app/src/main/assets/rime_prediction/opencc/`
- License: Apache License 2.0
- Changes: none to the copied files; text dictionaries are compiled to OpenCC
  binary dictionaries in app-private storage on first initialization.
- License text: `LICENSES/OPENCC-APACHE-2.0.txt`

## Content deliberately not copied

The old project's Luna Pinyin, Stroke, Prelude, Rime Essay text file, full
Trime configuration/theme assets, UI, input state machine, and BCI code were
not copied. The prediction-only schema generates its small context dictionary
from this app's existing `pinyin_map.txt`, so no additional general pinyin
dictionary or large raw corpus is packaged.
