# Native library functions

Version 0.3.2 implements application screens and interactions in Kotlin and Jetpack Compose. No original webpage is rendered, no website JavaScript runs, and no WebView or browser activity is present. Some school endpoints return HTML fragments; the data layer parses their business values and discards the markup before native rendering.

| Task | Native interaction | School data source |
| --- | --- | --- |
| Select date and filters | Native chips, selection dialogs and search field | `/self`, `/freeBook/ajaxGetRooms` |
| Find a seat | Colored status cards, immediate loaded-page availability filter, pagination | `/freeBook/ajaxSearch` |
| Find seat number | Match real labels in selected room catalogs | `/mapBook/getSeatsByRoom` |
| Use the room map | Pan/pinch native Canvas, seat details, accessible list | `/map`, `/mapBook/ajaxGetFloor`, `/mapBook/ajaxGetRooms`, `/mapBook/getSeatsByRoom` |
| Select booking times | Native time chips and reservation review | `/freeBook/ajaxGetTime`, `/freeBook/ajaxGetEndTime` |
| Submit reservation | Fresh account-bound token and one explicit confirmation | `/self`, `/selfRes` |
| Manage favorite seats | Per-account bookmarks and native common-seat cards | Local preferences, `/freeBook/fav` |
| Read/cancel reservations | Native history, details and fresh server-provided cancellation action | `/history?type=SEAT`, `/user/viewMoreHistory` |
| Read violations | Native cards and pagination | `/user/violations`, `/user/viewMoreViolations` |
| Check in / leave / return / finish | Native confirmations based on fresh server status | `/user/checkIn`, `/user/leave`, `/user/resume`, `/user/stopUsing` |
| Extend usage | Native end-time selection and confirmation | `/user/getTimeExtend`, `/user/extend` |
| Change password | Entry temporarily closed; Toast “修改密码当前未实现”, no form/submission | No request from application UI |
| Edit account remark | Save without password; preserve credentials/session and update display labels | Encrypted local account store |
| Connect campus network | Visible login requirement, Wi-Fi settings and reachability retry before login | Campus-only public login page |
| Sign out | Native confirmation and persistent local sign-out | `/logout` |
| Solve login captcha | Local ddddocr recognition | `/auth/createCaptcha`, `/auth/signIn` |
| Solve extra verification | Local ddddocr detection + text matching + automatic coordinates | `/cap/captcha/...`, `/cap/checkCaptcha` |

The app follows school availability, mode, location and verification restrictions. A successful HTTP response alone never establishes login, booking or password-change success. Mutation requests are not automatically retried; uncertain results require checking current state before another attempt.

The original numeric-name search does not find lettered labels, so native number lookup uses documented room catalogs. It applies date/building/room conditions; other time/power/window filters belong to ordinary search. Bookmarks store locations and trigger a fresh time query on selection.

Click captcha images and target text are handled by the same local ddddocr pipeline, never by manual user input. Proof tokens are scoped to the current account and expire locally; only server-confirmed proofs can be attached to a booking.
