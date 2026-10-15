# Android 发布

所有构建、签名与发布在保存原签名密钥的 MacBook 上执行。树莓派只缓存成品。版本在 `app/build.gradle.kts` 中维护，本次准备版本为 **0.3.8 / versionCode 13**，每次后续更新递增 `versionCode`。本版构建、设备验收与发布状态以 [ADB_ACCEPTANCE_0.3.8.md](ADB_ACCEPTANCE_0.3.8.md) 为准。

## 构建

使用 JDK 17、Android SDK 和项目 Gradle Wrapper：

```sh
python3 -m unittest discover -s tools/tests -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint :app:lintRelease :app:stageBetaApk
```

测试版产物位于 `app/build/outputs/beta-release/`：

- `BUCEA-Library-Seat-v0.3.8-beta.1.apk`
- `SHA256SUMS`
- `update.json`

正式版使用 `:app:stageReleaseApk`，输出目录为 `app/build/outputs/release/`。可用 `-PreleaseTag=v0.3.8-beta.2` 和 `-PreleaseNotesFile=docs/releases/0.3.8.md` 指定标签和说明。同一版本的补发也需要先增加 `versionCode`，已发布的资产禁止覆盖替换。

两个任务均使用 `assembleRelease`，关闭调试、压缩资源，并保留本机已有 Android Debug 证书，以兼容先前分发的 APK。**不要删除或重新生成 `~/.android/debug.keystore`，不要把密钥复制到树莓派或 Git。** 证书迁移不包含在本次功能中。

用 SDK `apksigner verify --print-certs` 对比旧包和待发布包的证书 SHA-256。当前沿用的证书摘要是 `211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`，打包任务会拒绝其他证书，防止新机器自动生成的 Debug 密钥破坏覆盖安装兼容。发布 APK 的具体摘要每次以 `SHA256SUMS` 为准。

## 上传

先审查并推送将要发布的提交，再使用已经验证的三个附件。`tools/publish_release.py` 在草稿中上传全部附件，完成后才发布；它拒绝已有标签，并在任何远端写入前重新验证大小和摘要：

```sh
python3 tools/publish_release.py --directory app/build/outputs/beta-release --tag v0.3.8-beta.1 --target <已审查并推送的40位提交SHA> --notes docs/releases/0.3.8.md --prerelease
```

正式版移除 `--prerelease` 并使用正式产物/标签。发布脚本使用 `gh` 的当前授权。如果上传失败，草稿会保留，检查附件后人工完成或删除草稿重试。不要用 `--clobber` 替换已发布资产。

Release 标题使用 `北建大图书馆选座 v0.3.8 测试版`，下载文件名保持包含应用名称和版本号的 ASCII 名称。本次 Android 16 / API 36 验收见 [ADB_ACCEPTANCE_0.3.8.md](ADB_ACCEPTANCE_0.3.8.md)，版本递增前的分阶段界面验收见 [ADB_ACCEPTANCE_UI_2026-10-15.md](ADB_ACCEPTANCE_UI_2026-10-15.md)。先前客户端自动重检的修复验收见 [CAMPUS_ACCESS_DIAGNOSIS.md](CAMPUS_ACCESS_DIAGNOSIS.md)，先前公开更新结果见 [ADB_ACCEPTANCE_0.3.5.md](ADB_ACCEPTANCE_0.3.5.md)。Android 8 / API 26 设备验证仍未完成，依赖图书馆内部 Wi-Fi 的实际签到与后续使用操作未执行。不能将模拟任务或安装器测试记作公开网络更新成功。

## 启用

树莓派已使用当前用户的 systemd 服务运行镜像同步器和 HTTP 服务，监听 `0.0.0.0:9080`，公开域名可达。每次发布后等待定时同步或运行 `systemctl --user start bucea-update-mirror.service`，再验证公开清单及 APK 的版本、大小与摘要。公开 HEAD、ETag 和 Range 的抽样检查不计为完整公网下载；安装验证采用的实际路径在对应 ADB 验收中单独记录。已有 0.3.3 用户需手动安装含更新模块的版本，0.3.4 起可在应用内更新。

先前 0.3.6 的公开更新与镜像证据保留在 [ADB_ACCEPTANCE_0.3.6.md](ADB_ACCEPTANCE_0.3.6.md)。公网 Cloudflare 缓存生成的 416 保留 APK 的 immutable 缓存头，origin 的 416 为 no-store；这是已有的转发层差异，本次未修改用户维护的 Cloudflare 配置。

详细协议见 [UPDATES.md](UPDATES.md)，交接见 [RASPBERRY_PI_HANDOFF.md](RASPBERRY_PI_HANDOFF.md)，测试与待验项目见 [UPDATE_VALIDATION.md](UPDATE_VALIDATION.md)。

先前 `v0.3.7-beta.1 / code 12` 的三个附件已发布并同步。该版最终安装字节、公开清单和 Range/ETag 检查，以及手机“已是最新版本”结果见 [ADB_ACCEPTANCE_0.3.7.md](ADB_ACCEPTANCE_0.3.7.md)；该版使用 ADB 覆盖安装验收，没有声称新的完整公网安装器升级事务。上述历史结果不代表本次 0.3.8 的验证结果。
