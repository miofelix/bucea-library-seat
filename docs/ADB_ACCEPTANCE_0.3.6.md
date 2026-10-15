# Release acceptance — 0.3.6, 2026-10-14

> Historical deployment record: Raspberry Pi, systemd, Tunnel, mirror status and server-test results below describe the service at the date of this record. The server implementation is retired; current Cloudflare Pages/R2 setup and activation checks are in [UPDATES.md](UPDATES.md). These results do not establish acceptance of the R2 deployment.

Version **0.3.6 / versionCode 11** releases the foreground campus-access recheck and qualified failure guidance from [CAMPUS_ACCESS_DIAGNOSIS.md](CAMPUS_ACCESS_DIAGNOSIS.md). A failed, idle access check is retried when the activity resumes; startup/ongoing operations and healthy sessions are not interrupted. The correction preserves ordinary explicit logout and account restoration.

## Current-build validation

- Fresh code 11 debug, instrumentation and minified release builds passed. **23 JVM tests** passed without failures/errors/skips. Both Lint variants reported zero errors (47 debug / 48 release advisory warnings).
- **107 independent instrumentation tests passed in 18.366 seconds**, with no skips. Opt-in live classes were excluded. This includes actual Android updater services and the foreground retry policy for every access/busy state.
- The real campus read-only test passed in **3.310 seconds**: authenticated session, school dates/availability, times and booking preparation without submission, history and native map geometry. No reservation, cancellation or seat-use operation was submitted.
- The actual minified release was installed without clearing data. Salted preference readback passed before and after installation, preserving encrypted accounts, selection, preferences, favorites and theme. Ordinary session refresh may update cookies.
- Actual release UI acceptance exercised Wi-Fi disconnection, the revised failure guidance, opening Wi-Fi settings and returning after reconnection. The native app automatically restored campus access and the saved session. The first run exceeded the 40-second network-validation bound; independent application-UID HTTP subsequently returned 200 in 119 ms. A longer bounded retry completed successfully. The earlier failed wait is retained as network evidence.
- Python **38 tests** passed in **0.701 seconds**, including the existing publishing, staging, mirror and real HTTP cases.

The tested release APK is **102,488,215 bytes**, SHA-256 `c4edbf17818ce555ff0c57e3c8f822e157a64980e521ba889b9d52ff6cee8579`. PackageManager reports code 11/version 0.3.6 without the debuggable flag. Staging verifies the original signer SHA-256 `211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`; the distributed APK must match the tested bytes.

## Publication and public update

**v0.3.6-beta.1** was published from commit `457163d` with exactly APK, `SHA256SUMS` and `update.json`. GitHub release **412403499** provides APK asset **639478202**; metadata, size and SHA-256 agree with the tested release bytes above. Published assets were not replaced.

- Raspberry Pi mirror synchronization completed successfully. Its full origin HTTP stream returned all 102,488,215 bytes with the expected SHA-256. Public manifest 200/304, APK HEAD/ETag and first/continuation/last exact Range 206 checks passed. The previous code 10 asset remains readable. Bounded Mac ranges are not described as a complete Mac public download.
- The actual published **0.3.5 / code 10 → 0.3.6 / code 11** native update passed manual discovery, explicit download consent, background download and reopening the READY task. SDK-only inspection checked the completed public APK against the expected final version, size and SHA-256.
- The system required unknown-source permission, then another explicit installation click. The OEM installer completed installation. SDK-only final readback returned **PASS / code -1**: actual installed non-debuggable code 11/version 0.3.6, exact published APK bytes, preserved original account/selection/preferences/favorites/theme state and automatic deletion of the task/backup/partial/internal APK/external download.

- Final manual checking reported **“已是最新版本”**. System inspection found zero managed downloads and zero task-record files. Acceptance-only preferences and the helper APK were removed; unknown-source permission returned to its original default, Wi-Fi remained enabled and mobile data disabled.

Evidence: `public-audit.log`, `public-audit-v0.3.6-beta.1/results.json`, `public-available-ui.log`, `public-ready-ui.log`, `public-install-ui.log`, `phase3-framework-record-task.log` and `phase3-framework-final.log` under the private run directory. This is a completed public update transaction, not a seeded installation task.

## Evidence and remaining conditions

Private artifacts are ignored under `.private/qa-0.3.6/`: `build.log`, `independent.log`, `live-readonly.log`, `release-foreground-ui.log`, `release-foreground-ui-retry.log`, SDK-only state/endpoint results, screenshots and `python-tests.log`. Failed network waits are not counted as passed checks. Original Wi-Fi and mobile-data settings are restored after each exercise.

The campus route and library service must be reachable; this patch does not eliminate their intermittent failures. The user continues to defer library-internal Wi-Fi check-in and dependent operations. Android 8 / API 26, a second live identity, mobile-data switching, whole-phone reboot and actual storage exhaustion remain untested. The known Cloudflare cached-416 header difference is recorded in the earlier [public-update acceptance](ADB_ACCEPTANCE_0.3.5.md).
