# Release acceptance — 0.3.8, 2026-10-15

This release is **0.3.8 / versionCode 13**, tagged `v0.3.8-beta.1`. It includes the compact layouts, shared section tabs, seat-filter ordering and controls, separate notices, official-library link, navigation state retention, and targeted search/map retries developed since the published 0.3.7 beta.

## Fresh version 13 verification

- **75 JVM tests** passed without failures, errors or skips.
- **135 independent ADB device tests** passed in **53.542 seconds**, with all opt-in live classes excluded and no skips. The physical PKX110 runs Android 16 / API 36.
- **Two actual campus read-only tests** passed in **5.138 seconds**, covering business reads and current-day regional inventory. The school returned **8 regions / 1708 seats / 0 unknown states**. No reservation, cancellation or seat-use mutation was submitted.
- **38 Python release-tool tests** passed in **0.409 seconds**.
- Debug, AndroidTest, R8-minified Release, both Lint variants and beta staging passed. Lint reports **0 errors / 50 debug and 51 release advisory warnings**.
- Staging verifies the original certificate SHA-256 `211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`, package identity, version metadata and disabled debugging.
- An SDK-only salted comparison confirms that encrypted accounts, selected account, preferences, manual favorites and appearance survived the original code 12 installation through code 13 testing. Ordinary session-cookie refreshes are outside that comparison.

Earlier code 12 UI-stage results remain in [ADB_ACCEPTANCE_UI_2026-10-15.md](ADB_ACCEPTANCE_UI_2026-10-15.md); they are not counted as fresh code 13 runs. Android 8 / API 26 and actual library-internal Wi-Fi check-in were not exercised by this release run.

## Prepared artifact and publication

The final committed candidate is staged after release preparation so its Git metadata identifies the reviewed source commit. Final installed-byte checks, publication assets and public update-source results are recorded here after preparation and publication. Released assets are never replaced.

Private evidence is ignored under `.private/qa-0.3.8/`, including `build.log`, `device-tests.log`, `live-readonly.log`, `python-tests.log`, `original-state-snapshot.log` and `after-tests-state.log`.
