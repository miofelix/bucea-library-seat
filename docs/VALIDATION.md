# Validation — 2026-10-13

## Current actual campus-service run

The current application uses native Kotlin/Compose screens and interactions. Website HTML is parsed only for business data; it is never rendered. Installed-APK architecture tests inspect native activity declarations and verify that the assets contain no HTML, JavaScript or CSS.

The current live outcomes are recorded in [ACTUAL_TEST_RESULTS.md](ACTUAL_TEST_RESULTS.md). Two future reservations and one same-day “start now” reservation were submitted, read back and canceled; no active test reservation remains. That run corrected receipt parsing, the school's fresh-token POST cancellation form, and canceled-record recognition. The three canceled audit records are retained by the school.

Two real password-change attempts encountered the school's error page and did not establish success. The original credential was subsequently authenticated, the saved value restored, and encrypted recovery removed. The application entry is now temporarily closed and displays “修改密码当前未实现”. Successful password mutation is **not** a passed live acceptance result.

At the user's direction, this run skips check-in on library-internal Wi-Fi and the leave, resume, renewal and finish-use operations that depend on check-in. These are not counted as passed live transactions. Only one authorized real identity was supplied; multiple-account isolation is exercised with separate synthetic device accounts.

The versioned results below are snapshots of earlier phases, including the former browser-based prototype. They do not describe the current APK or override the newer live outcome record. A later build/test total must be recorded from its own run output rather than inferred from source test counts.

## Version 0.3.2 selection and account improvements

- **64 independent device tests passed in 6.711 seconds**, excluding all nine opt-in live classes. The new UI test verifies immediate filtering, application to incoming pages, empty filtered pages with working pagination, and zero extra search callbacks.
- Real current-day query: “仅看可用” immediately changed **32 / 32 → 13 / 32**. The switch remained enabled after loading the next server page. No reservation was submitted.
- Both actual account notice/label tests passed in 11.202 seconds: password entry emits the requested Toast and opens no form; empty-password remark editing preserves credentials, cookies, sign-out, account choice and other accounts. The original remark was restored.
- Offline requirements appeared on dashboard, account and feature login screens; login was blocked and the Wi-Fi settings entry worked. Reconnection/retry restored authenticated search. Server HTTP/authentication errors are distinguished from transport failure.
- Application/home title is 北建大图书馆选座; repository and Gradle project use bucea-library-seat. Version 0.3.2 / code 6 builds and Lint pass with zero errors. Native query/time/review, history, favorites and violations smoke phases pass. Private evidence is under `.private/qa-0.3.2/`.

## Version 0.3.1 actual service acceptance

- **55 independent device tests passed in 2.176 seconds**, with all seven live classes excluded. This includes the real `now` start contract and cancellation readback regressions.
- Actual native booking: two future reservations plus one current-day “现在” reservation. School receipts were matched and all three were canceled. Final server inventory has no active usage or violations.
- Actual four-floor Canvas/list/pan checks passed. A real two-pointer map gesture test passed in 6.055 seconds and restored the original fit.
- Saved login, persistent logout, automatic OCR re-login, bookmark persistence/removal, settings persistence/restoration, Wi-Fi loss/reconnect, landscape/narrow/large-font dialogs, keyboard and Back were exercised through adb. Cached statistics now identify “上次数据” after a failed refresh.
- Debug/test APK builds and Lint passed with zero errors. The APK uses version 0.3.1 / code 5. Detailed pass/failure/deferral boundaries are in `ACTUAL_TEST_RESULTS.md`.

## Version 0.3.0 fully native migration

- All application UI and interactions now use Kotlin/Compose. Removed SeatWebActivity, its manifest entry, mobile JavaScript/CSS adapter and adapter test. The installed APK declares only MainActivity and packages no HTML/JS/CSS assets.
- Debug and instrumentation APK builds passed; Lint passed with 0 errors (32 advisory warnings). Installed version 0.3.0, versionCode 4, using adb.
- **46 independent device tests passed** in 4.802 seconds. Coverage includes native map geometry/status/verification, account password form/CSRF/result handling, logout, cancellation freshness, architecture constraints and automatic OCR verification. The obsolete browser-cookie snapshot case was removed.
- **Both explicit live tests passed** in 5.503 seconds with JUnit completion code 0. The read-only test covers native seat search/number lookup/times/booking preparation/history and real map dates/floors/rooms/layout. The automatic verification test runs the bundled detector and recognizer on Android and obtains a successful proof from the school's server without human input.
- Login CAPTCHA recognition is fully automatic: fresh challenges, bounded OCR attempts, immediate exit for credential/network/protocol failures. Ordered-click verification uses ddddocr `common_det.onnx` for text boxes and `common_old.onnx` for target/crop recognition, then submits computed coordinates. Four real image fixtures pass offline Android inference.
- Native UI smoke checks passed search → start/end selection → booking review without submission → reservations/favorites/violations. Manual checks also passed map Canvas selection → native seat details → native time dialog, map/list toggle, native password-dialog field visibility, explicit logout and automatic re-login.
- At this initial migration checkpoint, password change and booking/cancellation submissions were exercised only against synthetic responses. The subsequent live reservation/cancellation and password-attempt outcomes appear above and in `ACTUAL_TEST_RESULTS.md`. Native cancellation appears only for a fresh action actually provided by the account's server history.
- Screenshots and authenticated development captures remain under ignored `.private/`. Model assets and pixel-only CAPTCHA fixtures contain no credentials, session cookies or proof tokens.

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

