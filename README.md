# 建大图书馆 Android

这是非官方 Android 客户端。**所有界面和交互均由 Kotlin + Jetpack Compose 在本机原生实现**，包括选座、座位地图、预约、记录、账号与验证流程。应用不使用 WebView、不加载或渲染原版网页，也不执行网页 JavaScript。学校服务只提供业务数据和校验结果。

服务地址为 `http://10.1.20.7`，最低 Android 8.0，手机必须连接可以访问该地址的校园内网。学校的可预约规则、状态及地点限制仍以服务端返回为准。仅为该主机放行 HTTP；账号密码和 Cookie 使用本机 Keystore 加密保存，禁止备份。真实账号、会话、页面原文和调试截图不进入 Git。

## 原生功能

- 按学校开放日期、场馆、区域、时长、开始/结束时间、电源和靠窗筛选座位，结果支持分页。数字前缀和完整编号均可查询，例如 `001`、`001D`。
- 原生座位地图保留真实空间位置与通道，采用新的触屏交互：拖动、缩放、区域切换、点选座位及无障碍列表；不复制网页样式或座位图片。
- 选择服务器返回的可用时段，核对账号、日期和时间后确认预约。提交前重新校验时段与表单令牌，不自动重复提交；结果不明时先核实预约记录。
- 原生预约与违约记录、详情、加载更多；服务器提供可取消操作时显示取消确认。签到、暂离、返回、续约及结束使用按学校允许的当前状态提供。
- 本机座位收藏按账号隔离，同时显示学校的常用座位记录；选择收藏后再次查询可用时段。
- 多账号加密保存，切换后恢复独立会话或自动登录；可选密码错误后尝试下一个账号，网络错误不轮换账号。
- 原生修改图书馆密码、退出登录。密码明确修改成功后更新本机保存值；结果不明时阻止重复修改。主动退出保持未登录，直到再次主动登录。
- **全部验证码统一由本机 ddddocr 自动完成**。登录图片使用识别模型；额外点选验证码使用检测模型定位文字、识别目标及候选文字，再自动提交匹配坐标。没有手动输入或点选验证码的流程，也不调用外部 OCR 服务。识别失败仅有限次数刷新重试，不绕过学校验证。

底部“选座”查询座位，“我的”查看预约、收藏及违约记录；“布局选座”打开原生地图，“账号”提供登录信息管理、修改密码和退出。跨天或刷新时更新开放日期，保留有效筛选；查询和翻页后滚动到新结果。

按编号查询只使用日期、场馆和区域条件，点选后确认实时可用时段。收藏保存位置，不保证可用性。

## 构建与安装

需要 JDK 17、Android SDK 35，将 SDK 路径写入未提交的 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cn.edu.bucea.library/.MainActivity
```

首次在“账号”页添加图书馆账号，应用立即尝试自动登录。源码和 APK 不预置真实账号密码。

## 测试

```sh
./gradlew :app:assembleDebugAndroidTest :app:lintDebug
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e notClass cn.edu.bucea.library.LiveLibraryReadOnlyTest,cn.edu.bucea.library.LiveNativeVerificationInstrumentedTest,cn.edu.bucea.library.LiveLibraryFiltersTest,cn.edu.bucea.library.LiveLibraryInventoryTest,cn.edu.bucea.library.LivePasswordRoundTripTest,cn.edu.bucea.library.LiveNativeMapGestureTest,cn.edu.bucea.library.LiveCurrentDayReadOnlyTest cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
python3 tools/adb_library_smoke.py --serial YOUR_DEVICE_SERIAL --artifacts .private/native-qa
```

显式运行真实服务只读业务检查及自动验证码检查，需要已保存并登录的账号：

```sh
adb shell am instrument -w -r -e class cn.edu.bucea.library.LiveLibraryReadOnlyTest -e live_library true cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class cn.edu.bucea.library.LiveLibraryFiltersTest -e live_library true cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class cn.edu.bucea.library.LiveNativeVerificationInstrumentedTest -e live_library true cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
```

这些检查查询座位、时段、记录及地图，准备预约表单并取得服务器验证码凭证，不提交真实预约或修改密码。合成测试验证修改密码、取消与座位操作的参数、账号绑定及重复提交保护。截图仅写入忽略目录。

扩展原生界面检查与单独地图检查：

```sh
python3 tools/adb_library_smoke.py --full --serial YOUR_DEVICE_SERIAL --artifacts .private/full-native-qa
python3 tools/adb_library_smoke.py --map-only --serial YOUR_DEVICE_SERIAL --artifacts .private/map-native-qa
```

脚本检查筛选选项、学校返回的楼层和地图、列表切换及密码表单空值禁用；只核对预约预览，不点击提交。学校没有开放区域的楼层会记录为跳过。需要已解锁的手机、已登录账号和校园网，运行时避免其他 UI 自动化同时操作设备。

真实服务已完成两次未来预约和一次当天“现在开始”预约，并全部取消，核实没有遗留活动测试预约。密码修改界面已实现，但本次两种临时密码提交均遇到学校错误页，不能视为修改功能通过实际验收；原密码已重新认证，保存值已恢复。按用户要求，本次跳过必须连接图书馆内部 Wi-Fi 的签到及依赖签到的后续操作。逐项结果与限制见 [实际测试结果](docs/ACTUAL_TEST_RESULTS.md)，真实操作流程见 [实际测试计划](docs/ACTUAL_TEST_PLAN.md)。

所有工作按英文 Conventional Commits 分阶段提交。完整验证记录见 [docs/VALIDATION.md](docs/VALIDATION.md)，原生功能与服务接口对应见 [docs/LIBRARY_FUNCTIONS.md](docs/LIBRARY_FUNCTIONS.md)。

## OCR 模型

模型从本机 `/Users/felix/Projects/ddddocr` 的 1.6.1 版本导出：`common_old.onnx`、8210 项字符表及 `common_det.onnx`。Kotlin 使用 ONNX Runtime 在设备上推理，并复现灰度、Lanczos、BGR 输入、YOLOX 解码和匹配流程。

```sh
python tools/export_ocr_assets.py --source /path/to/ddddocr
python tools/check_ocr.py --source /path/to/ddddocr --sample /path/to/captcha.png --verify-kotlin
```

许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。当前 APK 为开发用 debug 构建，正式分发需要自己的签名。

测试版 APK：[v0.3.1-beta.1](https://github.com/miofelix/bucea-library-seat/releases/tag/v0.3.1-beta.1)，为通过 adb 验收的 Debug 签名测试包。0.3.1 修复当天“现在开始”预约；实际预约、取消及清理结果见 [实际测试记录](docs/ACTUAL_TEST_RESULTS.md)。
