# Verified update release — 2026-10-14

The user authorized a bootstrap release followed by a newer verified release. **v0.3.4-beta.1 / code 9** supplies the real public update fixture; **v0.3.5-beta.1 / code 10** increments the installed version. The updater implementation is identical between these two APKs; the final source change is the version code/name. Baseline, platform rejection, overwrite and first system-installer evidence is in [ADB_ACCEPTANCE_0.3.4.md](ADB_ACCEPTANCE_0.3.4.md).

## Public native update acceptance

The connected physical Android 16 / API 36 phone used the actual configured HTTPS origin and the existing encrypted account data. No production data was cleared. Original connectivity was Wi-Fi enabled and mobile data disabled; it was restored after every network test.

- The actual public manifest offered code 9 to the installed code 8 update-module baseline. Choosing “稍后” created **zero task files and zero managed DownloadManager rows**, and a process restart left the prompt quiet. Another manual check could still offer the release.
- Explicit confirmation downloaded the real public APK and reached native **READY** after the actual updater checked size, SHA-256, package/version/minimum SDK and signer. SHA-256 was `55d01ce683e4dbcc33b77e856691bf1a02ff8a2acce1bbd5510443b5f0c2cd98`. The checksum/signature agree with the published asset. Deleting the verified file through the scrollable native dialog left zero task files/download rows.
- With both networks disabled, a new native download had **exactly one system row**, ID 207, `STATUS_PAUSED = 4`, reason 2 (waiting for network), and zero downloaded bytes. The task was `DOWNLOADING`. Reopening its native controls and canceling removed the row and task record.
- A separate offline confirmation created task 208. After force-stopping the application and restoring Wi-Fi, the **same UUID, download ID and manifest snapshot** reached READY. Restoration did not open the installer. The application used the already authorized download; it did not enqueue another version.
- The actual OEM system installer installed the publicly downloaded code 9 APK. SDK-only readback returned **PASS / code -1**: actual installed code 9/version 0.3.4, non-debuggable, exact published APK bytes, unchanged encrypted accounts/selected account/preferences/bookmarks/theme, and automatic removal of the tracked record/backup/partial/internal APK/external download.
- During Wi-Fi reconnection, manual checks briefly failed. Fresh device HTTPS diagnosis and the actual production OkHttp/parser test both subsequently passed, returning the correct public manifest; the production-client test passed in **2.028 seconds**. Those failed attempts are retained privately and are not counted as passes.

Evidence is ignored under `.private/qa-0.3.4/`: `public-available-ui.log`, `public-deferred-restart-ui.log`, `public-confirmed-task.json`, `public-offline-cancel.log`, `public-offline-download-state.log`, `public-process-recovery.log`, `public-recovery-before.json`, `public-recovery-after.json`, `public-recovered-ready-ui.log`, `phase3-framework-record-task.log`, `phase3-framework-final.log`, and `public-production-client-diagnostic-fixed.log`.

## Public mirror acceptance

The deployed Raspberry Pi is reachable with `ssh raspi`; its project is `/home/fanyu/Projects/bucea-library-seat`. The active user systemd services run the matching deployed scripts under `~/.local/share/bucea-library/`. Android-only version changes do not require replacing the HTTP server.

- Bootstrap release 412320050 has exactly APK, `SHA256SUMS` and `update.json`; the mirror selected APK asset **639131095**, **102,488,215 bytes**, with the checksum above and the original certificate SHA-256 `211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`.
- Public full download/hash/signature, HEAD 200, manifest/APK ETag 304, exact 1024-byte Range 206 and another range after HTTP service restart passed. The mirror/timer services remained healthy.
- Origin unsatisfiable Range returns 416 with `no-store`. Cloudflare can generate a cached 416 while retaining the immutable asset's cache header. This proxy deviation is recorded; the valid range path and APK integrity passed. User-managed Cloudflare rules were not changed.

Evidence: `bootstrap-public-audit-final.log` and `public-audit-v0.3.4-beta.1/` in the private directory.

## Final release checkpoint

Version **0.3.5 / code 10** builds and both Lint variants pass. Its **23 JVM tests** pass. The unchanged updater/business implementation has the earlier **106 independent device tests**, **32 real Android updater-service tests**, **38 Python tests**, complete minified release UI/four-floor acceptance and the public update sequence above. These earlier counts are not new executions against code 10.

The final code 10 public update/install and retained code 9 asset checks are recorded after publication below. No incomplete public transaction is inferred from a local READY fixture.

## Remaining conditions

Android 8 / API 26 hardware/emulator is unavailable. A physical phone reboot, actual disk exhaustion and mobile-data transfer were not exercised; recovery/space policy tests do not replace those physical conditions. The user confirmed campus Wi-Fi only and explicitly deferred library sign-in and the dependent seat-use lifecycle. No new reservation/cancellation was submitted. Password modification remains explicitly unimplemented, and no second authorized real identity is available. The HTTP and mirror services share the existing OS user.
