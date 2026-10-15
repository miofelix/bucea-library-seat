# Validation — 2026-10-15

The official release **1.0.0 / versionCode 14** is [published](https://github.com/miofelix/bucea-library-seat/releases/tag/v1.0.0), covering denser layouts, four-column seat cards, all-results search, filterable layout cards, inline favorite booking, scrolling secondary tabs, navigation state, separate notice rows and the official-library link. Its fresh build, device and seven-asset publication results are recorded in [ADB_ACCEPTANCE_1.0.0.md](ADB_ACCEPTANCE_1.0.0.md). The preceding 0.3.8 / code 13 implementation checkpoint remains in [ADB_ACCEPTANCE_0.3.8.md](ADB_ACCEPTANCE_0.3.8.md), and the earlier code 12 UI stages remain in [ADB_ACCEPTANCE_UI_2026-10-15.md](ADB_ACCEPTANCE_UI_2026-10-15.md); those results are not reassigned to code 14. The published 0.3.7 checkpoint is in [ADB_ACCEPTANCE_0.3.7.md](ADB_ACCEPTANCE_0.3.7.md), and earlier public-update evidence remains in the versioned records below. Mirror deployment and synchronization are deferred as requested; GitHub publication and ADB installation do not establish a public updater transaction.

## 0.3.6 release checkpoint

- The published code 10 → code 11 native update completed through the real public origin and OEM installer. SDK-only readback verified the exact release APK bytes, preserved original account/preferences/favorites/theme data and automatic cleanup; manual checking reports the installed latest version. The final phone is on non-debuggable 0.3.6/code 11.
- Before the version increment, the campus-access fix passed **107 independent device tests in 19.541 seconds**, without skips, **23 JVM tests**, debug/test builds and both Lint variants with zero errors. Actual native Wi-Fi-settings return restored access and the saved account session automatically after reconnection. These counts describe the source-fix run and are not reused as a fresh code 11 execution.
- The deployed library endpoint separately timed out from the app UID and later returned HTTP 200. The restored published code 10 application then passed saved-session restoration and a real future-date query. This is evidence of an intermittent campus/service availability problem as well as the client recheck correction.
- Fresh code 11 execution passed **107 independent device tests in 18.366 seconds**, without skips, and one live campus read-only test in **3.310 seconds**. **23 JVM tests**, debug/release/AndroidTest builds and both Lint variants passed with zero errors (47 debug / 48 release advisory warnings). This is a new run, distinct from the preceding source-fix checkpoint.
- The non-debuggable code 11 release APK was installed on the physical phone with the original signing certificate. Its installed bytes match SHA-256 `c4edbf17818ce555ff0c57e3c8f822e157a64980e521ba889b9d52ff6cee8579`, size **102488215 bytes**; SDK-only private-state comparisons confirmed preservation of account, preference, bookmark and theme data. Final native recovery and public mirror results are recorded in the linked version-specific acceptance document.
- The current release-tooling run passed **38 Python tests in 0.701 seconds**; evidence is under `.private/qa-0.3.6/python-tests.log`.
- **v0.3.6-beta.1** was published with all three assets and synchronized to the Raspberry Pi. Public manifest code 11 matches GitHub release ID **412403499** and APK asset ID **639478202**. The complete origin HTTP stream matches the staged APK's **102488215 bytes** and SHA-256 above. Public manifest/asset conditional requests returned 304, HEAD returned 200 with the expected size, and first/continuation/final 1024-byte ranges returned 206 with exact candidate bytes. The previous code 10 asset remains available. These bounded public ranges are not counted as a second full public transfer; the phone's native download/install is recorded separately in the final-version acceptance document.
- Public invalid Range remains **416 / immutable** from Cloudflare's cached APK; origin is **416 / no-store**. This matches the already-recorded forwarding-layer difference. Evidence is under `.private/qa-0.3.6/public-audit.log` and `public-audit-v0.3.6-beta.1/`; deployed mirror/server script hashes match the unchanged local tools.
- The user continues to defer library-internal Wi-Fi check-in and dependent use operations. Android 8 / API 26, a second real identity, mobile-data switching, a whole-device reboot and actual storage exhaustion remain outside the completed acceptance.

## 0.3.5 final-release checkpoint

- The published bootstrap **v0.3.4-beta.1 / code 9** has three matching assets and is available through the Raspberry Pi mirror. Its public manifest matches GitHub release ID 412320050 and APK asset ID 639131095. The complete public APK is **102488215 bytes**, SHA-256 `55d01ce683e4dbcc33b77e856691bf1a02ff8a2acce1bbd5510443b5f0c2cd98`, with the historical signing certificate.
- Public manifest/APK conditional requests returned 304, HEAD returned 200, valid Range returned 206 with exact bytes, and continuation worked after restarting the deployed HTTP service. Origin unsatisfiable Range returned 416 / no-store; Cloudflare generated its cached 416 with the APK's immutable header. The forwarding-layer difference is recorded without modifying user-managed Cloudflare configuration.
- The actual native public transaction **debug code 8 → published release code 9** passed discovery, the visible update prompt, deferral with quiet process restart and zero managed downloads, download confirmation, cancellation and an actual network-waiting task. A second offline task retained the same token/Download ID/manifest across force-stop and Wi-Fi restoration, then downloaded and verified the public APK to READY without opening an installer automatically.
- An explicit install click completed the public code 9 update through the OEM installer. SDK-only final readback verified non-debuggable installed code 9, exact public APK digest, preserved account/preferences/bookmarks/theme fingerprints and automatic cleanup. Wi-Fi and mobile-data settings returned to their original values. The native manifest-client diagnostic passed one test in 2.028 seconds after transient Wi-Fi reconnection failures; failed connection attempts are not counted as successful checks.
- Code 10 retains the same production implementation and only increments version metadata. Its build, **23 JVM tests** and both Lint variants passed. The actual phone completed the public native code 9 → code 10 download and OEM installation. SDK-only readback verified non-debuggable version 0.3.5, exact published APK bytes, preserved original account/preferences/bookmarks/theme state and automatic cleanup. Manual checking reports the installed latest version. Final public full-byte/signature/ETag/Range/restart checks and retained code 9 downloads passed.

The completed 0.3.4 baseline and earlier versioned runs below remain historical snapshots; their counts are not reassigned to an unexecuted final-version run.

## 0.3.4 bootstrap staged ADB acceptance

- Android 16 / API 36, arm64: **106 independent device tests passed in 21.277 seconds**, with no skips. This includes **32 updater service tests in 0.827 seconds** using actual PackageManager, DownloadProvider, FileProvider and persistent Android storage. **23 JVM tests** and **38 Python tests** passed.
- Real campus read-only business and inventory checks each passed once. Native query, time selection and reservation review were exercised without submission; Canvas/list/pan/fit passed on all four returned floors. The closed password entry's transient Toast was independently observed by its passing device test.
- Actual old release **0.3.3 / code 7** and debug **0.3.4 / code 8** overwrite-install checks preserved salted fingerprints of decrypted accounts, preferences, bookmarks and theme, plus exact stored cookies. Tests used the existing signing certificate and did not clear production data.
- Raspberry Pi user HTTP service restart and listener recovery passed; the mirror timer stayed active and a manual synchronization completed successfully. Before the first complete release, the public HTTPS manifest correctly returned **503 / no-store** because older releases have no update manifest. Private state and configuration files use 0600 permissions. Both services currently run under the same existing OS account.
- The final non-debuggable code 9 release was installed through the actual OEM system installer. SDK-only readback verified its exact APK bytes, preserved account/preferences/bookmarks/theme data and automatic cleanup. Permission denial/grant/return, cancellation and a subsequent successful attempt were exercised; full native UI/four-floor regression passed on the minified release. Local installation fixtures do not establish a completed public-network update.
- This run submitted no real reservation, cancellation or seat-use mutation. Android 8 / API 26 remains untested; the user confirmed campus Wi-Fi only and deferred library check-in and a second authorized real identity is unavailable. Password modification remains unimplemented in the product.

Private evidence is under `.private/qa-0.3.4/`; credentials, session captures and fixture APKs are excluded from Git.

## Historical 2026-10-14 native 留白 interface

- The native application now uses the **留白** interface across dashboard, seat search, map, reservation sheets, records/favorites/frequent seats/violations, accounts, settings, verification and update dialogs. The native design specification is maintained in [UI_DESIGN.md](UI_DESIGN.md); temporary HTML comparison/selection prototypes have been removed.
- Debug and instrumentation APK builds passed. **21 JVM unit tests passed** with no failures, errors or skips. The tested debug APK is version **0.3.4 / code 8** and was installed over the existing application without clearing its data.
- **6 UI regression tests passed in 17.246 seconds** on the connected Android device: cancellation confirmation/keep/Back/inactive records; library Wi-Fi guidance; manual favorites versus system frequent seats; immediate current-page seat filtering; busy account controls; and note editing with the save action above the visible keyboard. Note edits use synthetic accounts and callback recorders, never the account vault or campus mutation APIs.
- The existing **9 seat-presentation/map-contract device checks** also passed in the targeted run, covering status versus server availability, loaded-page filtering, real grid geometry and read/verification contracts.
- Actual campus read-only UI acceptance passed future-date seat search, compact start/end time selection, the complete reservation review **without submission**, all four library sections, and all seven advanced filter selectors.
- Actual maps for **all four returned floors** passed Canvas display, pan, fit, list mode and switching back to the map. The smoke helper now reads the two persistent mode buttons' selected/checked state and restores the visible page header before reopening floor selection.
- Native settings acceptance passed opening manual update controls and the theme picker. A temporary alternate color survived an application restart, then the original color was restored; account/login preferences were not modified. The unavailable password entry opened no form and submitted nothing; its transient Toast was not counted as independently observed in the smoke run.
- Android 33+ UI fixtures clear the UiAutomation node cache before reading Compose text/enabled states. Failure artifacts are limited to synthetic fixtures; authenticated screenshots and logs remain ignored under `.private/whitespace-ui/`.
- This UI acceptance did not create/cancel a real reservation, change a library password, or invoke live check-in/leave/renew/finish operations. Those transaction results are not inferred from presentation tests.

## Historical actual campus-service run

The current application uses native Kotlin/Compose screens and interactions. Website HTML is parsed only for business data; it is never rendered. Installed-APK architecture tests inspect native activity declarations and verify that the assets contain no HTML, JavaScript or CSS.

Earlier live outcomes are recorded in [ACTUAL_TEST_RESULTS.md](ACTUAL_TEST_RESULTS.md). Two future reservations and one same-day “start now” reservation were submitted, read back and canceled; no active test reservation remains. That run corrected receipt parsing, the school's fresh-token POST cancellation form, and canceled-record recognition. The three canceled audit records are retained by the school.

Two real password-change attempts encountered the school's error page and did not establish success. The original credential was subsequently authenticated, the saved value restored, and encrypted recovery removed. The application entry is now temporarily closed and displays “修改密码当前未实现”. Successful password mutation is **not** a passed live acceptance result.

At the user's direction, that earlier run skipped check-in on library-internal Wi-Fi and the leave, resume, renewal and finish-use operations that depend on check-in. These are not counted as passed live transactions. Only one authorized real identity was supplied; multiple-account isolation is exercised with separate synthetic device accounts.

The versioned results below are snapshots of earlier phases, including the former browser-based prototype. They do not describe the current APK or override the newer live outcome record. A later build/test total must be recorded from its own run output rather than inferred from source test counts.

## Version 0.3.3 reservation and navigation improvements

- **72 independent device tests passed in 18.665 seconds**, excluding all nine opt-in live classes, with no skipped tests. ADB ran the final APK on the physical Android 16 / SDK 36 device.
- New native UI fixtures verify visible cancellation, keeping the reservation, Back dismissal and exactly one confirmed cancellation callback; inactive records offer no cancel action. Data regressions cover fresh tokens, same-origin form normalization, form-only record identity, conflicting/inactive records and paginated cancellation readback.
- Library Wi-Fi guidance distinguishes location restrictions from no current reservation; an enabled system Wi-Fi settings entry and reconnect refresh are present. Fresh history restores cancellation even when the separate seat-action state read fails.
- Manual “收藏座位” and school-generated “常用座位” have separate sections. The same seat remains in the system list after removing its local bookmark; system entries offer no manual deletion.
- The real campus read-only business test passed in **4.072 seconds** with no skip. Native search, number lookup, times, booking preparation, history, violations and room maps were read without submitting a reservation or seat action.
- Full ADB native UI smoke passed query, time selection, reservation review without submission, all four record/seat sections, advanced filter choices, and Canvas/list/pan/fit for all four returned floors. The closed password entry shows its notice and opens no form.
- Latest ADB UI acceptance also passed the original dashboard without a map entry, the persistent full-width map entry at the top of “选座”, native map routing, the device-specific Wi-Fi settings activity, About author/QQ/copy controls, and outgoing GitHub repository/issue URLs. The map entry remains visible while scrolling query results.
- Debug/test builds and Lint passed: **0 errors, 36 advisory warnings**. Version **0.3.3 / code 7** retains the prior APK signing certificate and supports ADB overwrite installation.
- Release asset: `BUCEA-Library-Seat-v0.3.3-beta.1.apk`; SHA-256 `67c90dc11effc60329cbbdf3e4941e21b3b1608c1961505bb90ddbd02c733aab`. `SHA256SUMS` agrees and the staged asset is byte-for-byte identical to the tested APK.
- This run did not submit a new real reservation or cancellation. Cancellation protocol and UI changes passed synthetic regression tests; earlier real cancellation evidence remains recorded for 0.3.1. Check-in, leave, resume, renewal and finish remain unverified live because the device is not connected to library-internal Wi-Fi.
- Private logs and screenshots are under `.private/qa-0.3.3/`. Temporary ADB keep-awake settings are restored after acceptance.

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

Android 16 / SDK 36, arm64 physical device; app minimum SDK 26, target SDK 35. Debug version is 0.3.3, versionCode 7. Dependencies and SDK values are defined in `app/build.gradle.kts`.

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

The UI script checks native search, time selection and reservation review without submission, then reservations/manual bookmarks/system frequent seats/violations. `--full` additionally opens server filter choices, checks native map Canvas/list presentation for returned floors, and verifies that the temporarily closed password entry opens no form; an explicit device event test verifies its Toast. `--map-only` runs only the map portion. It does not test successful password changes, reservation submissions or seat-use mutations; those need separate actual-service results. A floor without school-provided rooms produces an explicit `SKIP`.

Run one UI test at a time on an unlocked device. UI screenshots may show names and account labels; keep them under ignored `.private/` paths. The script does not print or persist UI XML. Synthetic account tests use separate preference namespaces and do not clear the production vault.

## OCR reference parity

Compared Kotlin image preprocessing with local ddddocr / Pillow on seven cases: real captcha, upstream sample, RGB upsampling/downsampling, grayscale, RGBA resampling, and unchanged-size RGBA. All output pixels matched. The Kotlin CTC check covers blanks and consecutive duplicate characters.

The earlier mobile website adapter and layout-restoration checks are historical prototype evidence. The adapter, browser activity and embedded webpage assets have been removed; current acceptance checks the native screens and installed APK.
