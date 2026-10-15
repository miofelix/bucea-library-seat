# Unpublished candidate acceptance — 0.3.8, 2026-10-15

> Historical deployment record: Raspberry Pi, systemd, Tunnel, mirror status and server-test results below describe the service at the date of this record. The server implementation is retired; current Cloudflare Pages/R2 setup and activation checks are in [UPDATES.md](UPDATES.md). These results do not establish acceptance of the R2 deployment.

This **0.3.8 / versionCode 13** candidate was tested but **never published**. The user then chose to publish **1.0.0 / versionCode 14** as an official release; its final results belong in [ADB_ACCEPTANCE_1.0.0.md](ADB_ACCEPTANCE_1.0.0.md). This checkpoint includes compact four-column seat cards, automatic loading of all seat results, filtered layout cards, inline Favorites booking, shared section tabs, separate notices, the official-library link, navigation state retention, and targeted search/map retries developed since the published 0.3.7 beta.

## Fresh version 13 verification

- **76 JVM tests** passed without failures, errors or skips, including compatibility parsing for the new Chinese display name and legacy ASCII public filename.
- **144 independent ADB device tests** passed in **86.036 seconds**, with all opt-in live classes excluded and no skips. The physical PKX110 runs Android 16 / API 36. This final run supersedes the earlier 135-test checkpoint before the new result cards and booking flow.
- **Two actual campus read-only tests** passed in **5.787 seconds**, covering business reads and current-day regional inventory. The school returned **8 regions / 1708 seats / 0 unknown states**. No reservation, cancellation or seat-use mutation was submitted.
- **48 Python release-tool tests** passed, including canonical Chinese naming, draft asset readback, source checksums and mirror compatibility for unchanged Android clients.
- Debug, AndroidTest, R8-minified Release, both Lint variants and beta staging passed. Lint reports **0 errors / 50 debug and 51 release advisory warnings**.
- Staging verifies the original certificate SHA-256 `211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`, package identity, version metadata and disabled debugging.
- An SDK-only salted comparison confirms that encrypted accounts, selected account, preferences, manual favorites and appearance survived the original code 12 installation through code 13 testing. Ordinary session-cookie refreshes are outside that comparison.

The final presentation fixtures verify four short cards per row, green/red availability borders, long-press favorites and accessible full status, immediate filtering across all loaded results, and local-only booking restrictions. Controlled search tests cover automatic offset traversal, deduplication, exact interrupted-read retry, verification continuation, repeated offsets and stale canceled responses. The Favorites fixture exercises server-provided dates and start/end times, loading/retry/dismiss, explicit final confirmation and synthetic success while remaining on My Library; its submission callback is synthetic.

The layout regression fixture uses real page gestures in a 300dp × 580dp pane at normal and 1.6 font scale. It checks scrolling down to later seats and back to the common controls, stable switch anchors and sticky tabs, and independent map/list positions. The visual tab bar measures 44dp at both scales; its accessibility touch target may extend to 48dp. The page uses a single scroll container so the height-stability fix does not block vertical page scrolling.

Earlier code 12 UI-stage results remain in [ADB_ACCEPTANCE_UI_2026-10-15.md](ADB_ACCEPTANCE_UI_2026-10-15.md); they are not counted as fresh code 13 runs. Android 8 / API 26 and actual library-internal Wi-Fi check-in were not exercised by this release run.

## Candidate artifact

The signed, non-debuggable code 13 candidate passed an actual Release UI smoke run covering complete search results, server start/end time selection and confirmation preview without submission, reservations, Favorites, frequent seats and violations. It was installed through ADB; no code 13 GitHub release or public updater transaction occurred. The final published artifact will instead use version 1.0.0/code 14. Released assets are never replaced.

All future APK attachments use `北建大图书馆选座-vx.x.x.apk`, enforced by staging and publication validation; the beta suffix stays in the release tag. The mirror compatibility change is implemented and tested locally, but its deployment is explicitly deferred by the user. The existing Raspberry Pi Tailscale node key expired, and both known SSH routes were unreachable. Public mirror availability for code 13 is not claimed until that deployment occurs.

Private evidence is ignored under `.private/qa-0.3.8/`, including `page-scroll-build.log`, `final-device-tests.log`, `final-live-readonly.log`, `final-python-tests.log`, `page-scroll-focused-tests.log`, `final-map-switch-geometry.txt`, `original-state-snapshot.log` and `final-after-tests-state.log`.
