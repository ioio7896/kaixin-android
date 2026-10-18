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

- OpenCC dictionaries/configuration: Apache License 2.0, under
  `app/src/main/assets/opencc/`.
- English word-frequency data: Apache License 2.0, documented in
  `lexicon/en/LICENSE.wordfreq.md`.
- Chinese single-character reading data: MIT, documented in
  `app/src/main/assets/lexicon-notices/LICENSE.pinyin-data.md`. The reading
  index is derived from the desktop project's pinned pinyin data; the bundled
  word corpus mirrors the desktop project's `lexicon/` directory.
- Emoji artwork: Twemoji (CC BY 4.0), OpenMoji (CC BY-SA 4.0), Microsoft Fluent
  Emoji (MIT), and the Fluent Emoji subset from LobeHub (MIT). See
  `app/src/main/assets/emoji_styles/NOTICE.txt` and the license texts in its
  `licenses/` directory.
Check the notices in the asset and data directories before redistributing those
materials or including them in a commercial build.

