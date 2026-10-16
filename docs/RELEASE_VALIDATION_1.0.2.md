# 1.0.2 发布验证

验证日期：2026-10-16。版本为 **1.0.2 / versionCode 16**，包名 `cn.edu.bucea.library`，最低支持 Android 8.0 / API 26。发行说明见 [releases/1.0.2.md](releases/1.0.2.md)。本次审查提交基于 `333f574`，包含北京时间修复提交 `adacdeb`。

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
| `BUCEA-Library-Seat-v1.0.2.apk` | 55,617,674 | `773160abe634f5c86b6d2d9e587538d963c7760f279e7accf57e355b23fa4d8a` |
| `BUCEA-Library-Seat-v1.0.2-arm64-v8a.apk` | 33,901,195 | `4cc1446ccaec9ec0a514fa072a3ed90a343e26847df0b4bf46f2f743d880ec70` |
| `BUCEA-Library-Seat-v1.0.2-armeabi-v7a.apk` | 33,336,001 | `2d2059a6140884f62dfbc590dd3f6c530fad76797ba2a8331b3f9ccb68293441` |
| `BUCEA-Library-Seat-v1.0.2-x86.apk` | 35,156,133 | `10cf323dc3af626d90d3fcdfa54b8259827c377c630d786cdacb52c276b2a08e` |
| `BUCEA-Library-Seat-v1.0.2-x86_64.apk` | 35,144,046 | `78c50f4a27ce61565143cedf3f775c75e6fbf7253866d58c792f23eb28448f91` |

## 公布后检查

发布脚本将逐项校验 GitHub Release 的五个 APK、`SHA256SUMS` 和 `update.json`，随后由 `release.yml` 同步 Cloudflare R2 并部署两站官网。发布完成后补充公开清单、五个下载地址、完整下载摘要和 Actions 运行结果。

