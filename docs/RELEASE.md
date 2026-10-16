# Android 发布

APK 的构建、签名和 GitHub 发布在保存原签名密钥的开发机上执行；发布后由 GitHub Actions 将成品与清单分发到 Cloudflare R2，官网由 Cloudflare Pages 托管。版本在 `app/build.gradle.kts` 中维护，本次版本为 **1.0.2 / versionCode 16**，每次后续更新递增 `versionCode`。本次更新修复预约状态、字母学号登录和跨时区日期显示问题，发行说明见 [releases/1.0.2.md](releases/1.0.2.md)。最终构建、签名、设备和公网结果见 [RELEASE_VALIDATION_1.0.2.md](RELEASE_VALIDATION_1.0.2.md)。

## 构建

使用 JDK 17、Android SDK 和项目 Gradle Wrapper：

```sh
python3 -m unittest discover -s tools/tests -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint :app:lintRelease
./gradlew -PreleaseDistributions=true -PreleaseTag=v1.0.2 -PreleaseNotesFile=docs/releases/1.0.2.md :app:stageReleaseApk
```

有日常数据的真机不要使用会在测试结束时卸载目标 App 的 `connectedDebugAndroidTest`；应通过 `adb install -r` 显式覆盖安装已构建的 APK，再调用 `am instrument` 运行测试，避免自动卸载清理本机数据。

正式版产物位于 `app/build/outputs/release/`：

- `BUCEA-Library-Seat-v1.0.2.apk`
- `BUCEA-Library-Seat-v1.0.2-arm64-v8a.apk`
- `BUCEA-Library-Seat-v1.0.2-armeabi-v7a.apk`
- `BUCEA-Library-Seat-v1.0.2-x86.apk`
- `BUCEA-Library-Seat-v1.0.2-x86_64.apk`
- `SHA256SUMS`
- `update.json`

正式版使用 `-PreleaseDistributions=true :app:stageReleaseApk`，同时产生通用包与四种完整独立 APK；分架构包无需安装其他 split 文件。可用 `-PreleaseTag=v1.0.2` 和 `-PreleaseNotesFile=docs/releases/1.0.2.md` 指定标签和说明；测试版另用 `:app:stageBetaApk`，输出到 `app/build/outputs/beta-release/`。不传 `releaseDistributions` 时可暂存单通用包。`update.json` 保留通用包字段，增加 `apkVariants` 列表；`SHA256SUMS` 列出五个 APK。暂存工具验证五包的实际 ABI、相同版本、签名和非调试状态。同一版本的补发也需要先增加 `versionCode`，已发布的资产禁止覆盖替换。

默认压缩原生库，由 Android 在安装时一次性解压当前架构，保留原始 ONNX 推理引擎和离线模型；可用 `-PcompressNativeLibraries=false` 构建未压缩原生库的对照包。体积、安装占用取舍和后续优化评估见 [APK_SIZE.md](APK_SIZE.md)。

两个任务均使用 `assembleRelease`，关闭调试、压缩资源，并保留本机已有 Android Debug 证书，以兼容先前分发的 APK。**不要删除或重新生成 `~/.android/debug.keystore`，不要把密钥复制到 Git、GitHub Actions 或 Cloudflare。** 证书迁移不包含在本次功能中。

用 SDK `apksigner verify --print-certs` 对比旧包和待发布包的证书 SHA-256。当前沿用的证书摘要是 `211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`，打包任务会拒绝其他证书，防止新机器自动生成的 Debug 密钥破坏覆盖安装兼容。发布 APK 的具体摘要每次以 `SHA256SUMS` 为准。

## 上传

先审查并推送将要发布的提交，再使用已经验证的七个附件。`tools/publish_release.py` 在草稿中上传五个 APK 和两份元数据，逐项核对远端文件名、大小和摘要后才发布；它拒绝已有标签，并在任何远端写入前重新验证本地大小和摘要：

```sh
python3 tools/publish_release.py --directory app/build/outputs/release --tag v1.0.2 --target <已审查并推送的40位提交SHA> --notes docs/releases/1.0.2.md
```

正式版使用 `v1.0.2` 标签且不传 `--prerelease`；测试版需使用对应测试标签并传入该参数。发布脚本使用 `gh` 的当前授权。如果上传失败，草稿会保留，检查附件后人工完成或删除草稿重试。不要用 `--clobber` 替换已发布资产。

