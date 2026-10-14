# Campus access diagnosis — 2026-10-14

The user reported that the published **0.3.5 / code 10** application could not access the library despite being connected to campus Wi-Fi. The device was connected through ADB. Testing used public read-only requests; no password, reservation, cancellation or seat-use operation was submitted.

## Observed failure and recovery

- The phone was connected to the campus Wi-Fi, with an active, validated Wi-Fi default route and no configured global HTTP proxy. The campus DNS host responded to ICMP in about 29.6 ms. The actual public HTTPS update manifest returned HTTP 200 from the application UID.
- Independent shell TCP connections to the configured library **10.1.20.7:80** and port 443 both timed out after four seconds. A direct unauthenticated HTTP request returned zero response bytes. The installed application's manual retry also remained unavailable.
- A separate SDK-only probe running under the application's UID confirmed that cleartext HTTP was permitted for the configured IP, but `GET /login?targetUri=%2F` failed with **SocketTimeoutException after 8.012 seconds**, without an HTTP response. This excludes the app's cleartext configuration or native UI state as the cause of that transport failure. These probes do not distinguish a campus routing/access-control issue from a library service outage.
- Later the same application-UID probe returned **HTTP 200 in 155 ms**, with the library login form marker. The real campus read-only test then passed in **3.348 seconds**, including authenticated session identity, availability, booking preparation without submission, history and native map reads.

The failure was intermittent. Its eventual recovery is not attributed to the UI patch: the independent probe also recovered. Failed connectivity checks are retained as failure evidence, not reported as passed transactions.

## Client correction and validation

Returning from Wi-Fi settings previously left the last `UNREACHABLE` result visible until manual refresh. The native activity now asks the existing MainViewModel to recheck on foreground return only when the prior access failed and no operation is busy. Startup checks, active operations and reachable sessions are not interrupted. It preserves the ordinary explicit-logout and account restoration flow.

Unreachable-state guidance now says **“暂时无法访问图书馆”** and explains that the campus route or library service may be temporarily unavailable. The dashboard no longer repeats an instruction to connect to campus Wi-Fi for a known failed access check.

- **107 independent device tests passed in 19.541 seconds**, excluding the opt-in live classes, with no skips. The new foreground policy case covers every access state and busy state.
- **23 JVM tests passed**. Debug/test builds and both Lint variants passed with zero errors; the final wording build also passed (47 debug / 48 release advisory warnings).
- Actual native recovery acceptance passed after temporarily disabling Wi-Fi: qualified failure guidance appeared; returning from Wi-Fi settings after restoring a validated route automatically recovered campus access and the saved account session. Wi-Fi and mobile-data preferences were restored.
- Salted private-state comparisons passed before and after temporary debug testing and restoration of the published release. Credentials, selected account, preferences, bookmarks and appearance were preserved; ordinary session refresh may update cookies.

The automatic foreground correction is committed source for a subsequent build. The device is restored to the unchanged, published **v0.3.5-beta.1 / code 10** APK (SHA-256 `a40dd3492492814fd196b7b99d33fb918c5908452bf4efaf186ae0f7dd14f269`). Actual native acceptance on that restored published APK passed saved-session restoration and a future-date seat query returning real results. Published release assets were not replaced.

## Evidence

Private artifacts are ignored under `.private/campus-access-20261014/` and `.private/qa-0.3.4/`. They include `build.log`, `final-ui-build.log`, `independent.log`, `foreground-recovery-ui.log`, `campus-recovery-live-read-sequential.log`, application-UID endpoint output, and private UI captures. The first UI check expected continued unavailability but found the recovered connection instead; it is superseded by the explicit disconnect/reconnect acceptance. A live read attempt interrupted by another instrumentation process is excluded; the sequential rerun above is the recorded result.
