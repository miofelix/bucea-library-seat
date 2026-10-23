# 签到禁用状态与首页预约卡片验证

验证日期：2026-10-23。设备：PKX110 / Android 16（API 36）。本地版本保持 **1.0.5 / versionCode 19**。

## 最终界面

- 首页“当前预约”不显示签到按钮或签到 Wi-Fi 提示框，保留预约信息、座位定位及“查看我的预约”入口。
- “我的预约”中，学校报告网络限制且当前预约待签到时，仍显示“签到”按钮。按钮使用灰色底、描边和锁图标，真正禁用点击；无障碍状态说明需要连接图书馆 Wi-Fi 后刷新。
- “签到需连接图书馆 Wi-Fi”使用加粗标题、Wi-Fi 图标及琥珀色提示框，保留 Wi-Fi 设置和刷新入口。大字体及窄屏下，两个入口上下排列。
- `LOC_RESTRICT` 不再生成状态标签，不以其他文案替换“位置受限”。允许签到后的“待签到”等预约状态正常展示。
- 刷新获得学校允许的操作后，恢复原有签到按钮与确认流程。网络限制在确认过程中出现时，关闭原确认框并禁用按钮。
- 禁用签到提示根据已解析的当前预约生成，不写入服务器授权操作列表；使用中、暂离、取消、结束或不明状态的预约不补造待签到操作。

## 自动检查

最终代码执行：

```sh
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease \
  :app:lintDebug :app:lintRelease
```

- Debug / Release 单元测试各 **150 项通过**，无失败或跳过。
- Debug、AndroidTest、Release 构建通过。
- Debug / Release Lint 均为 **0 个 Error**，报告分别含 52 / 53 个 Warning。
- `git diff --check` 通过。

## ADB 设备测试

生成隔离的更新校验 APK fixture 后，对最终 Debug APK 执行完整 AndroidJUnitRunner 测试，启用 `captureWifiCheckInScreenshots=true`：共发现 **186 项**，实际 **161 项通过、25 项跳过、0 项失败**，耗时 130.917 秒。跳过项为需要额外参数的可选真实服务、账号变更或更新安装验收；不将跳过计入通过。

新增及调整的界面覆盖：

- 首页在受限和允许状态下均隐藏签到、Wi-Fi 提示及相应设置/刷新入口，仍能进入预约页面。
- 普通字号与 1.6 倍字号下，待签到预约显示真正禁用的按钮；物理触摸不会打开确认或提交操作。
- 刷新恢复允许状态后，提示框消失；签到经过确认后只调用一次合成提交回调。
- 确认框打开期间新增网络限制会关闭确认，并禁用签到。
- 已使用、暂离、结束、取消或未知预约不会补造签到按钮。
- 现有预约操作、取消确认、座位定位、地图、筛选、收藏、账号、主题、OCR 与更新组件回归通过。

普通字号、大字体、连接恢复及首页两种状态的截图均已保存并目视核对。

## 真实服务与 Release

- 单独运行 `LiveCheckInDiagnosticTest -e live_library true`：**1 项通过**。当前网络下学校真实返回 `LOC_RESTRICT`，`actions=[]`、`requiresLibraryWifi=true`。
- 同签名 Release 包覆盖安装成功。已安装 APK 与最终构建产物 SHA-256 一致，包标志不含 `DEBUGGABLE`。
- Release 冷启动成功，现有账号及预约正常读取。实际首页卡片不显示签到和 Wi-Fi 提示；实际预约页显示禁用的签到、醒目的 Wi-Fi 提示，且没有“位置受限”。物理触摸实际禁用按钮未打开确认框。
- 本轮没有提交真实预约、签到、取消或其他座位变更。馆内允许状态与恢复后的提交流程通过合成数据验证；本轮未切换到馆内 Wi-Fi 提交真实签到。
- 完整设备测试前后的加密账号文件 SHA-256 一致；系统字体比例 `1.0`、旋转设置 `0 / 0` 保持原样。测试 APK 和本轮设备临时截图、UI 转储已清理。

最终 Release APK SHA-256：`caa6b0d4ed27fb808ed772cf7c9ca2ea1d87cfbf828b52956cfc5d4b77beb0ec`。

原安装包备份、构建和测试日志、截图及安装摘要位于忽略的 `.private/wifi-checkin-validation/`。本轮仅本地构建和 ADB 安装，未发布远端版本。