Release 标题使用 `北建大图书馆选座 v1.0.2`，按正式版发布。APK 附件统一命名为 `BUCEA-Library-Seat-vx.x.x.apk`，分架构包在版本后追加 `-arm64-v8a`、`-armeabi-v7a`、`-x86` 或 `-x86_64`。版本来自 APK 的三段 `versionName`；测试版标签的 `-beta.N` 不进入安装包文件名，打包与上传工具会强制校验。同一文件名同时写入 `update.json` 和 `SHA256SUMS`。GitHub 上传会清理中文附件名，尝试修改远端名称也未成功；按用户最新要求，英文格式取代此前的中文命名约定，后续统一沿用。历史已发布附件不改名、不替换。

1.0.0 的历史 Android 16 / API 36 验收见 [ADB_ACCEPTANCE_1.0.0.md](ADB_ACCEPTANCE_1.0.0.md)，版本递增前的分阶段界面验收见 [ADB_ACCEPTANCE_UI_2026-10-15.md](ADB_ACCEPTANCE_UI_2026-10-15.md)。先前客户端自动重检的修复验收见 [CAMPUS_ACCESS_DIAGNOSIS.md](CAMPUS_ACCESS_DIAGNOSIS.md)，先前公开更新结果见 [ADB_ACCEPTANCE_0.3.5.md](ADB_ACCEPTANCE_0.3.5.md)。Android 8 / API 26 设备验证仍未完成，依赖图书馆内部 Wi-Fi 的实际签到与后续使用操作未执行。不能将模拟任务或安装器测试记作公开网络更新成功。

## 发布到 R2 与官网

GitHub Release 是签名成品的发布来源。发布完成后，[`release.yml`](../.github/workflows/release.yml) 读取并核对该 Release 的全部附件，将通用包和四种分架构包上传 R2，再发布 Android 更新清单和官网使用的下载元数据。无需在 Actions 中构建或签名 Android，也不再部署服务器进程。

R2 bucket、两套 token、`download.miofelix.com` 及仓库 Actions 变量和 secrets 已配置。发布 `v1.0.1` 后由工作流自动同步；需要重试时通过手动 tag 工作流同步同一 Release。公开清单的 `versionCode` 不得回退，已发布附件不覆盖替换。

正常发布顺序为：本地构建与验收 → 完整 GitHub Release → R2 全部 APK → 最后更新清单 → 部署官网。只有前一步完成才执行下一步，上传失败时保留上一份成功清单。官网部署和元数据维护见 [WEBSITE.md](WEBSITE.md)。

Android 更新清单为 `https://download.miofelix.com/bucea-library-seat/updates/latest.json`，通用包及分架构包均使用 `https://download.miofelix.com/bucea-library-seat/releases/<tag>/<name>`。官网 <https://library.miofelix.com/> 共用相同的 R2 APK 下载地址。

`1.0.1 / versionCode 15` 构建包含新的 R2 清单与下载地址。此前同步到 R2 的 `v1.0.0` 只改变分发位置，APK 字节和内置旧地址未改变；使用该旧地址的版本需手动安装新版本。本次不提供旧地址兼容。

R2 bucket 使用 `miofelix-software-releases`，下载域名为 `download.miofelix.com`。Pages 项目为 `bucea-library-seat-website`，采用 Direct Upload，由 Actions 统一部署；部署凭据使用账户级 **Pages → Write** token。2026-10-16 已成功同步 `v1.0.0` 并部署官网，`library.miofelix.com` 已活动且启用 SSL，五个 APK 的公开完整下载与 SHA-256 校验通过。实际配置和上线结果见 [UPDATES.md](UPDATES.md)。

同日正式发布 `v1.0.1`，[自动发布工作流](https://github.com/miofelix/bucea-library-seat/actions/runs/38794715008) 完成 R2 同步与官网部署，五个 APK 的公开完整下载和 SHA-256 均通过。最终成品、设备测试及实际验证范围见 [RELEASE_VALIDATION_1.0.1.md](RELEASE_VALIDATION_1.0.1.md)。

每次发布检查 Actions 结果，并从固定公网地址核对清单版本、所有 APK 的大小与摘要。HEAD、ETag 和抽样 Range 不计为完整下载；GitHub 附件读回或 ADB 安装不计为新 R2 来源的真实 Android 更新事务。

[UPDATE_VALIDATION.md](UPDATE_VALIDATION.md) 与各版本 ADB 文档保留当时实际验收，旧服务器部署记录属于已退役架构的历史快照，不再作为当前部署步骤。新架构需记录本次实际执行结果。
