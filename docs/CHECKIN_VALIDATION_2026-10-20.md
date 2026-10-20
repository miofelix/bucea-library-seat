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

## 续约修复

续约时会短暂弹出错误提示，并显示“暂时无法同步：登录已失效，请重新登录；正在同步预约状态，请确认结果后再操作”；点击“重试”后恢复正常。

17:37 实测的请求序列：

1. `GET /user/getTimeExtend` → `{"status":"success","data":{"endTimes":[{"id":"1110","value":"18:30"}]}}`，两次读取结果一致。
2. `GET /user/extend?endTime=1110` → `302` 到 `/error/serverError`（学校通用的“出错了 Oops…”页面），没有 JSON 结果。
3. 服务器实际已保存新的结束时间：预约记录变为“今天 17:20 -- 18:30”，再次查询返回 `{"status":"fail","message":"该预约已无法续约"}`。

旧实现把任何 HTML 回应都当作登录页，报告“登录已失效”，并在随后成功的刷新中保留这条错误，直到点击“重试”。学校拒绝续约时，回答也被显示为“暂时无法同步”。

修复：

- `/user/extend` 返回非 JSON 页面时，登录页仍报告登录失效；否则只读一次预约记录，确认今天使用中的预约已按所选时间结束才报告“续约成功，可使用至 HH:MM”，否则报告“续约未生效，请刷新预约状态后重试”。续约请求只发送一次。
- `extensionTimes` 返回学校提供的时段和说明；学校拒绝或没有可选时段时显示为提示和 Toast，不再显示“暂时无法同步”。
- 其他座位操作若返回非 JSON 页面，不再误报登录失效，提示刷新核实。

| 预约 | 操作 | 结果 |
| --- | --- | --- |
| 056B 17:20–18:00 | 旧逻辑续约至 18:30 | 复现：服务器已续约，App 显示“登录已失效…”及“暂时无法同步” |
| 056B（已续约） | 修复后再次点击续约 | Toast 与说明“该预约已无法续约”，无同步错误卡片，操作按钮保留 |
| 007D 17:58–18:30 | 修复后签到、续约至 19:00 | Toast“续约成功”，说明“续约成功，可使用至19:00”，时间线显示 17:58 — 19:00；随后结束使用 |

056B 经用户同意后结束使用，以便新建可续约的测试预约。所有测试预约均已结束，违约记录未新增。

## 图书馆 Wi-Fi 提示卡片

- 学校返回 `RESERVE`、`CHECK_IN`（含使用中别名）、`AWAY`、`COMPLETE` 等允许座位操作的状态时，隐藏“签到需连接图书馆 Wi-Fi”卡片；待签到状态只提示在预约允许的时间内操作。
- `LOC_RESTRICT` 或座位操作明确返回位置限制时保留卡片。位置限制通过结果字段传到界面，自动刷新不会立即覆盖这次拒绝；用户连接后手动刷新，以最新学校状态决定是否隐藏。
- 没有位置状态码的旧响应仍识别“仅在场馆内”“馆内网络”说明；不在签到时间内等其他失败只显示学校原始原因。网络判断不读取或匹配手机 SSID。

## 自动化检查

- 最终构建的 `:app:testDebugUnitTest`、`:app:testReleaseUnitTest` 均通过，各 96 项无失败。`ReservationActionsTest` 11 项覆盖实测状态与响应结构（凭证号已替换）、续约错误页面回放、位置限制及非位置失败。
- 最终 Debug/AndroidTest 构建及 Debug/Release Lint 均通过，Lint 0 个 Error。
- 设备 `am instrument`（包含 Wi-Fi 卡片修复的调试构建）：`LibraryFunctionsInstrumentedTest` 18 项、`LibraryHubUiInstrumentedTest` 8 项，共 26 项通过。新增界面回归先显示位置拒绝的提示卡片，再依次验证待签到、使用中及其别名、暂离、完成状态隐藏卡片且保留操作，并滚动至列表底部检查；字体与旋转设置未改变。
- 现有账号执行 `LiveCheckInDiagnosticTest -e live_library true`：1 项通过；本轮只读取当前状态和记录，没有提交预约或座位操作。
- 可选诊断 `LiveCheckInDiagnosticTest`：默认只读；`-e live_extension_read true` 连续读取两次可续约时段；仅在 `-e live_checkin_submit true` 且状态为 `RESERVE` 时发送一次签到。日志只输出状态字段。

## 设备状态与未覆盖项

最终代码提交为 `f30c7b6`，包含签到、续约及 Wi-Fi 提示修复。`./gradlew -PreleaseDistributions=true :app:assembleRelease` 通过后，以 `adb install -r` 覆盖安装 ARM64 本地 Release 构建，保留版本 **1.0.2 / versionCode 16** 和现有账号数据。

- APK SHA-256：`6204aea80b65da59a33e940e753b130d7d343b92e0571a1efdcd950c784642c8`；从手机拉回的已安装 APK 与构建产物一致。
- 签名证书 SHA-256：`211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`，与原安装包一致；设备包标志没有 `DEBUGGABLE`，ABI 为 `arm64-v8a`。
- 最终 Release 启动后，现有账号仍能读取真实预约记录，007D 显示“结束使用”、17:58—19:00；当前无进行中的预约，无同步错误和 Wi-Fi 提示卡片。最终截图为 `.private/checkin-debug/final-release-hub-verified.png`。
- 测试包 `cn.edu.bucea.library.test` 已卸载；App 内 `files/checkin-diagnostic` 和手机上的临时 UI 文件已删除。字体比例 `1.0`，旋转设置 `0 / 0`，与本轮开始一致。

原 v1.0.2 安装包备份、最终本地 APK、截图、页面和检查日志位于忽略的 `.private/checkin-debug/`。

未实测：校园网馆外的 `LOC_RESTRICT`，以及超出签到时间窗的失败提示。
