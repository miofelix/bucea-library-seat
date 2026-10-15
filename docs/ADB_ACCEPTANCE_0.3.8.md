# Release acceptance — 0.3.8, 2026-10-15

This release is **0.3.8 / versionCode 13**, tagged `v0.3.8-beta.1`. It includes the compact layouts, shared section tabs, seat-filter ordering and controls, separate notices, official-library link, navigation state retention, and targeted search/map retries developed since the published 0.3.7 beta.

## Fresh version 13 verification

- **76 JVM tests** passed without failures, errors or skips, including compatibility parsing for the new Chinese display name and legacy ASCII public filename.
- **135 independent ADB device tests** passed in **53.542 seconds**, with all opt-in live classes excluded and no skips. The physical PKX110 runs Android 16 / API 36.
- **Two actual campus read-only tests** passed in **5.138 seconds**, covering business reads and current-day regional inventory. The school returned **8 regions / 1708 seats / 0 unknown states**. No reservation, cancellation or seat-use mutation was submitted.
- **48 Python release-tool tests** passed, including canonical Chinese naming, draft asset readback, source checksums and mirror compatibility for unchanged Android clients.
- Debug, AndroidTest, R8-minified Release, both Lint variants and beta staging passed. Lint reports **0 errors / 50 debug and 51 release advisory warnings**.
- Staging verifies the original certificate SHA-256 `211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`, package identity, version metadata and disabled debugging.
- An SDK-only salted comparison confirms that encrypted accounts, selected account, preferences, manual favorites and appearance survived the original code 12 installation through code 13 testing. Ordinary session-cookie refreshes are outside that comparison.

Earlier code 12 UI-stage results remain in [ADB_ACCEPTANCE_UI_2026-10-15.md](ADB_ACCEPTANCE_UI_2026-10-15.md); they are not counted as fresh code 13 runs. Android 8 / API 26 and actual library-internal Wi-Fi check-in were not exercised by this release run.

## Prepared artifact and publication

The final committed candidate is staged after release preparation so its Git metadata identifies the reviewed source commit. Final installed-byte checks, publication assets and public update-source results are recorded here after preparation and publication. Released assets are never replaced.

All future APK attachments use `北建大图书馆选座-vx.x.x.apk`, enforced by staging and publication validation; the beta suffix stays in the release tag. The mirror compatibility change is implemented and tested locally, but its deployment is explicitly deferred by the user. The existing Raspberry Pi Tailscale node key expired, and both known SSH routes were unreachable. Public mirror availability for code 13 is not claimed until that deployment occurs.

Private evidence is ignored under `.private/qa-0.3.8/`, including `build.log`, `device-tests.log`, `live-readonly.log`, `python-tests.log`, `original-state-snapshot.log` and `after-tests-state.log`.
