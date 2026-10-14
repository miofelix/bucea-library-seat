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

## Coverage limits

An Android 8 / API 26 device or emulator is not available on this machine. API 26 installation and platform behavior cannot be inferred from API 36 results. Successful password modification remains outside the current product: its entry explicitly reports that it is unimplemented. Library-internal Wi-Fi is required for actual check-in and the dependent seat-use lifecycle, and a second authorized account is required for multiple live identities.

Subsequent phases will record real APK rejection, DownloadManager recovery, overwrite data preservation, system installer interaction and final release verification separately. A synthetic APK or seeded installation task does not establish a successful public-network update.
