# 建大图书馆 Android

使用 **Kotlin + Jetpack Compose** 开发的第三方 Android 客户端，服务地址为 `http://10.1.20.7`。手机必须连接可以访问该地址的校园内网。最低 Android 8.0。

## 已实现

- 原生选座页：查询学校开放的日期、场馆、阅览区域、使用时长、开始/结束时间、电源、靠窗及座位号；结果支持分页。
- 原生预约流程：选择座位后获取学校实时可用时段，核对账号、日期和时间，再确认提交。提交前重新检查时段并获取新的表单令牌，不自动重复预约；结果不明确时提示先核实“我的预约”。
- “我的”页直接显示预约记录、当前预约状态、常用座位及违约记录。签到、暂离、返回、结束使用和续约只在学校允许的状态与网络条件下提供，操作前确认。
- 在查询结果中点击星标保存本机收藏，按账号隔离；常用座位同时显示学校网站已有的使用记录。收藏仅保存位置，选中后重新查询可用时段。
- 原生首页显示网站实际返回的可用、已使用、未签到座位数。
- 添加、修改、删除多个账号；切换账号后自动恢复该账号的独立会话或重新登录。
- Android Keystore AES-GCM 加密保存账号密码和每个账号的 Cookie，应用备份关闭。
- App 内运行 ddddocr 默认 ONNX 模型，不依赖电脑、Python 服务或远程 OCR 服务。
- 验证码识别最多尝试 3 次，失败时显示新验证码供手动输入。账号密码错误直接停止；开启“登录失败自动切换”后，按保存的账号逐个尝试。网络错误不会触发账号轮换。
- “布局选座”和“完整网页功能”保留整个原站：自选座位、布局选座、常用座位、我的预约、违约记录、修改密码，以及原站提供的签到、续约、暂离、返回和结束使用等操作。
- 功能中心注入手机样式，保留原始表单、令牌、路由和 JavaScript 事件；菜单、筛选、列表和弹窗适配手机，座位地图保留原坐标并支持滚动与缩放。右上角可以切回原网页布局。
- 打开 App 验证保存的会话；会话失效时自动重新识别验证码登录。功能中心发现登录页时自动恢复一次，避免无限登录循环。

“自动切换”指手动选中账号后自动登录，以及可选的密码错误后切换下一个账号。不会定时轮换、自动预约或在多个账号之间执行操作。自动恢复登录可在设置中关闭；有效的现有会话仍可使用。

## 构建与安装

需要 JDK 17、Android SDK 35。将本机 SDK 路径写入未提交的 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cn.edu.bucea.library/.MainActivity
```

首次打开后，在“账号”页添加图书馆账号，App 会立即尝试登录。源码和安装包没有预置任何真实账号密码。在原网站修改密码后，也需在“账号”页修改保存的密码。

底部“选座”打开原生查询与预约；“我的”查看记录、收藏和当前座位操作。房间座位地图、修改密码及原站其他功能可以通过对应完整页面进入。学校要求额外验证时，App 会提供原页面入口。

座位号可输入数字前缀（如 `001`）或完整编号（如 `001D`）。按编号查询时，以日期、场馆和阅览区域为条件，不同时应用时长、电源和靠窗筛选；点击结果后再查询实时可用时段。

“选座”右上角可刷新开放日期与筛选项，仍有效的选择会保留。跨天重新进入页面或恢复 App 时会更新日期。查询与翻页完成后自动滚动到结果标题。

```sh
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
./gradlew :app:lintDebug
```

仅运行独立回归测试，或显式运行真实校园服务只读检查：

```sh
adb shell am instrument -w -e notClass cn.edu.bucea.library.LiveLibraryReadOnlyTest cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class cn.edu.bucea.library.LiveLibraryReadOnlyTest -e live_library true cn.edu.bucea.library.test/androidx.test.runner.AndroidJUnitRunner
```

真实服务检查需要 App 中已有已登录账号；读取座位、时段、记录并准备预约表单，**不会提交预约或执行签到等操作**。默认测试会跳过该检查。

## 验证与阶段

开发按英文 Conventional Commits 分阶段提交：Android 骨架、离线 OCR、账号与登录、手机功能中心、设备验证与文档。

真实内网站点的登录协议为 `GET /login` → `GET /auth/createCaptcha` → `POST /auth/signIn`，每次验证码请求和表单提交使用同一账号的 CookieJar。登录成功必须由主页的 `sysUsername` 和退出入口共同确认，不把 HTTP 200 当作成功。

验证码模型由本机 `/Users/felix/Projects/ddddocr` 导出，默认模型 `common_old.onnx`，完整字符表为 8210 项。Kotlin 灰度、Lanczos 缩放、归一化与 CTC 解码对照原 Python 实现验证。

```sh
python tools/export_ocr_assets.py --source /path/to/ddddocr
python tools/check_ocr.py --source /path/to/ddddocr --sample /path/to/captcha.png --verify-kotlin
```

仪器化测试在独立的测试存储命名空间中使用虚构账号，验证加密、Cookie 隔离、会话恢复、账号更新/删除、失效 Jar 和手机实际 ONNX 推理。测试不修改真实账号数据，也不提交预约。

六个功能页面已检查适配前后的表单、输入控件和链接数量一致，并检查 412px 视口布局。交易操作沿用学校原站实现；预约、签到、续约等真实写操作需要在实际使用时验证，开发测试不占用座位。

0.2.1 的构建、Lint 和 25 项 adb 真机回归测试通过。手机解锁后已完成原生查询、可用时段选择、预约预览、收藏添加/进入/移除、分页和三个记录页的点击验收，并检查横屏弹窗。真实服务只读检查也已通过，覆盖编号选座、预约表单准备和记录读取；未提交真实预约。详细记录见 [docs/VALIDATION.md](docs/VALIDATION.md)，功能对应关系见 [docs/LIBRARY_FUNCTIONS.md](docs/LIBRARY_FUNCTIONS.md)。

可重复运行原生界面验收脚本，手机需解锁、账号已登录并连接校园网：

```sh
python3 tools/adb_library_smoke.py --serial YOUR_DEVICE_SERIAL --artifacts .private/native-qa
```

脚本核对预约预览后取消，禁止点击最终预约或座位操作按钮；截图保存在忽略目录。

## 实现边界

这是非官方客户端。原生层接入学校现有座位查询、时段、预约与记录接口；地图和其他完整业务页面继续使用原站。学校的可预约规则、当前状态、地点限制和验证要求仍以服务端返回为准。服务地址使用学校提供的 HTTP，网络配置仅为 `10.1.20.7` 放行 HTTP，其他主机禁止加载。账号、Cookie、页面原文、截图及本机测试资料位于忽略目录或设备加密存储，不进入 Git。

模型来源与许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。当前提供开发用 debug APK；正式发布仍需配置自己的签名。
