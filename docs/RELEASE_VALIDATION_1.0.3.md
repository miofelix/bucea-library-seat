# 1.0.3 发布验证

验证日期：2026-10-20。版本 **1.0.3 / versionCode 17**，包名 `cn.edu.bucea.library`，最低 Android 8.0 / API 26。发行说明见 [releases/1.0.3.md](releases/1.0.3.md)。

## 自动检查与设备

- 发布工具 Python 测试 44 项、官网构建测试 14 项通过。
- Debug / Release JVM 测试各 139 项通过；Debug、AndroidTest 与 Release 构建通过；Debug / Release Lint 0 个 Error。
- 1.0.3 调试构建真机 `am instrument`：预约业务 18 项、预约界面 11 项、首页 4 项、地图焦点与恢复布局 3 项，共 36 项一次运行全部通过。
- 真实账号只读地图定位 1 项通过，精确匹配二层内环区 003D；真实签到与续约实测及座位定位行为见 [CHECKIN_VALIDATION_2026-10-20.md](CHECKIN_VALIDATION_2026-10-20.md) 和 [RESERVATION_LOCATION_VALIDATION_2026-10-20.md](RESERVATION_LOCATION_VALIDATION_2026-10-20.md)。本轮发布验收未提交真实预约或座位操作。
- 已用同一签名的 ARM64 Release 覆盖安装 PKX110 / Android 16，保留账号；包版本 1.0.3 / 17、非调试标志和安装后 APK 字节与摘要一致。测试包已卸载。

## 签名产物

`-PreleaseDistributions=true -PreleaseTag=v1.0.3 -PreleaseNotesFile=docs/releases/1.0.3.md :app:stageReleaseApk` 通过；五包均验证 ABI、版本、大小、摘要、关闭调试及既有证书。

证书 SHA-256：`211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `BUCEA-Library-Seat-v1.0.3.apk` | 55,636,162 | `7d2ab9e664dc3cb7c5001f07f466ee2205b95456f56d36731ce8356706120ce5` |
| `BUCEA-Library-Seat-v1.0.3-arm64-v8a.apk` | 33,919,683 | `45f5e96aaaf86f14db22dcb877b9055f6a842c9a141df58fafb3e92ff83cf440` |
| `BUCEA-Library-Seat-v1.0.3-armeabi-v7a.apk` | 33,354,485 | `8d3aace0d8b615acc2a32a502913d88abfef3f07831f3ce114853e9c5229c312` |
| `BUCEA-Library-Seat-v1.0.3-x86.apk` | 35,174,617 | `a03589b560a1527bdf7e0ab54300c169aaa8c3d3e052f4fe1e3ae8c850f72f02` |
| `BUCEA-Library-Seat-v1.0.3-x86_64.apk` | 35,162,534 | `f3c61689bbe7195fa42f79b407a0c70beb256267a8395384fd14411f72b1666f` |

## 发布与分发

功能代码已通过合并提交 `46243d20b44463f8636f660ea4b7bd73009ba729` 合并并推送至 `main`，`v1.0.3` 标签指向同一提交。正式 [GitHub Release v1.0.3](https://github.com/miofelix/bucea-library-seat/releases/tag/v1.0.3)（ID `418408362`）已发布，七个附件齐全，GitHub 返回的五个 APK 大小与 SHA-256 均与本地校验产物一致。

发布工作流 [39427435311](https://github.com/miofelix/bucea-library-seat/actions/runs/39427435311) 的 `publish` 与 `website` job 均成功，已完成 R2 同步、Cloudflare Pages 和 GitHub Pages 部署。

## 公网最终核对

- `updates/latest.json` 与 `releases/latest.json` 均为 **1.0.3 / versionCode 17**，Release ID `418408362`，与最终产物一致。
- 从 `https://download.miofelix.com/bucea-library-seat/releases/v1.0.3/` 完整 GET 五个 APK，共 **193,247,481 字节**，每包字节数与 SHA-256 均匹配上表。
- 五包 HEAD 长度正确；五项 `Range: bytes=0-1023` 返回 206、正确的 `Content-Range` 和 1024 字节，且内容与完整文件首段一致。
- <https://library.miofelix.com/> 与 <https://miofelix.github.io/bucea-library-seat/> 的 `release.json` 均匹配 R2，首页显示 v1.0.3，五个下载地址均指向该版本；两站首页字节一致。
- 完整下载、页面副本与机器可读核对结果保存在忽略的 `.private/checkin-debug/public-v1.0.3-checks/`。本次通过 ADB 安装的 ARM64 Release 与上述成品同摘要；未将这些结果记为 DownloadManager 的线上更新安装事务。
