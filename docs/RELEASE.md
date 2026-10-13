# Android 测试版发布

版本号及 `versionCode` 在 `app/build.gradle.kts` 中维护。每次更新需递增 `versionCode`，当前发布标签为 `v0.3.3-beta.1`。

## 构建与命名

使用 JDK 17 和项目 Gradle Wrapper。完成回归测试、Lint 和 ADB 真机检查后运行：

```sh
./gradlew :app:stageBetaApk
```

产物在 `app/build/outputs/beta-release/`：

- `北建大图书馆选座-v0.3.3-beta.1.apk`：实际下载文件名包含应用名称及版本。
- `SHA256SUMS`：该 APK 的 SHA-256 校验值。

GitHub Release 标题使用 `北建大图书馆选座 v0.3.3 测试版`，APK 附件显示名称使用 `北建大图书馆选座 v0.3.3 测试版 · APK（Android 8.0+）`。上传时使用 Gradle 生成的中文文件名，避免仅修改显示名称而使下载文件仍叫 `app-debug.apk`。

## 签名与安装验证

已分发的测试版使用本机 Android Debug 签名。此任务使用同一 `assembleDebug` 产物，保留现有用户覆盖安装及账号数据的兼容性。不要删除或重新生成本机 `~/.android/debug.keystore`。该签名文件不应提交到 Git。

发布前用 SDK 的 `apksigner verify --print-certs` 对比本次与上次 APK 的签名证书 SHA-256，确认一致。通过 ADB 覆盖安装最终附件并启动应用：

```sh
adb install -r 'app/build/outputs/beta-release/北建大图书馆选座-v0.3.3-beta.1.apk'
adb shell am start -n cn.edu.bucea.library/.MainActivity
```

发布说明需如实列出当前构建的测试结果，注明 Debug 签名测试包；依赖图书馆内部 Wi-Fi 的签到、暂离、返回、结束使用等操作未连接该网络时应列为未实测。实际预约或取消结果应以服务端回执或读回确认。
