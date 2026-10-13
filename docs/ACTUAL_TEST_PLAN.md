# Actual campus-service acceptance plan

This plan covers the native client against the real campus service. A completed row needs the observed server result and native screen behavior; a synthetic test or a visible button does not establish that a live operation succeeded. Record outcomes, device/build and remaining server restrictions in `VALIDATION.md`.

Use one connected Android device through adb and one active test run. Keep authenticated captures, UI hierarchies and screenshots in ignored `.private/` paths. Passwords, cookies, CSRF tokens, CAPTCHA proof tokens and password recovery data must not appear in console output, instrumentation arguments or committed files. Private screenshots can contain account labels; never capture revealed passwords.

## Function inventory and evidence

| Function | Live exercise | Required evidence and final state |
| --- | --- | --- |
| Login and restore | Sign out, log in with automatic OCR, restart app | Server confirms the exact saved account; no human CAPTCHA entry; session restores after restart |
| Multiple accounts | Switch between available authorized accounts; return to original | Names, history and bookmarks follow the selected account; sessions remain isolated |
| Login failure | Exercise an isolated synthetic account in tests | Wrong credentials stop further submissions for that account; network/CAPTCHA failures do not switch accounts |
| Public dashboard | Refresh connected, temporarily disconnected, reconnected | Real seat counts and announcements; retry works; no fabricated values |
| Seat filters | Date, building, unrestricted room, specific room, duration, start/end, power and window | Selected values match the requested school options and affect ordinary results |
| Number lookup | Numeric prefix, exact lettered label, nonexistent label | Real matching labels only; date/building/room applied; empty query handled honestly |
| Result pagination | Next, previous and new filter query | Distinct real results, correct page count, view moves to result heading and new query resets paging |
| Native map | Date/building/floor/room, pan, pinch, list view, seat details | Server geometry/status retained; local-only or unavailable seats cannot be booked |
| Available time selection | Choose seat/start/end; return and reopen | Only server-enabled times selectable; changing start clears end; booking preview shows account/date/seat/time |
| Reservation submission | Book one server-available future slot, then read current/history | Explicit review submits once; server receipt and matching native history confirm booking |
| Reservation cancellation | Cancel the reservation just created using its current server action | Confirmed cancellation, updated native history and no remaining test reservation |
| Current seat actions | Check in, leave, resume, extend, finish when current state/location permits | Fresh allowed action and server result; verify state after every transition; finish test usage |
| History and violations | Detail cards, load more, refresh | Real server records/status, stable IDs and no invented empty state after a load error |
| Bookmarks | Add test bookmark, reopen times/map, remove | Same-account persistence and restored original bookmark set |
| Automatic extra verification | Obtain image challenge and submit locally computed coordinates | School returns proof for current account; bounded retries; no manual point selection |
| Password change | Complete the controlled round trip below | Temporary password authenticates; original password restored and authenticates; vault contains original again |
| Persistent logout | Logout, restart, refresh, then explicit login | Session removed; no automatic login until user requests it; explicit login succeeds |
| Native architecture | Inspect installed APK manifest/assets and foreground activity | Only native activity/UI; no browser/WebView or bundled page/script assets |
| Layout/accessibility | Portrait/landscape, narrow width, larger font, keyboard, Back | All actions reachable, two-column cards align, dialogs scroll, confirmation remains visible, Back preserves sensible navigation |

If the school forbids an operation because of location, time or reservation state, record its exact restriction and the state observed. Do not classify a hidden action as a successful transaction. For this run, the user explicitly deferred library-Wi-Fi sign-in and the dependent leave, resume, extend and finish sequence.

## Reservation and seat-action sequence

1. Read current reservation and history before any write. Preserve existing user reservations and identify the exact new test reservation separately.
2. Choose an available future slot from school options. Review the displayed account, date, seat and times, then explicitly submit once.
3. Read back current state/history and match the test booking by its receipt and details. An ambiguous response requires readback before any new submission.
4. Exercise cancellation using the fresh server-provided cancel action for that test record. Confirm the canceled state in history.
5. Exercise current-use operations only when the server exposes them and physical/location requirements are met. Follow the permitted lifecycle: check in → optional leave → resume → optional extension → finish. Confirm every transition before continuing.
6. Verify no new test reservation or active usage remains. Remove only bookmarks introduced by this run and restore the original account selection and display settings.

## Password round trip and recovery

Password writes require a recoverable original password throughout the round trip. The supplied account's production vault alone is insufficient: a confirmed change updates that vault to the temporary password.

Before the first write, durably save the original and temporary values in a separate private recovery namespace encrypted with Android Keystore. A scoped `ContextWrapper` can route `AccountStore` to a dedicated recovery preference file; do not overwrite the production `library_vault` namespace or pass either secret through adb command arguments. Verify the encrypted recovery record can be reopened before proceeding. Keep both values until final restoration is confirmed.

| Step | Action | Checkpoint / recovery behavior |
| --- | --- | --- |
| 1 | Read password form and verify authenticated account | `/user/updateCredentials`, fields `password`, `newPassword`, `newPasswordConfirm`, fresh CSRF token and URI are present |
| 2 | Submit original → temporary once through native flow | Capture the server's explicit password result privately; HTTP 200 alone is insufficient |
| 3 | Authenticate with temporary after clearing cached session | Successful exact-account login confirms which credential is active; preserve original recovery value |
| 4 | Fetch a new password form; submit temporary → original once | Fresh token; verify server result; do not reuse the first form or retry an uncertain submission |
| 5 | Authenticate with original after clearing cached session | Exact-account success confirms restoration; production saved password equals original |
| 6 | Delete encrypted recovery records | Only after original login and vault restoration are both verified |

If either change has an unknown outcome or a timeout, stop repeated mutation submissions. Determine the active credential with one bounded automatic-login attempt for the candidate expected after that write, use a fresh form, and complete restoration. If the server refuses restoration because of password policy/history, retain the encrypted recovery record and report the active credential state privately without printing either password. Do not report the round trip as complete.

The native parser accepts an explicit password-success receipt. If actual service wording differs, inspect the captured message, add that precise response to the parser and a synthetic regression case, and verify the active credential before modifying saved account data. Never treat generic success text, unrelated seat-operation messages or an unchanged password form as proof.

## Outcome record

For every live mutation record the initial state, one submitted action, server confirmation/readback, cleanup and final state. Distinguish completed transactions, expected server restrictions and failures. Include APK version, test completion codes and artifact paths in `VALIDATION.md`; keep sensitive artifacts ignored.
