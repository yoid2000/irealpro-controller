# Third-party components

VoiceKeys uses these upstream components. This file does not change their licenses.

| Component | Version/source | License |
| --- | --- | --- |
| sherpa-onnx Android library | [v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) | [Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/LICENSE) |
| X-ASR Chinese/English 160 ms streaming Zipformer model | [2026-06-05 quantized export](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models), [upstream project](https://github.com/Gilgamesh-J/X-ASR) | [Apache-2.0](https://github.com/Gilgamesh-J/X-ASR/blob/main/LICENSE) |
| ONNX Runtime, bundled by sherpa-onnx | See upstream AAR | [MIT](https://github.com/microsoft/onnxruntime/blob/main/LICENSE) |
| AndroidX / Jetpack Compose | See app/build.gradle.kts | [Apache-2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) |
| Kotlin standard library | 2.1.20 | [Apache-2.0](https://github.com/JetBrains/kotlin/blob/v2.1.20/license/LICENSE.txt) |

The model is downloaded from the sherpa-onnx `asr-models` release. VoiceKeys uses
continuous greedy transducer decoding with whole-word matching and acoustic
score rejection. Each command occurrence is handled separately, including words
that arrive together in one model chunk.

Binary download URLs and SHA256 checksums are pinned in `scripts/prepare_assets.py`.
Build-time Python test packages are optional and are not included in the APK.
