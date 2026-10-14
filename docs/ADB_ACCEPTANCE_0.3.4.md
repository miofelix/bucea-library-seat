# ADB acceptance — 2026-10-14

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

## Coverage limits

An Android 8 / API 26 device or emulator is not available on this machine. API 26 installation and platform behavior cannot be inferred from API 36 results. Successful password modification remains outside the current product: its entry explicitly reports that it is unimplemented. Library-internal Wi-Fi is required for actual check-in and the dependent seat-use lifecycle, and a second authorized account is required for multiple live identities.

Subsequent phases will record real APK rejection, DownloadManager recovery, overwrite data preservation, system installer interaction and final release verification separately. A synthetic APK or seeded installation task does not establish a successful public-network update.
