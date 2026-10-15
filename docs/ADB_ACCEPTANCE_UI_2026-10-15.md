# UI acceptance — 2026-10-15

Verified on the physical PKX110, Android 16 / API 36. This iteration retains version 0.3.7 / code 12 and does not publish a new release.

## Phase 1: compact tabs and seat header

- Seat selection and My Library share the gray rounded segmented control with a white selected item and theme-colored text.
- Both rendered tracks measure 154 physical pixels at density 3.5: **44dp**, reduced from 48dp for seat selection and 56dp for My Library. Selection, disabled controls, fixed positioning and independent mode state remain covered.
- Seat selection adds the shared `BUCEA / LIBRARY` decoration and “为专注，找一个喜欢的位置。” subtitle.
- Actual Release screenshots at font scales 1.0 and 1.6 confirm readable labels; the four My Library labels wrap and grow naturally at the larger scale. The system font scale was restored to 1.0.

## Phase 2: official website and notices

- Settings opens `https://lib.bucea.edu.cn/`; ADB confirmed the outgoing browser intent has the official URL. The footer reads `BUCEA-LIBRARY-SEAT · 非官方客户端`.
- Expanded notices use separate rows and dividers. Parsing preserves paragraphs, list items and HTML line breaks, retains short notices, and excludes the nested occupancy summary structurally.
- Actual final Release inspection found **6 separately displayed school notice rules**, with no occupancy counts or labels mixed into the notice card.

## Validation

- **15 focused ADB UI tests passed in 45.413 seconds**, covering the two tab modes, My Library navigation/actions, dashboard and settings/account fixtures. The fixed-position fixture compares visible tab surfaces because Compose expands the touch targets of compact controls.
- **75 JVM tests passed**, including four notice parsing cases for source boundaries, short entries, inline text and nested occupancy metrics.
- Debug, AndroidTest and R8-minified Release builds passed. Debug Lint reports **0 errors / 50 advisory warnings**.
- The final non-debuggable Release was installed with ADB and inspected directly. No booking, cancellation, account or seat-use mutation was submitted during the actual UI walkthrough.

Logs, hierarchy snapshots and screenshots are private under `.private/qa-compact-tabs-20261015/`. Authoritative final evidence includes `verified-build.log`, `final-device-tests.log`, `final-ui-acceptance.log`, `final-notice-check.log`, `final-release-install.log` and `final-notice-*.png`.

## Compact layout follow-up

The next iteration applies shared 16dp page margins, 12dp section spacing, smaller headers and tighter cards, settings rows, records and selection sheets. Ordinary-width home statistics use three columns, and account actions remain inline when space permits; narrow screens and large fonts expand naturally.

Seat search now places the advanced filter group above the optional seat number. Its secondary filter entry uses a 36dp minimum, the availability area 40dp, and the editable seat-number field 48dp. Loaded-page counts share the availability subtitle. Primary actions, account operations and favorites retain 48dp targets; the search scroll safety inset and map gesture logic remain intact.

- **22 focused ADB UI tests passed in 56.733 seconds**, covering shared screens, seat cards, regional cards, map rendering, accounts, settings and community controls. The adaptive home/account refinement then passed **9 targeted tests in 22.597 seconds**; the compact seat controls passed **3 seat UI tests in 16.189 seconds**.
- The actual final Release confirms the advanced entry precedes the seat number in both collapsed and expanded states. The seat-number accessibility surface measures **168 pixels / 48dp**; typing a temporary test value works, and hidden advanced options correctly report their numbered-query state. The temporary value was discarded by restarting the app.
- Native read-only smoke passed search, server time selection, reservation preview without submission, reservations, local favorites, frequent seats and violations.
- Before/after normal-font captures of the same physical device show the regional heading approximately **141dp higher**, the account-add action **124dp higher**, and the official website entry **102dp higher**. These are layout comparisons, not comparisons of changing seat availability.
- Final Release captures cover home, both seat modes, My Library, accounts and settings at normal and **1.6 font scale**. Text and controls remain readable, and the system font scale was restored to **1.0**.
- Final Debug, AndroidTest and R8 Release packaging passed; Debug Lint reports **0 errors / 50 advisory warnings**. The phone retains the final non-debuggable Release, version 0.3.7 / code 12.

Evidence is ignored under `.private/qa-compact-layout-20261015/`: `device-tests.log`, `adaptive-device-tests.log`, `compact-controls-device-tests.log`, `verified-controls-build.log`, `final-order-check.log`, `final-controls-check.log`, `reservation-smoke.log`, `large-font.log`, and the `before/`, `final/`, `large/` screenshot directories.
