# Android 发布

所有构建、签名与发布在保存原签名密钥的 MacBook 上执行。树莓派只缓存成品。版本在 `app/build.gradle.kts` 中维护，当前开发版本为 **0.3.4 / versionCode 8**，每次后续更新递增 `versionCode`。

## 构建

使用 JDK 17、Android SDK 和项目 Gradle Wrapper：

```sh
python3 -m unittest discover -s tools/tests -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint :app:lintRelease :app:stageBetaApk
```

测试版产物位于 `app/build/outputs/beta-release/`：

- `BUCEA-Library-Seat-v0.3.4-beta.1.apk`
- `SHA256SUMS`
- `update.json`

正式版使用 `:app:stageReleaseApk`，输出目录为 `app/build/outputs/release/`。可用 `-PreleaseTag=v0.3.4-beta.2` 和 `-PreleaseNotesFile=docs/releases/0.3.4.md` 指定标签和说明。同一版本的补发也需要先增加 `versionCode`，已发布的资产禁止覆盖替换。

两个任务均使用 `assembleRelease`，关闭调试、压缩资源，并保留本机已有 Android Debug 证书，以兼容先前分发的 APK。**不要删除或重新生成 `~/.android/debug.keystore`，不要把密钥复制到树莓派或 Git。** 证书迁移不包含在本次功能中。

用 SDK `apksigner verify --print-certs` 对比旧包和待发布包的证书 SHA-256。当前沿用的证书摘要是 `211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`，打包任务会拒绝其他证书，防止新机器自动生成的 Debug 密钥破坏覆盖安装兼容。发布 APK 的具体摘要每次以 `SHA256SUMS` 为准。

## 上传

先审查并推送将要发布的提交，再使用已经验证的三个附件。`tools/publish_release.py` 在草稿中上传全部附件，完成后才发布；它拒绝已有标签，并在任何远端写入前重新验证大小和摘要：

```sh
python3 tools/publish_release.py --directory app/build/outputs/beta-release --tag v0.3.4-beta.1 --target <已审查并推送的40位提交SHA> --notes docs/releases/0.3.4.md --prerelease
```

正式版移除 `--prerelease` 并使用正式产物/标签。发布脚本使用 `gh` 的当前授权；本轮实现没有执行上传或发布。如果上传失败，草稿会保留，检查附件后人工完成或删除草稿重试。不要用 `--clobber` 替换已发布资产。

Release 标题使用 `北建大图书馆选座 v0.3.4 测试版`，下载文件名保持包含应用名称和版本号的 ASCII 名称。说明如实记录验证结果：目前无法连接 ADB，Android 8 / 16 设备验收尚未完成；依赖图书馆内部 Wi-Fi 的签到等操作也没有实测。

## 启用

先在树莓派部署镜像同步器和独立 HTTP 服务，确认端口可用、空源时返回 503，再由用户将域名转发到该端口。发布第一个完整 Release 后等待定时同步或手动启动服务，验证公开清单与 APK。已有 0.3.3 用户需手动安装 0.3.4 以获得更新模块，后续版本才可在应用内更新。

详细协议见 [UPDATES.md](UPDATES.md)，交接见 [RASPBERRY_PI_HANDOFF.md](RASPBERRY_PI_HANDOFF.md)，测试与待验项目见 [UPDATE_VALIDATION.md](UPDATE_VALIDATION.md)。
