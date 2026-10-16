# Reservation card and status presentation validation

This follow-up was completed in the isolated worktree `../bucea-library-reservation-login`.

## Status coverage

`结束使用` is now retained as a server status label and shares the terminal-state
behavior with fulfilled and finished records: it appears in the history card, is
placed in the history timeline, and cannot expose cancellation. Its receipt status
cannot be interpreted as a newly confirmed reservation.

The status badge and timeline marker use the displayed status meaning. The six
semantic palettes are:

| Meaning | Statuses | Foreground/background contrast |
| --- | --- | ---: |
| Reserved | `已预约`, `预约`, `待签到` and server reserved codes | 6.16:1 |
| In use | `使用中`, `已签到` and checked-in codes | 5.15:1 |
| Away | `暂离` and away codes | 6.84:1 |
| Fulfilled | `已履约`, `履约`, `已完成`, `结束使用` and finished codes | 6.81:1 |
| Missed | `失约`, `已失约`, `违约` and missed codes | 5.91:1 |
| Canceled/ended/unknown | `已取消`, `已结束` and related codes | 6.87:1 |

The live campus stylesheet was read through the connected device on 2026-10-16.
It returned HTTP 200 with SHA-256
`e238d4e278c2ae4cb564d6c9c06546b4eda87ac11b4eaf8018e9b063f11adbdd`. Its shared
selector includes `a.Missed` and `a.Canceled`, while the color declarations use
lowercase `.missed` and `.canceled`; the real canceled snapshot uses `.Missed`.
Because those class names do not reliably identify the state color, the native UI
uses the server's displayed text and the palettes above. All badge colors have
opaque fills and at least 4.5:1 text contrast.

## Card density and touch targets

Timeline cards now use 10 dp vertical padding and 6 dp internal spacing. Date and
status share a row, and the record title is combined with the details affordance,
removing a duplicate title row. The time remains prominent, location remains
visible, and both the details and cancellation actions retain at least 48 dp
touch height. Status text remains an independent accessibility node and can grow
at 1.6x font scale.

The connected PKX110 / Android API 36 device passed all six `LibraryHubUiInstrumentedTest`
tests, including the 1.6x-font fixture covering `结束使用`, `已履约`, details and
cancellation confirmation. The JVM suite passed 84 tests with no failures.

## Follow-up correction

The next device review found two presentation gaps. Timeline cards had measured as
full width after the 14 dp timeline marker, so their right side could overflow; the
details button also had zero horizontal padding. The card now takes the remaining
timeline width and the button has 8 dp horizontal / 4 dp vertical content padding,
while retaining a 48 dp touch target. A 1.6x-font test checks the label inset and
the complete seat and details text.

The server can expose an in-use reservation as `IN_USE`, `USING` or `CHECKED_IN`.
These codes now expose the `结束使用` action. History records also preserve linked
or non-anchor terminal labels, and `STOPPED` / `FINISHED` render as `结束使用` on the
card. The final follow-up device run passed 25 instrumented tests and the JVM suite
passed 85 tests, all without failures.

The card keeps `已完成` distinct from `结束使用`. When a history record includes an
explicit `状态：结束使用` detail, that detail takes precedence over a separate
machine or summary field, so the collapsed card and its detail dialog show
`结束使用` consistently.

When the detail endpoint supplies the authoritative status only after a card is
opened, the resolved record is now written back to the timeline list as well. Closing
the dialog therefore keeps the newly resolved `结束使用` label on the collapsed card.
