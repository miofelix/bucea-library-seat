# Release acceptance — 0.3.7, 2026-10-14

Version **0.3.7 / versionCode 12** adds 奶龙黄 and matching launcher icons, the selected A「留白馆览」homepage, automatic current-day regional statistics, and clearer seat maps. Regional cards are compact, use two columns, and are expanded by default with a manual collapse control. Search-result cards use two columns and matching row heights.

## Final verification

- **64 JVM tests** passed without failures, errors or skips. Repository and navigation fixtures cover exact target validation, Shanghai dates, partial responses and verification stops; timeline fixtures cover valid/missing schedules, chronological ordering and historical states.
- **125 independent ADB tests** passed in **41.887 seconds**, with no skips. The complete final run covers visible metrics, compact equal-height cards at large font scale, whole-card selection, regional collapse/navigation, direct map presentation, reservation timelines/cancellation confirmation, six launcher identities, QQ click/long-press separation and existing updater/account behavior.
- Real campus read-only business/regional tests passed in **5.977 seconds**. The school returned **8 regions / 1708 seats / 0 unknown states**. No actual booking, cancellation or seat-use operation was submitted; success Toasts are wired to confirmed operation results rather than manufactured in live tests.
- Debug, AndroidTest and R8-minified Release packaging passed. Both Lint variants reported zero errors (**50 debug / 51 release advisory warnings**). **38 Python tests** passed in **0.498 seconds**.
- The non-debuggable code 12 Release was installed with ADB. PackageManager metadata and full installed APK readback passed; the final commit-stamped artifact fingerprint is recorded after preparation below. Staging verifies the original certificate `211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`.
- Salted comparisons passed against the initial code 11 preferences at earlier checkpoints. Before final installation that old baseline already differed during the interactive session; it is not claimed unchanged through the whole session. A fresh current-state snapshot followed by final Release overwrite passed, preserving current encrypted accounts, selection, preferences, manual favorites and theme. Ordinary cookie refreshes are outside that comparison.
- Actual final Release UI automatically synchronized regional cards. Clicking a region opened its exact map immediately with the selector form omitted; Back returned home. The reservation timeline was visible and the requested duplicate search/layout shortcuts were absent. Default collapse/expansion had also passed in the preceding Release checkpoint and final full device tests.

Evidence: `final-complete-build.log`, `final-all-device-tests.log`, `final-live-readonly.log`, `final-installed-check.log`, `final-current-state-preserved.log` and `final-release-ui/` in the private run directory. The final run includes the corrected visible-label geometry and stable map-fit screenshot checks.

## Corrected findings

New screenshot/text fixtures initially failed because accessibility state can precede compositor drawing and this device exposes metric values as sibling nodes. Bounded frame waits and precise same-metric matching corrected those fixtures without lowering checks. Failed runs remain in private evidence.

Normal `/map` pages include a callback referencing `clickVerifyCode` and a rate-limit message. A broad string check incorrectly treated that normal script as a live challenge. Full-page detection now distinguishes ordinary callback wiring from direct challenges, JSON limits and visible rate-limit content. Live map and regional queries passed after the correction.

Actual reserved seats use CSS `agreement` and title **“当前已有预约”**. The classifier missed “已有预约”, so 11 such seats appeared unknown. The corrected classifier matches the real title while retaining unknown handling for unrecognized values. Final live regional readback reports zero unknown seats.

The school's transport source confirms the existing captcha routes. A forced captcha test against an ordinary page's unissued blank token returned HTTP 404 and is not counted as completed verification. The application now reloads the current saved session and requires an issued valid token before sending a captcha request. Blank placeholders show a qualified message rather than repeated requests or misleading login advice. Normal map and regional reads no longer enter the false verification flow; a genuine new school rate-limit challenge was not forced or claimed as passed.

Private evidence is ignored under `.private/qa-0.3.7/`, including logs, actual API diagnostics, installed APK readback and UI screenshots. An intermittent `ENETUNREACH` diagnostic remains a failure; later campus reads succeeded. Android 8/API 26, actual library-internal Wi-Fi check-in and dependent seat-use actions were not exercised. This version's installation uses ADB and does not claim a new end-to-end public system-installer transaction.
