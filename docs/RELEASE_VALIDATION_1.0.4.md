# 1.0.4 发布验证

记录日期：2026-10-21。计划版本 **1.0.4 / versionCode 18**，包名 `cn.edu.bucea.library`，最低 Android 8.0 / API 26。发行说明见 [releases/1.0.4.md](releases/1.0.4.md)。

本次本地构建与设备验证已完成；GitHub Release、R2、官网和公网完整下载核对将在发布后补录。

## 自动检查与设备

- 发布工具测试 44 项、官网构建测试 14 项通过。
- Debug / Release JVM 测试各 147 项通过；Debug、AndroidTest 与 Release 构建通过；Debug / Release Lint 均为 0 个 Error。
- 设备定向回归 61 项通过，覆盖暂离预约信息、座位定位、未签到取消确认、更新任务状态和 DownloadManager / APK 校验。
- 同签名 ARM64 Release 包已覆盖安装设备；安装后版本为 1.0.4 / versionCode 18，安装 APK 字节摘要与正式产物一致，非调试状态由暂存工具验证。

## 签名产物

`-PreleaseDistributions=true -PreleaseTag=v1.0.4 -PreleaseNotesFile=docs/releases/1.0.4.md :app:stageReleaseApk` 通过。签名证书 SHA-256 为 `211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `BUCEA-Library-Seat-v1.0.4.apk` | 55,638,966 | `90e7869a2523c789bf58b43d2dd4b2329c8ed764228e5403be369bafe433af90` |
| `BUCEA-Library-Seat-v1.0.4-arm64-v8a.apk` | 33,922,487 | `70232cc73f7aa7460539b8c6d8e0577c2277e684b5b29a76de40c2931ade220b` |
| `BUCEA-Library-Seat-v1.0.4-armeabi-v7a.apk` | 33,357,293 | `f7335c01b64d3021aaae34ade8669cde405ef27a6df1f92562a2017a3d01216a` |
| `BUCEA-Library-Seat-v1.0.4-x86.apk` | 35,177,425 | `7fa6869bbf5b697357234207445d6b1e63224f8338df5dea3812103368129cce` |
| `BUCEA-Library-Seat-v1.0.4-x86_64.apk` | 35,165,338 | `aff04efbf3d9b89b198acfd6b5ce13fd1a1dc45a802211b089770ba979227ca5` |

## 发布与分发

待记录发布提交、`v1.0.4` 标签、GitHub Release 及七个附件的远端核对结果；待记录 R2 同步、Cloudflare Pages 与 GitHub Pages 部署工作流结果。

## 公网最终核对

- Android 更新清单与官网下载元数据的版本、Release 标识及内容：发布后待验证。
- 五个 APK 的公网完整下载、字节数与 SHA-256：待验证。
- APK 的 HEAD 与 Range：待验证。
- 官网与 GitHub Pages 的版本、五个下载链接及元数据：待验证。
- 来自 R2 的真实 Android 更新下载与安装：尚未在本轮验证；后续应按实际执行范围记录，不以 ADB 安装或公网完整下载代替。
