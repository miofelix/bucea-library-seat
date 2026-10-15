# Official release acceptance — 1.0.0, 2026-10-15

The official release is **1.0.0 / versionCode 14**, tagged `v1.0.0`. It includes the compact UI, four-column green/red seat cards, automatic loading of all search results, filterable layout cards, inline Favorites booking and the final scrolling-tab behavior. The earlier **0.3.8 / code 13** candidate was never published; its separate implementation checkpoint is in [ADB_ACCEPTANCE_0.3.8.md](ADB_ACCEPTANCE_0.3.8.md).

## Verification

- **76 JVM tests** passed without failures, errors or skips. **69 Python release-tool tests** passed, covering canonical English naming, five-APK metadata/checksums, seven-asset draft readback, mirror selection of the universal APK and compatibility with existing Android update clients.
- Debug, AndroidTest, R8-minified Release and both Lint variants built successfully. Lint reports **0 errors / 50 debug and 51 release advisory warnings**.
- Staging checks the package, version, minimum SDK, disabled debugging and original certificate SHA-256 `211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`.
- **144 independent physical-device tests** passed in **88.835 seconds** with compressed native-library packaging, including OCR recognition and all four ordered-click fixtures. The PKX110 runs Android 16 / API 36; all opt-in live classes are excluded and no tests were skipped. This final run supersedes the pre-compression run of the same 144 tests.
- **Two actual campus read-only tests** passed in **6.974 seconds**, covering native business reads and current-day inventory: **8 regions / 1708 seats / 0 unknown states**. No booking, cancellation or seat-use operation was submitted.
- An SDK-only salted comparison against the original code 12 baseline passed after code 14 testing. Encrypted accounts, selected account, preferences, local favorites and appearance were preserved; ordinary session-cookie refreshes are outside the comparison.

Presentation checks cover short four-column cards, green/red borders, full accessibility status, long-press favorites, immediate available-only filters and local-only booking restrictions. Controlled search checks cover automatic offset traversal, deduplication, partial-result retention, interrupted-request retry, verification continuation, malformed cursors and stale canceled responses. The inline Favorites fixture chooses server-provided dates and start/end times, handles loading/retry/dismiss, requires explicit final confirmation, and returns to Favorites after a synthetic successful submission. Its mutation callback is synthetic.

The layout regression uses real page gestures in a 300dp × 580dp pane at normal and 1.6 font scale. The entire page scrolls down to later seats and back up to the controls. The secondary **地图｜列表** bar scrolls out with the page; switching modes retains the shared visible anchor and separate browsing state. Its visual height is **44dp** at both font scales, with an accessibility touch target that may extend to 48dp.

The compressed ARM64 code 14 Release UI smoke selects actual server start/end times and opens the reservation confirmation preview without submitting. It also visits reservations, Favorites, frequent seats and violations. An actual region's map/list switching preserves tab geometry, the secondary tabs scroll completely out with the page, and scrolling back restores them. The final installed APK and source metadata are verified after the release-preparation commit.

Android 8 / API 26 and actual library-internal Wi-Fi check-in remain untested. The device system font scale is restored to 1.0.

## Artifact and publication

The universal asset is **BUCEA-Library-Seat-v1.0.0.apk**. Four complete independent APKs append `-arm64-v8a`, `-armeabi-v7a`, `-x86` and `-x86_64` before `.apk`, as authorized by the user. Future releases retain this exact `BUCEA-Library-Seat-v<version>.apk` style. GitHub stripped Chinese characters on initial upload and on an asset-name PATCH; strict remote validation kept that initial attempt unpublished, and the user then explicitly chose the English convention. All packages have code 14, the same certificate, minimum SDK and features; each architecture APK contains only its declared ABI. Staging checks every signature and actual ABI before replacing staged output. `SHA256SUMS` contains five entries and `update.json` retains the universal `apk` field plus `apkVariants`; the publisher validates all seven uploaded assets before publishing.

The original universal APK was 102,563,251 bytes. Lossless native-library compression reduces the universal APK to approximately **55.53 MB**, and splitting plus compression reduces ARM64 to approximately **33.81 MB**. All twelve native-library entries and both OCR models are byte-identical after decompression to the prior build; the final archives declare `extractNativeLibs=true` and DEFLATE their native libraries. Android extracts only the device's ABI during installation. The installed-storage tradeoff, model investigation and further optimization options are detailed in [APK_SIZE.md](APK_SIZE.md). ARM32/x86/x86_64 are structurally inspected, not claimed as runtime-tested on this ARM64 phone.

The final committed source, APK hashes, installation readback and GitHub asset verification are recorded below after preparation/publication; released assets are never overwritten.

Mirror deployment and synchronization are **deferred at the user's request**. The Raspberry Pi Tailscale key expired and its known SSH routes were unreachable. The public mirror remains on 0.3.7/code 12 until multi-APK checksum compatibility is deployed and synchronization occurs. This release's ADB installation does not establish a public updater transaction.

Private evidence is ignored under `.private/qa-1.0.0/`; the original salted baseline and earlier code 13 evidence remain under `.private/qa-0.3.8/`.
