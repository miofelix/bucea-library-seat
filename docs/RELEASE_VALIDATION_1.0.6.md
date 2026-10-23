# 1.0.6 发布验证

记录日期：2026-10-23。版本 **1.0.6 / versionCode 20**，包名 `cn.edu.bucea.library`，最低 Android 8.0 / API 26。发行说明见 [releases/1.0.6.md](releases/1.0.6.md)。

本次发布改进待签到预约的禁用按钮、图书馆 Wi-Fi 提示和首页预约卡片。交互与完整设备测试见 [CHECKIN_WIFI_UI_VALIDATION_2026-10-23.md](CHECKIN_WIFI_UI_VALIDATION_2026-10-23.md)。

## 发布前设备备份

已备份用户手机在发布开始时安装的本地 Release：**1.0.5 / versionCode 19**。备份 APK SHA-256 为 `caa6b0d4ed27fb808ed772cf7c9ca2ea1d87cfbf828b52956cfc5d4b77beb0ec`。按用户要求，发布及所有渠道核对完成后重新覆盖安装这份备份，保留账号数据。

## 本地构建与验证

- 发布工具测试 44 项、官网构建测试 14 项通过。
- 最终版本 Debug / Release JVM 测试各 150 项通过；Debug / Release Lint 无 Error。
- 使用 `-PreleaseDistributions=true -PreleaseTag=v1.0.6 -PreleaseNotesFile=docs/releases/1.0.6.md :app:stageReleaseApk` 构建并暂存通用包与四种架构包。全部 APK 的包名、版本、最低 SDK、关闭调试和签名由暂存工具校验。
- 签名证书 SHA-256：`211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`。
- 签到界面完整设备测试已在本次相同界面代码的 1.0.5 本地构建上完成：161 项通过、25 项可选用例跳过、0 项失败，另有真实服务只读 1 项通过。随后仅提升发布版本号；手机按用户要求保留在发布前版本，不安装 versionCode 20。

## 签名产物

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `BUCEA-Library-Seat-v1.0.6.apk` | 55640622 | `38b1f4bd9302d0effc3f8450cec2a5652a09ca2d60b45456ec761db7ea312a7c` |
| `BUCEA-Library-Seat-v1.0.6-arm64-v8a.apk` | 33924143 | `a9e507b6593c678b056bcb1e701bbd6b72508ad52108641a66595ea2dde0f880` |
| `BUCEA-Library-Seat-v1.0.6-armeabi-v7a.apk` | 33358949 | `4ab7d190a1266940f8fb8079cfce65b6858c64b385b1d49034816136d0dd481d` |
| `BUCEA-Library-Seat-v1.0.6-x86.apk` | 35179081 | `e29312e304e83818afed7506f77ddd52689ec5123f2b9b2013204a844a3770ab` |
| `BUCEA-Library-Seat-v1.0.6-x86_64.apk` | 35166994 | `47cbd06f00cdc3318a9bde01f9eb5cdb0ede9448ba1c7d50c06fc0405b9d5c1f` |

发布、公开下载校验和最终设备恢复结果将在实际完成后补充。
