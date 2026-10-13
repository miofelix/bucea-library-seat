# Third-party notices

The Android application's offline captcha recognition bundles `common_old.onnx`
and `CHARSET_OLD` from [sml2h3/ddddocr](https://github.com/sml2h3/ddddocr), version
1.6.1. The initial source export was made from local revision
`c40f56f95412e10bcb9bd0bd24411e92f896d238`.

These assets are distributed under the upstream MIT license. Its complete text
is included in the APK as `assets/ocr/LICENSE.ddddocr.txt`. Asset hashes and
preprocessing settings are recorded in `assets/ocr/model.json`.

`LanczosPreprocessor.kt` reproduces Pillow's separable Lanczos3 resampling
algorithm and integer grayscale conversion. This implementation is original
Kotlin code; no Pillow source is bundled.

The app uses Microsoft's ONNX Runtime Android package for local inference,
distributed under the MIT license: <https://github.com/microsoft/onnxruntime>.
No captcha image or account credential is sent to an OCR service.
