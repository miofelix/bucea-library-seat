# Native library functions

Version 0.2.0 adds native seat search, reservation selection and a personal library screen. The original website remains available for the room map, school verification and full feature access.

| User task | Native behavior | School source |
| --- | --- | --- |
| Choose a booking date | Shows dates currently opened by the school | `/self`, `#options_onDate` |
| Find a seat | Date, building, room, duration, start/end time, power and window filters; paginated seat cards | `/freeBook/ajaxSearch` |
| Find a seat number | Matches numeric prefixes or full labels against the selected date and room catalogs | `/mapBook/getSeatsByRoom` |
| Change building | Loads that building's real room options | `/freeBook/ajaxGetRooms` |
| Choose start/end times | Shows only server-returned available time options | `/freeBook/ajaxGetTime`, `/freeBook/ajaxGetEndTime` |
| Book a seat | Reviews account/date/seat/times, rechecks time availability, obtains a fresh account-bound token, then submits once | `/self`, `/selfRes` |
| Keep favorite seats | Saves local bookmarks per account and combines them with school common-seat records | Local preferences, `/freeBook/fav` |
| View booking history | Native record cards and load-more, without treating hidden controls as records | `/history?type=SEAT`, `/user/viewMoreHistory` |
| View violations | Native record cards and load-more | `/user/violations`, `/user/viewMoreViolations` |
| Check in / leave / return / finish | Provides confirmation and submits only if the current server state allows it | `/user/checkIn`, `/user/leave`, `/user/resume`, `/user/stopUsing` |
| Extend usage | Loads allowed extension end times, asks for confirmation and revalidates the chosen time | `/user/getTimeExtend`, `/user/extend` |
| Use the full website | Opens the requested authenticated feature page with mobile styles | Original library routes |

The school can restrict seat operations to a particular network or mode. Native actions follow the original page's `softMode`, reservation state and Wi-Fi restriction flags. A hidden or unavailable school action is not exposed as an enabled native button.

Search rate verification and additional reservation verification open the original school page. HTTP success alone does not count as booking success: native result handling requires an affirmative booking result or a receipt. An uncertain submission disables immediate resubmission and directs the user to verify booking history.

Local bookmarks store locations, not guaranteed availability. Selecting a bookmark queries available times again. Removing a local bookmark does not alter the school's usage history.

The original numeric name-search endpoint does not find the school's lettered seat labels. Native number lookup uses the documented room maps and matches labels locally; it uses date/building/room conditions, while time/power/window filters apply to regular search. Each seat selection still requests fresh available times from the school.
