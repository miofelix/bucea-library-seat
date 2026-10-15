# ADB acceptance — 2026-10-14

> Historical deployment record: Raspberry Pi, systemd, Tunnel, mirror status and server-test results below describe the service at the date of this record. The server implementation is retired; current Cloudflare Pages/R2 setup and activation checks are in [UPDATES.md](UPDATES.md). These results do not establish acceptance of the R2 deployment.

This staged acceptance follows the deferred checks in [UPDATE_VALIDATION.md](UPDATE_VALIDATION.md). The connected physical device is Android 16 / API 36, arm64. Authenticated captures, logs and APK fixtures are kept in ignored `.private/qa-0.3.4/` paths. No account credentials or sessions are committed.

## Phase 1 — baseline and service prerequisites

- Installed 0.3.4 / versionCode 8 debug and test APKs over the existing application without clearing data.
- All **74 independent instrumentation tests passed in 21.574 seconds**, excluding the nine opt-in live classes; no skips or failures.
- The actual campus read-only flow passed in **3.607 seconds**, including real availability, reservation preparation without submission, history, violations and native map geometry.
- The authenticated before-test inventory passed in **1.295 seconds** and was saved privately. It provides the reservation/account state against which cleanup can be checked.
- Python **38/38 tests passed**, including 13 HTTP integration tests. The public HTTPS update origin is reachable; its manifest returns **503 / no-store** because all three existing GitHub releases lack `update.json`.
- Read-only SSH confirmed deployed Raspberry Pi **user** systemd HTTP and mirror-timer units, listening on port 9080. The deployed scripts match the repository. The empty mirror result is expected before the first complete release.

Evidence: `phase1-build.log`, `phase1-instrumentation.log`, `phase1-live-read.log`, `phase1-inventory.log`, `python-tests.log`, and `raspi-user-systemd-readonly.txt` under the private run directory.

## Phase 2 — actual Android update services

- **32/32 updater instrumentation tests passed in 0.827 seconds**. Real PackageManager checks accepted a higher-version, non-debuggable, same-certificate universal fixture APK, and rejected truncation, a changed digest, non-APK bytes, wrong package/version/minimum SDK/signature, debuggable builds, unsupported SDK and already-installed versions.
- Real DownloadProvider rows exercised descriptor reads, verified internal copying, atomic rename, interrupted `.part` recovery, orphan isolation, restored enqueue/download/verification phases, duplicate confirmation, cancellation and original-version retry. Registered completed rows do not establish network progress; Android 16 reports zero downloaded bytes for those synthetic rows.
- Real FileProvider checks exercised the configured internal root, readable URI bytes, sibling-directory exclusion and path traversal rejection. Update persistence checks used Android SharedPreferences and AtomicFile, including corrupt records and backup recovery.
- Testing identified a production ownership defect: recovery/cleanup previously matched only a download basename. The updater now requires a canonical file path inside its own external update directory. Unrelated same-basename downloads survived the device regression. Internal cleanup also works without external storage.
- The deprecated completed-download fixture API requires visible completion notification on this device; the fixture was adjusted without adding production permissions. Test notifications and rows are removed during cleanup. The production download request retains `VISIBILITY_VISIBLE`.
- **23/23 JVM tests passed**. Debug/release builds and both Lint variants passed with zero errors (47 debug / 48 release advisory warnings).
- Full native UI acceptance passed real query/time selection/review without submission, all four library sections, seven advanced filter selectors and Canvas/list/pan/fit on all four returned floors. The separate native password-notice instrumentation passed in 2.350 seconds; its transient Toast was not inferred from the smoke helper.
- Raspberry Pi user HTTP service restart, listener recovery, successful mirror oneshot sync, active timer, 0600 private-state/config permissions and private-path HTTP exclusion passed. Both services use the existing `fanyu` account; a separate HTTP OS account is not configured.

Reproduce the archive/device fixtures with `python3 tools/create_update_test_fixtures.py --sdk <Android SDK>`, then build/install the debug and AndroidTest APKs and run the `cn.edu.bucea.library.update` instrumentation package. Generated APKs and the alternate fixture key are excluded from Git.

Evidence: `phase2-android-updates-final.log`, `phase2-final-build.log`, `phase3-release-build.log`, `native-ui.log`, `phase2-account-notice.log`, and `raspi-service-restart-validation-final.jsonl` under the private run directory. Earlier failed fixture runs remain there for traceability; they are superseded by the complete passing run.

## Phase 3 — overwrite and actual system installation

- The complete independent suite passed **106/106 tests in 21.277 seconds**, excluding all opt-in live classes, with no skipped tests.
- Took salted semantic and encrypted-preference fingerprints, installed the actual old **0.3.3 / code 7** release over the debug baseline, then upgraded to **0.3.4 / code 8**. Account credentials, selected account, preferences, sign-out choices, manual bookmarks, theme and exact stored cookies survived both overwrites. No application data was cleared.
- A manual check against the actual public HTTPS origin displayed the expected explicit failure for the empty mirror's 503 response. No installer was launched.
- Verified the final, full **0.3.4 / code 9** release APK through actual PackageManager/signature/hash checks and a real DownloadProvider descriptor copy. The prepared local READY task survived process restart quietly. This local fixture bypasses public-manifest discovery and download consent; it is installation evidence, not a public update transaction.
- Unknown-source permission was initially off. Returning without granting it kept the update available. Granting it and returning did not open the installer automatically; another explicit installation click was required.
- The OEM installer read the granted FileProvider URI and displayed the correct application, version and size. Cancellation displayed **“已取消安装”**; the application retained its verified task and offered installation again. The next explicit attempt completed with **“安装完成”**.
- SDK-only release readback returned **PASS / instrumentation code -1**. Actual PackageManager reports code 9, version 0.3.4 and no debuggable flag; the installed APK SHA-256 equals the verified candidate. Encrypted account data, choices, preferences, bookmarks and theme match the pre-overwrite snapshot. Actual release startup cleared the tracked task/backup/partial/internal APK/external download files.
- Full native acceptance also passed on the **actual R8-minified release**: real query, time selection, booking review without submission, four library sections, advanced filters and all four returned map floors in Canvas/list/pan/fit modes.
- Debug AndroidJUnitRunner cannot start against this minified release because it expects unminified Kotlin/AndroidX classes. A separate SDK-only Java instrumentation APK performs the release readback without changing the release APK. Reproduce it with `python3 tools/create_release_acceptance_apk.py --sdk <Android SDK>`; runner steps are explicitly opted in and kept separate from ordinary tests.

Evidence: `phase3-*snapshot.log`, `phase3-old-data-preserved.log`, `phase3-upgrade-*-preserved.log`, `manual-update-ui.log`, `installer-ready-ui.log`, `permission-*-return.log`, `installer-completed.xml`, `phase3-framework-final.log` and `final-release-ui.log`. The failed Debug-runner attempts remain private for traceability and are superseded by SDK-only release acceptance.

## Coverage limits

An Android 8 / API 26 device or emulator is not available on this machine. API 26 installation and platform behavior cannot be inferred from API 36 results. Successful password modification remains outside the current product: its entry explicitly reports that it is unimplemented. The user confirmed campus Wi-Fi only and explicitly deferred library check-in and its dependent seat-use lifecycle. A second authorized account is not available for multiple live identities.

Mobile data was originally disabled and is preserved. A physical phone reboot and actual storage exhaustion were not exercised; JVM recovery/space-failure checks are not physical proofs of those conditions. A synthetic APK or seeded installation task does not establish a successful public-network update.