At the 0.2.0 checkpoint, booking/action writes were covered with synthetic responses and account-bound tokens. Actual transaction outcomes are now tracked separately in `ACTUAL_TEST_RESULTS.md`.

## Current verification commands and coverage

Android 16 / SDK 36, arm64 physical device; app minimum SDK 26, target SDK 35. Debug version is 0.3.2, versionCode 6. Dependencies and SDK values are defined in `app/build.gradle.kts`.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e notClass cn.edu.bucea.library.LiveLibraryReadOnlyTest,cn.edu.bucea.library.LiveNativeVerificationInstrumentedTest,cn.edu.bucea.library.LiveLibraryFiltersTest,cn.edu.bucea.library.LiveLibraryInventoryTest,cn.edu.bucea.library.LivePasswordRoundTripTest,cn.edu.bucea.library.LiveNativeMapGestureTest,cn.edu.bucea.library.LiveCurrentDayReadOnlyTest,cn.edu.bucea.library.LiveNativeAccountNoticeTest,cn.edu.bucea.library.LiveNativeAccountLabelTest cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

Device tests cover OCR inference and preprocessing, encrypted account/session isolation and expiry, fresh booking/cancellation tokens, state-restricted actions, uncertain mutation results, native map geometry, password parsing, automatic verification, and installed APK architecture. Test counts and elapsed times above belong to the specified historical runs.

The opt-in `LiveLibraryReadOnlyTest` restores the active identity and reads school-opened dates, filter/number search, times, booking preparation, history, violations and native map dates/floors/rooms/grid. It never invokes reservation, cancellation, password or seat-action writes. `LiveLibraryFiltersTest` checks actual filter choices, paging and missing-number results. The opt-in `LiveNativeVerificationInstrumentedTest` obtains a real automatic OCR proof without creating a reservation. A JUnit assumption caused by missing account, unavailable school data or verification restrictions is a skip, not proof of a completed check.

```sh
adb shell am instrument -w -r -e class cn.edu.bucea.library.LiveLibraryReadOnlyTest -e live_library true cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class cn.edu.bucea.library.LiveLibraryFiltersTest -e live_library true cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class cn.edu.bucea.library.LiveNativeVerificationInstrumentedTest -e live_library true cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
python3 tools/adb_library_smoke.py --serial YOUR_DEVICE_SERIAL --artifacts .private/native-qa
python3 tools/adb_library_smoke.py --full --serial YOUR_DEVICE_SERIAL --artifacts .private/full-native-qa
python3 tools/adb_library_smoke.py --map-only --serial YOUR_DEVICE_SERIAL --artifacts .private/map-native-qa
```

The UI script checks native search, time selection and reservation review without submission, then records/favorites/violations. `--full` additionally opens server filter choices, checks native map Canvas/list presentation for returned floors, and verifies that the temporarily closed password entry opens no form; an explicit device event test verifies its Toast. `--map-only` runs only the map portion. It does not test successful password changes, reservation submissions or seat-use mutations; those need separate actual-service results. A floor without school-provided rooms produces an explicit `SKIP`.

Run one UI test at a time on an unlocked device. UI screenshots may show names and account labels; keep them under ignored `.private/` paths. The script does not print or persist UI XML. Synthetic account tests use separate preference namespaces and do not clear the production vault.

## OCR reference parity

Compared Kotlin image preprocessing with local ddddocr / Pillow on seven cases: real captcha, upstream sample, RGB upsampling/downsampling, grayscale, RGBA resampling, and unchanged-size RGBA. All output pixels matched. The Kotlin CTC check covers blanks and consecutive duplicate characters.

The earlier mobile website adapter and layout-restoration checks are historical prototype evidence. The adapter, browser activity and embedded webpage assets have been removed; current acceptance checks the native screens and installed APK.
