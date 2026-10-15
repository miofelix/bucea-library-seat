# Third-party notices

The project's original code is licensed under the [MIT License](LICENSE).
Third-party components and assets retain their own licenses.

The Android application's offline captcha recognition bundles `common_old.onnx`, `common_det.onnx`
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

Other runtime dependencies retain the following upstream licenses:

| Component                    | License    | Upstream                                                        |
| ---------------------------- | ---------- | --------------------------------------------------------------- |
| AndroidX and Jetpack Compose | Apache-2.0 | <https://android.googlesource.com/platform/frameworks/support/> |
| Kotlin standard library      | Apache-2.0 | <https://github.com/JetBrains/kotlin>                           |
| Kotlin Coroutines            | Apache-2.0 | <https://github.com/Kotlin/kotlinx.coroutines>                  |
| OkHttp                       | Apache-2.0 | <https://github.com/square/okhttp>                              |
| Okio                         | Apache-2.0 | <https://github.com/square/okio>                                |
| jsoup                        | MIT        | <https://jsoup.org/license>                                     |

OkHttp also includes `publicsuffixes.gz`, compiled from the
[Public Suffix List](https://publicsuffix.org/list/public_suffix_list.dat).
That data is licensed under MPL-2.0, independently of the application's MIT
license. Its upstream notice and the complete MPL-2.0 text are bundled with
the application's offline open-source notices.
