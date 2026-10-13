# Validation — 2026-10-13

## Version 0.2.1 device acceptance

- Debug builds and Lint passed (0 errors). Installed using adb on the same physical device.
- **25 independent instrumentation tests passed** in 0.694 seconds. Four new checks cover rolling booking-date/filter reconciliation and the distinction between retryable reads and nonretryable mutation requests.
- The final 0.2.1 APK also passed the explicit live read-only business check in 2.636 seconds, including real number lookup, available times, account-bound booking preparation and record reads.
- After the user unlocked the phone, `tools/adb_library_smoke.py` passed all five native UI phases: future-date query, selecting real start/end times, checking the booking preview without submitting, booking records, favorites and violations.
- Additional manual checks passed for building/room selection, numbered query, bookmark add → open available times → remove, real page 2 loading, and landscape time-dialog controls. The test bookmark was removed and original rotation settings restored.
- Live device inspection found a grid rounding bug that left the right half empty on a high-density display. Equal-weight rows now show two real seat cards side by side. Paging scrolls to the result heading rather than staying at the previous page's bottom.
- Fixed stale booking dates, missing unrestricted-room choice after selecting a building, misleading empty-time loading copy and confirmations that outlived their reservation context.
- Read-only requests can recover stale connections; every redirect target is checked against an exact read allowlist. Booking POSTs, seat mutation GETs, logout and unknown routes retain no automatic retry. Uncertain seat actions require a fresh status check before action buttons are reenabled.
- Screenshots and temporary UI hierarchies are in ignored `.private/` paths and are not committed. No real booking, check-in, renewal or finish-usage transaction was submitted.

## Version 0.2.0 native library functions

- Debug application and test APK builds passed; installed on the existing device through adb.
- Independent instrumentation returned **OK (21 tests)** in 4.102 seconds. Coverage now includes search/page parsing, disabled time slots, official verification, stable record IDs, unknown/negative booking results, fresh account-bound form tokens, duplicate booking prevention, operation restrictions, browser cookie deletion, persistent logout and per-account bookmarks.
- Explicitly enabled `LiveLibraryReadOnlyTest` returned **OK (1 test)** in 3.805 seconds against the campus service. It verified identity, school-opened future dates, rooms, free-seat search, native number lookup, start/end times, fresh booking-token preparation, booking history, violations and current reservation state. It stopped before any reservation or seat action submission.
- Real testing exposed a school endpoint issue: the original numeric name search did not find lettered labels such as `001D`. Native number lookup now uses the documented room map API, matches labels locally and excludes unavailable or local-only seats. Ordinary filter search continues using the paginated search API.
- The mobile adapter behavior test passed inherited/delegated handlers, AJAX-added seats, paging alignment after resize, token/form preservation, hidden dialogs, repeat injection, observer settling and original layout restoration.
- The first 0.2.0 run could not complete manual UI acceptance while the device was locked; this was completed for 0.2.1 above.

Booking/action writes are covered with synthetic responses and account-bound tokens. Actual reservation, check-in, departure, return, renewal and finish-usage submissions were not executed against the live account.

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
