# 1.0.1 发布验证

验证日期：2026-10-16。版本为 **1.0.1 / versionCode 15**，包名 `cn.edu.bucea.library`，最低支持 Android 8.0 / API 26。本版包含 Cloudflare R2 更新地址，以及 App 设置和官网的 MIT 开源许可入口。发行说明见 [releases/1.0.1.md](releases/1.0.1.md)。

## 构建与自动检查

包含最终许可入口的代码已执行以下检查：

```sh
python3 -m unittest discover -s tools/tests -v
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint :app:lintRelease
./gradlew -PreleaseDistributions=true -PreleaseTag=v1.0.1 -PreleaseNotesFile=docs/releases/1.0.1.md :app:stageReleaseApk
```

| 检查 | 结果 |
| --- | --- |
| JVM 单元测试 | 77 项通过，0 失败、0 跳过。 |
| Debug 与 Android 测试 APK | `assembleDebug` 与 `assembleDebugAndroidTest` 成功；与 JVM、lint 检查合计 34 秒，`BUILD SUCCESSFUL`。 |
| lint | Debug 50 项、Release 51 项，均为 Warning；0 Error、0 Fatal。 |
| Python 发布工具与官网脚本 | 分别 44 项、14 项通过。 |
| 工作流与 JavaScript | actionlint、`node --check` 通过。 |
| 格式与差异检查 | Prettier、`git diff --check` 通过。 |
| 许可静态复核 | 无发布阻碍；13 份 App 许可文档及站点本地链接存在，对应打包资源与源文件字节一致。 |
| 正式暂存与签名、ABI 校验 | 最终成品结果记录于完成后。 |
| APK 许可资源与更新地址 | 核对五个最终 APK 的许可证、第三方资源及 DEX 中的固定 R2 端点。 |

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

最终产物位于 `app/build/outputs/release/`，包含通用包、四种独立 ABI 包、`SHA256SUMS` 和 `update.json`。最终 APK 的字节数和 SHA-256 在完成暂存验证后记录，发布仅使用这批成品。

## 设备验证

验证设备为 PKX110，Android API 36，`arm64` 架构。正式成品需确认覆盖安装、正常启动、设置页版本、手动更新检查及开源许可入口。验证结果按最终 APK 实际执行记录。

## GitHub、R2 与官网

GitHub Release、Actions 发布、R2 五包完整下载与 SHA-256、清单版本以及官网版本和下载链接的结果在正式发布后记录。本页尚未记录 1.0.1 的公网发布成功。

## 验证边界

ADB 覆盖安装与清单检查分别验证本地成品和更新检查端点，不能代替应用更新器的真实下载、校验和系统安装事务。Android API 26 设备验证未执行。各项结果按本版实际执行记录，1.0.0 及首次 R2 上线记录保留为历史证据。
