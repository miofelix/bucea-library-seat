# 图书馆 Wi-Fi 签到修复与实测

2026-10-20 在图书馆内使用 PKX110 / Android 16 真机和设备上已有账号完成。手机连接 `BUCEA-Library`（10.73.187.248），默认网络为该 Wi-Fi，无 VPN，`10.1.20.7` 经 `wlan0` 访问。

## 问题与原因

有待签到预约时，App 不显示“签到”，提示“图书馆系统暂未开放在线座位操作”；暂离、返回、续约和结束使用同样不会出现。

学校页面内嵌的 `userInfo` 在图书馆 Wi-Fi 下的实际值：

| 时机 | `currentReservationStatus` | `softMode` |
| --- | --- | --- |
| 无预约 | `NO_RESERVATION` | `false` |
| 预约后待签到 | `RESERVE` | `false` |
| 签到后 | `CHECK_IN` | `false` |

学校脚本只用 `softMode` 显示或隐藏馆内终端式的顶部按钮栏；按钮是否可用由预约状态决定，馆外限制由 `LOC_RESTRICT`（“该功能仅在场馆内有效”）表示。公开公告也说明签到与续约可在触摸屏或连接图书馆 Wi-Fi 后在网页端完成，有效期为开始时间前后 30 分钟。旧实现要求 `softMode=true` 才提供任何座位操作，因此在图书馆 Wi-Fi 下始终无法签到。

## 修复

- `parseUserState` 只按服务器状态给出操作：`RESERVE` → 签到；`CHECK_IN` → 续约、暂离、结束使用；`AWAY` → 返回、结束使用；`COMPLETE` → 结束使用。`LOC_RESTRICT` 和 `NO_RESERVATION` 仍不提供操作。`softMode` 只作为数据保留。
- 当天首次登记入场后（`userCheckedIn: true`、`lastIn`），学校会在约一分钟内自动将新预约转为使用中。此时点击签到，`performAction` 读取最新状态后不再发送请求，提示“当前预约已签到，座位正在使用中”，不再显示同步错误。

## 实际操作结果

| 预约 | 操作 | 服务器结果 |
| --- | --- | --- |
| 013B 16:39–17:00 | App 预约 | 预约成功；`RESERVE`、`softMode=false`，旧版无操作按钮 |
| | 诊断测试发送一次 `GET /user/checkIn` | `{"status":"success","message":"您已登记入场"}`，状态 `CHECK_IN`（使用中） |
| | 修复后 App：暂离 | 暂离中，“请于17时00分前返回” |
| | 返回座位 | 使用中，“您已登记入场” |
| | 续约 | “该预约已无法续约”，未发生续约 |
| | 结束使用 | “成功停止使用当前预约”，回到无预约 |
| 019D 16:57–17:30 | App 预约后显示签到 | 确认前已被学校自动签到（`lastIn` 16:46），App 未重复发送；随后结束使用 |
| 009C 17:07–17:30 | App 预约并点击签到 | “签到成功”，提示“您已登记入场”，使用中；随后结束使用 |

三条测试预约均为“结束使用”，结束时无进行中的预约。违约记录未新增，最新一条仍是此前的 2026-10-13 迟到违约。

## 自动化检查

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`：通过，JVM 90 项无失败，其中新增 `ReservationActionsTest` 5 项使用本次实测的状态与响应结构（凭证号已替换）。
- 设备 `am instrument`：`LibraryFunctionsInstrumentedTest` 18 项通过（自动签到处理加入前的构建）；最终调试构建上 `LibraryHubUiInstrumentedTest` 7 项通过，字体与旋转设置未改变。
- 新增可选诊断 `LiveCheckInDiagnosticTest`：默认只读；仅在 `-e live_checkin_submit true` 且状态为 `RESERVE` 时发送一次签到，日志只输出状态字段。

## 设备最终状态与未覆盖项

手机安装本次修改的 arm64-v8a Release 本地构建（versionCode 16，未发布，SHA-256 `96b6c81dff80d854264512786a4fc0b936b6cfb97d83e61c48a256c3b3cbb040`，原签名证书，非调试），账号会话保留；测试包已卸载，设备内诊断页面已删除。原 v1.0.2 安装包备份及截图、页面等私有证据位于忽略的 `.private/checkin-debug/`。

未实测：校园网馆外的 `LOC_RESTRICT`、超出签到时间窗的失败提示，以及有可续约时段时的实际续约。服务器拒绝续约时，消息目前显示在“暂时无法同步”卡片中，可后续调整措辞。
