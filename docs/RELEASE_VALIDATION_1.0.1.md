# 1.0.1 发布验证

验证日期：2026-10-16。版本为 **1.0.1 / versionCode 15**，包名 `cn.edu.bucea.library`，最低支持 Android 8.0 / API 26。本版包含 Cloudflare R2 更新地址，以及 App 设置和官网的 MIT 开源许可入口。发行说明见 [releases/1.0.1.md](releases/1.0.1.md)。

代码提交：[`4a2fcb59cc446927a73c57de1ab661eadc65c175`](https://github.com/miofelix/bucea-library-seat/commit/4a2fcb59cc446927a73c57de1ab661eadc65c175)（`feat(release): prepare v1.0.1 with offline license notices`），已推送。

## 构建与自动检查

包含最终许可入口的代码已执行以下检查：

```sh
python3 -m unittest discover -s tools/tests -v
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint :app:lintRelease
./gradlew -PreleaseDistributions=true -PreleaseTag=v1.0.1 -PreleaseNotesFile=docs/releases/1.0.1.md :app:stageReleaseApk
```

| 检查                      | 结果                                                                                                                               |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| JVM 单元测试              | 77 项通过，0 失败、0 跳过。                                                                                                        |
| 独立设备回归              | 144 项通过，0 Failure、0 Error、0 Skip；排除全部 15 个 opt-in Live 类，`connectedDebugAndroidTest` 成功，耗时 3 分 27 秒。         |
| Debug 与 Android 测试 APK | `assembleDebug` 与 `assembleDebugAndroidTest` 成功；与 JVM、lint 检查合计 34 秒，`BUILD SUCCESSFUL`。                              |
| lint                      | Debug 50 项、Release 51 项，均为 Warning；0 Error、0 Fatal。                                                                       |
| Python 发布工具与官网脚本 | 分别 44 项、14 项通过。                                                                                                            |
| 工作流与 JavaScript       | actionlint、`node --check` 通过。                                                                                                  |
| 格式与差异检查            | Prettier、`git diff --check` 通过。                                                                                                |
| 许可静态复核              | 无发布阻碍；13 份 App 许可文档及站点本地链接存在，对应打包资源与源文件字节一致。                                                   |
| 正式暂存与签名、ABI 校验  | `stageReleaseApk` 成功，耗时 28 秒；五包通过包名、版本、最低 SDK、非调试、原签名及 ABI 校验，ARM64 另经 `apksigner` 确认证书一致。 |
| APK 许可资源与更新地址    | 五包 assets 中的 MIT、全部第三方原文和 ddddocr 许可均逐字节匹配源文件；DEX 包含固定 R2 清单与下载前缀，不含旧域名。                |
| 成品代码追溯              | 五包 `META-INF/version-control-info.textproto` 均包含代码提交 `4a2fcb5`。                                                          |

## 网页验证

使用 ego 浏览器在 1920px 桌面宽度和 390px 手机宽度检查开源许可页，内容显示正常、无横向溢出；MIT 内容展开、返回官网和官网页脚许可入口均正常。

在示意 UI 上滚轮操作后，网页 `scrollY` 从 `0` 增至 `358.5`，各示意界面的内部 `scrollTop` 全部保持不变，符合页面静态展示与网页继续滚动的要求。

## 签名与更新端点

沿用的签名证书 SHA-256 为：

```text
211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801
```

固定更新清单与下载前缀为：

```text
https://download.miofelix.com/bucea-library-seat/updates/latest.json
https://download.miofelix.com/bucea-library-seat/releases/
```

## 本地成品

最终产物位于 `app/build/outputs/release/`，包含通用包、四种独立 ABI 包、`SHA256SUMS` 和 `update.json`。以下字节数和 SHA-256 直接读取最终 `update.json`，正式发布使用了这批成品。

| APK                                         |     字节数 | SHA-256                                                            |
| ------------------------------------------- | ---------: | ------------------------------------------------------------------ |
| `BUCEA-Library-Seat-v1.0.1.apk`             | 55,616,642 | `758fc63921ec842d2cb2e597b529596a32fc892378ca0d93d7a06da762ba409e` |
| `BUCEA-Library-Seat-v1.0.1-arm64-v8a.apk`   | 33,900,163 | `b9531b8f22aea447b01ba0da45f17e618d72fa611decc0c7d7fcccc4b0f5485b` |
| `BUCEA-Library-Seat-v1.0.1-armeabi-v7a.apk` | 33,334,965 | `b7e25e5f969eca60253aa81dcfcfe89573ceae675c3432086fbdf30a4d96ffdb` |
| `BUCEA-Library-Seat-v1.0.1-x86.apk`         | 35,155,097 | `722148bb8b12447b76a9aea3f36b68e203d0f22e3025a0957fc32a6896178111` |
| `BUCEA-Library-Seat-v1.0.1-x86_64.apk`      | 35,143,014 | `9ab253f7047ac13e0ebf6db221b2c1f00cbe05eb479ed1d69f066aabc3354e25` |

## 设备验证

验证设备为 PKX110，Android API 36，`arm64` 架构。144 项独立回归通过，未执行被排除的 15 个 opt-in Live 类。

本次 `connectedDebugAndroidTest` 在测试结束时由 AGP 自动卸载目标 App，随后通过包查询确认目标包已不存在。测试前未预先保留应用数据；正式包恢复后，账号页显示“添加第一个账号”，确认本地保存的账号已清除，需要重新添加并登录，原设置也可能已清除。本轮不是数据保留或覆盖升级成功，恢复安装未恢复原有账号数据。

最终 ARM64 正式包已重新安装成功并正常启动，设置页显示 `1.0.1`，开源许可列表显示正常。通过 `pm path` 定位并读回已安装的 base APK，其 SHA-256 为 `b9531b8f22aea447b01ba0da45f17e618d72fa611decc0c7d7fcccc4b0f5485b`，与上述本地 ARM64 成品一致。

实际开启飞行模式并关闭 Wi-Fi 后，重新打开 MIT 原文、滚动至免责声明，以及打开和滚动约 320KB 的 ONNX 第三方原文均成功；返回许可列表及系统返回键关闭均正常。测试结束已恢复原网络设置。

最终 ARM64 包于 `2026/10/16 03:31` 手动检查更新，界面由“正在检查更新…”变为“已是最新版本”，“最近尝试”和“最近成功”均显示 `03:31`。当时 R2 公开清单仍为 `1.0.0 / versionCode 14`，低于已安装的 code 15，结果符合预期，证明实际 Release `UpdateHttpClient` 成功读取并解析 R2 清单。该检查不代表更新器已完成 APK 下载、校验或安装。

发布后于 `03:34` 再次手动检查，界面显示“已是最新版本”，“最近尝试”和“最近成功”均为 `03:34`；此时 R2 清单为 `1.0.1 / versionCode 15`，与已安装版本一致。

有日常数据的设备后续使用显式 `adb install -r` 安装已构建 APK，再调用 `am instrument` 运行测试，避免该 Gradle 流程的自动卸载清理。

## GitHub、R2 与官网

源码推送触发的 [Pages 工作流 `38793920115`](https://github.com/miofelix/bucea-library-seat/actions/runs/38793920115) 成功。正式 [开源许可页](https://library.miofelix.com/licenses.html) 已可访问，ego 浏览器在 390px 手机宽度下确认无横向溢出、MIT 展开正常；13 个本地许可证与声明链接全部返回 HTTP `200`，包括 326,829 字符的 ONNX 第三方原文，根 MIT 为 1,068 字符。

[GitHub Release `v1.0.1`](https://github.com/miofelix/bucea-library-seat/releases/tag/v1.0.1) 已按正式稳定版发布，非草稿、非预发布。发布脚本逐项验证五个 APK、`SHA256SUMS` 和 `update.json` 七个附件后完成发布。

[发布工作流 `38794715008`](https://github.com/miofelix/bucea-library-seat/actions/runs/38794715008) 全部成功，`publish` 耗时 1 分 1 秒、`website` 耗时 27 秒。公开更新清单和网站下载元数据均为 `1.0.1 / versionCode 15`。

ego 浏览器确认正式官网显示“v1.0.1 现已发布”，GitHub 发行与备用链接均指向 `v1.0.1`；五个 APK 下载地址均为 `download.miofelix.com/bucea-library-seat/releases/v1.0.1/` 下对应包。ARM64 约 33.9MB、通用包约 55.6MB，开源许可入口存在，手机布局无横向溢出。

五个 APK 均已从公开下载域名完整 GET 成功，共 `193149881` 字节；各包大小和 SHA-256 全部与上述本地成品及 GitHub digest 一致，HEAD 返回 `200`、Range 返回 `206`。x86 首次完整 GET 曾遇到 Cloudflare `522`，仅重试一次即成功下载完整的 `35,155,097` 字节，SHA-256 为 `722148bb8b12447b76a9aea3f36b68e203d0f22e3025a0957fc32a6896178111`。本次观察到的瞬时错误保留在此。

## 验证边界

本版完成正式包重新安装、离线许可阅读、真实 R2 更新清单检查与五包公开完整下载；未通过应用更新器执行本版 APK 的真实下载、校验和系统安装事务。Android API 26 设备验证未执行。1.0.0 及首次 R2 上线记录保留为历史证据。
