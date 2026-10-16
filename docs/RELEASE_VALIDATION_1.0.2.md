# 1.0.2 发布验证

验证日期：2026-10-16。版本为 **1.0.2 / versionCode 16**，包名 `cn.edu.bucea.library`，最低支持 Android 8.0 / API 26。发行说明见 [releases/1.0.2.md](releases/1.0.2.md)。发布目标提交为 `e5fddc7`，包含北京时间修复提交 `adacdeb`。

## 自动检查

- `python3 -m unittest discover -s tools/tests -v`：44 项通过。
- `./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest`：通过。
- `./gradlew :app:lintDebug :app:lintRelease`：通过，0 个 Error；剩余提示为依赖版本、KTX 写法、布局尺寸和 API 版本建议。
- `./gradlew :app:connectedDebugAndroidTest --no-parallel`：真机 `PKX110 - 16` 通过；需要真实账号或显式 live 参数的 23 项按设计跳过，其余测试无失败。测试结束后设备字体、旋转和应用状态已恢复。

## 真机与真实服务

- 使用 Android 16 真机安装 ARM64 release APK，安装覆盖成功，versionCode、包名和首页启动正常。
- 首页、馆内概况、区域座位、刷新入口、底部五项导航和设置入口均可访问；正常字体下截图未见横向溢出或裁切。
- 真实账号执行只读自动验证码登录，并读取历史记录；服务返回 4 条历史记录，状态解析成功。测试使用临时加密账号存储，结束后临时凭据、会话和账号数据均已清理。
- 未执行预约、取消、签到、离开、续时、结束使用或修改密码等真实写操作；这些操作继续由显式用户动作和既有安全门控保护。

## 发布产物

所有 APK 均为 `debuggable=false`，使用既有签名证书 `211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`，并通过 `stageReleaseApk` 的 ABI、版本、签名和清单校验。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `BUCEA-Library-Seat-v1.0.2.apk` | 55,617,674 | `84b38173922d53ab8ab69d61e7c3dbb5b681c415dd78153cce3edb3e02d86cf7` |
| `BUCEA-Library-Seat-v1.0.2-arm64-v8a.apk` | 33,901,195 | `5a827e53c87dce0c5ed022acde26eb1498f75c02e6b22aebfb4766132c357e1b` |
| `BUCEA-Library-Seat-v1.0.2-armeabi-v7a.apk` | 33,336,001 | `8f8697e46f4f6df9300b2daeb56a541e2b4fa4001618a120539eb963527873bb` |
| `BUCEA-Library-Seat-v1.0.2-x86.apk` | 35,156,133 | `0921511732adabe6788aeaa85e4f78c6c92af451b5a7183b679e7da8ec017b54` |
| `BUCEA-Library-Seat-v1.0.2-x86_64.apk` | 35,144,046 | `bb36c5e8dde37e492be8215f54b0838c4f9e9c9ae0aa079b77ec0c381c308bfc` |

## 公布后检查

正式 [GitHub Release v1.0.2](https://github.com/miofelix/bucea-library-seat/releases/tag/v1.0.2) 已发布，包含五个 APK、`SHA256SUMS` 和 `update.json` 七个附件。发布工作流 [38873095342](https://github.com/miofelix/bucea-library-seat/actions/runs/38873095342) 的 `publish` 与 `website` job 均成功，R2 清单和官网均切换到 1.0.2 / versionCode 16。

公开校验结果：`https://download.miofelix.com/bucea-library-seat/updates/latest.json` 和 `releases/latest.json` 返回 200，五个 APK 均完整下载并与上表 SHA-256、字节数一致；HEAD 返回正确长度，首段 `Range: bytes=0-1023` 返回 206 和 1024 字节。`library.miofelix.com` 与 GitHub Pages 镜像均返回 200，页面和 `release.json` 显示 1.0.2，五个下载链接有效且未发现横向溢出风险。

