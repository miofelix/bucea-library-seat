# Reservation status and student login validation

The fixes were developed in the isolated worktree `../bucea-library-reservation-login`,
on branch `fix/reservation-status-zs-login`, starting from `214681f`. The main worktree
was not modified.

## Stage 1: alphanumeric student numbers

Commit: `82b94da` — `fix(auth): accept alphanumeric student numbers`.

The account vault previously rejected every nonnumeric username before attempting
login. It now accepts 4–30 ASCII letters and digits, preserving the exact case and
the existing numeric format. Synthetic device tests cover persistence, password
updates without duplicate accounts, and rejection of malformed input without
changing the vault.

The supplied transfer student's account (`2025ZS…0331`) successfully authenticated
on the real campus service using the supplied password and automatic CAPTCHA OCR.
Subsequent session restoration confirmed the exact username returned by the server.
The history page returned four records with `使用中`, `已取消` and `已完成` statuses.

`LiveStudentNumberLoginTest` receives only a temporary filename through
instrumentation arguments. Credentials are read from the app's private files
directory and deleted before login. Its separate encrypted vault and cookies are
cleared afterward; the test verifies that the production vault remains unchanged.
History pages omit the home page's username field, so the test rejects login forms
and conflicting identities there and rechecks the exact identity through `restore()`.

## Stage 2: reservation statuses

Commit: `0da1430` — `fix(reservations): preserve complete history statuses`.

History parsing now preserves the server's static `.normal` status label rather
than filtering it through an incomplete list. Legacy unstyled labels also recognize
`已履约` and `失约`. The labels covered by the fallback are `已预约`, `预约`, `待签到`,
`已签到`, `使用中`, `暂离`, `已履约`, `履约`, `失约`, `已失约`, `已取消`, `取消`,
`已完成`, `已违约`, `违约` and `已结束`.

Cancellation eligibility, timeline grouping, status badges and receipt validation
share the terminal-state check. Fulfilled and missed reservations are historical
and cannot expose cancellation. The server record ID remains stable even if its
only source is a cancellation form on an inactive record.

The supplied account did not return `已履约` or `失约` in its first history page.
Those states, new `.normal` labels, pagination and terminal-state behavior were
verified with synthetic fixtures; they were not created by changing real bookings.

## Stage 3: checks and installed artifact

Device: PKX110, Android API 36. Results:

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | 82 passed, no failures or skips |
| Account/session, native library functions and cancellation device tests | 37 passed |
| Opt-in live student login and read-only history test | 1 passed |
| Debug, AndroidTest and Release APK builds | Passed |
| Debug / Release lint | 0 errors; 50 / 51 warnings |
| Installed Release APK | Byte-for-byte SHA-256 match; not debuggable; launcher started |

The final Release APK was explicitly installed with `adb install -r`, preserving
the existing installation. `connectedDebugAndroidTest` was not used because its
automatic uninstall can remove user data. Real acceptance submitted no reservation,
cancellation, seat-use action or password change.

Artifact: `app/build/outputs/apk/release/app-release.apk`, 55,615,986 bytes.

SHA-256: `b053cecb8e78256e6e914e790a0e3a5ef0fd211ccad9a1336b44d38e85804648`.

The installed build retains version `1.0.1` / code `15`. This is a local verification
build; no public release, push or deployment was performed.
