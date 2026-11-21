# Third-party notices

The root `LICENSE` applies to the project's original source code. Third-party
code, dictionaries, datasets, and artwork remain under their respective terms;
the MIT license does not relicense them.

Notices and license texts shipped with the app are kept alongside the relevant
assets. In particular:

- Handwriting: Ismantic/Handwritten MobileNetV2 INT8 model, character map and
  derived C preprocessing code (Apache-2.0); Tencent/ncnn runtime (BSD-3-Clause).
  Pinned versions, source links, modifications and licenses are bundled under
  `app/src/main/assets/handwriting/`. No CASIA training dataset is distributed.
- Extended handwriting: PaddleOCR PP-OCRv5 Mobile pretrained recognition model
  and character dictionary (Apache-2.0), converted to NCNN by nihui's reference
  Android project. Pinned sources, checksums and license are in
  `app/src/main/assets/handwriting/extended/`.
- Offline speech: sherpa-ncnn 2.1.10 and kaldi-native-fbank 1.18.7
  (Apache-2.0); Zipformer Chinese 14M model (Apache-2.0), with the Ooura FFT
  notice. Sources, local changes, licenses, model provenance and checksums are
  documented under `third_party/sherpa-ncnn/` and `app/src/main/assets/speech/`.
- OpenCC dictionaries/configuration: Apache License 2.0, under
  `app/src/main/assets/opencc/`.
- English word-frequency data: Apache License 2.0, documented in
  `lexicon/en/LICENSE.wordfreq.md`.
- Chinese single-character reading data: MIT, documented in
  `app/src/main/assets/lexicon-notices/LICENSE.pinyin-data.md`. The reading
  index is derived from the desktop project's pinned pinyin data; the bundled
  word corpus mirrors the desktop project's `lexicon/` directory.
- Emoji sequences and categories come from Unicode Emoji 17.0, with names
  and search keywords from Unicode CLDR 48.2 (Unicode License v3). The
  source notice and license in `data_sources/emoji/` are included in the APK.
Check the notices in the asset and data directories before redistributing those
materials or including them in a commercial build.

