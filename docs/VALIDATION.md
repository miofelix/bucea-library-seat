# Validation — 2026-10-13

## Build

- `:app:assembleDebug` — passed.
- `:app:assembleDebugAndroidTest` — passed.
- `:app:lintDebug` — passed with 0 errors. Remaining warnings concern available dependency upgrades, synchronous vault writes, and optional KTX APIs.
- `git diff --check` — passed.

## Device via adb

Android 16 / SDK 36, arm64 physical device; app minimum SDK 26, target SDK 35.

`adb shell am instrument -w cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner` returned **OK (6 tests)**:

1. Bundled ddddocr ONNX model recognizes the real captcha fixture twice on the phone.
2. Encrypted fake accounts and separate cookies survive storage reload; deleting one leaves the other intact.
3. Updating an account clears only its saved session.
4. An invalidated cookie jar cannot restore a deleted session from a late response.
5. Releasing a stale native cookie jar preserves the newer session saved by WebView.
6. Restored cookies obey host, path, security and expiry restrictions.

The test vault uses a unique fake preference namespace. These tests do not open or clear the user's production vault.

Manual adb checks confirmed adding a real account through the native UI, automatic captcha login, rendering real campus seat statistics, launching the authenticated mobile function center, and restoring the account after process restart / APK update.

## OCR reference parity

Compared Kotlin image preprocessing with local ddddocr / Pillow on seven cases: real captcha, upstream sample, RGB upsampling/downsampling, grayscale, RGBA resampling, and unchanged-size RGBA. All output pixels matched. The Kotlin CTC check covers blanks and consecutive duplicate characters.

## Official site on a mobile viewport

Checked the authenticated `/self`, `/map`, `/freeBook/fav`, `/history?type=SEAT`, `/user/violations`, `/user/password` pages at a 412px viewport. Form, input-control and link counts remained identical before and after adaptation; document width stayed within the viewport. Private live page captures remain in ignored `.private/`.

Verified original date/filter controls and AJAX seat search return seat results. Verified the room selector opens the original seat map, preserved horizontal map geometry inside a scrollable viewport, and selecting a seat opens the original time selection dialog. Fixed clipping of the password submit button and reservation/cancel action row. Verified toggling to original layout and back.

Reservation, check-in, renewal, departure and password-change submissions were not executed against the live account. Their original controls, forms, hidden tokens and handlers remain present; transaction results require subsequent use on the campus network. No seat was reserved by these checks.
