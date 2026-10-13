# 更新功能验证 — 2026-10-14

本轮在 MacBook 上实现与验证。无法连接 ADB，所有 Android 设备测试均未执行；下面的离线结果不能代替 Android 8 / 16 的安装验收。没有发布 GitHub Release，也没有部署树莓派或修改 CF Tunnel。

## 已完成

- Python **28 个测试通过**，包含实际运行 Caddy 2.11.7 的 3 个 HTTP 集成测试。覆盖最大版本选择、预发布、完整分页、条件请求、限流退避、草稿/不完整附件、撤回与分页移动、元数据及摘要失败、原子切换、崩溃恢复、资产内容不变、故障保留和至少 7 天清理。检查了公开文件 0644 与私有状态 0600 权限。
- Caddy 验证清单 200 / 304 / 503、缓存重新验证、APK Range 206、旧资产在版本切换和服务重启后继续续传、私有路径不可访问、缺失 APK 不缓存。请求使用 Tunnel 转发的公网 Host 头；配置解析通过，仅监听 127.0.0.1。
- Android JVM **21 个测试通过**，其中 16 个状态机测试、5 个清单/客户端/签名测试。覆盖确认前无下载、重复点击和并发检查合并、版本快照、失败重试、取消、下载与校验重启恢复、丢失任务、实际已安装版本比较、空间不足、24 小时限频及暂停提示、非法 URL/字段、客户端隔离和签名集合。
- Debug APK、release APK、AndroidTest APK 构建通过；`lint` 与全量 `lintRelease` 均为 **0 errors**，Debug 有 45 个建议性警告，release 有 46 个建议性警告。将仅 Debug 存在的预览 Activity 移除规则迁至 Debug Manifest，解决原有 release Lint 的 MissingClass 错误。
- `stageBetaApk` 输出 APK、`SHA256SUMS`、`update.json`。`aapt` 验证包名 `cn.edu.bucea.library`、版本 **0.3.4 / code 8**、最低 **API 26**、无 `application-debuggable`，包含 arm64-v8a、armeabi-v7a、x86、x86_64，使用通用 APK。
- `apksigner` 验证签名通过，与本机之前 0.3.3 APK 的证书 SHA-256 一致：`211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`。打包任务会拒绝不同证书；发布脚本在远端操作前验证附件大小和摘要。
- 对真实 GitHub 仓库进行了一次只读同步：API 扫描成功，现有旧 Release 因无 `update.json` 被跳过，没有候选。因此空镜像返回 503 是预期行为。

最终 APK 的字节数和摘要以 `app/build/outputs/beta-release/update.json` 与 `SHA256SUMS` 为准。交接包包含可审查的源码补丁与基础提交，不含 APK 或签名密钥。

## 可复现命令

```sh
CADDY_BINARY="$PWD/build/tools/caddy" python3 -m unittest discover -s tools/tests -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint :app:lintRelease :app:stageBetaApk
build/tools/caddy validate --config deploy/updates/Caddyfile --adapter caddyfile
python3 tools/make_update_handoff.py
```

Caddy 二进制来自官方 GitHub Release，仅用于本机 HTTP 测试，位于忽略的 `build/tools/`，不包含在交接包中。树莓派安装了 `caddy` 时 Python 测试自动使用 PATH 中的 Caddy；没有 Caddy 的环境会明确跳过 3 个 HTTP 集成测试，不应把跳过计为通过。

## 树莓派待验

systemd 的实际定时运行、Caddy 服务账号权限、服务器重启、现有 CF Tunnel ingress 和公开 HTTPS 入口，须在树莓派执行。首个完整 Release 发布后验证真实 APK 下载、摘要与 Range，保留已有站点配置。步骤与接续提示词见 [RASPBERRY_PI_HANDOFF.md](RASPBERRY_PI_HANDOFF.md)。

## Android 8 / 16 待验

以下均未实测，应在恢复设备条件后逐项记录结果：

1. 安装旧版并保存账号、登录会话、手动收藏；用当前同签名 release APK 覆盖安装，确认数据保留及 OCR / 登录 / 收藏 / 原生选座回归。现有离线 instrumentation 回归只编译了测试 APK，没有运行。
2. 首次手动安装更新模块后，用更大 `versionCode` 的同签名测试版本验证检查与安装。版本检查、关闭提示及“稍后”均无 APK 下载；断网自动失败安静，手动有结果；业务对话框、输入和请求进行中不显示更新提示。
3. 连续点击仅创建一个下载；Wi-Fi / 移动网络切换、系统暂停、进程终止与重启恢复；取消和失败清理；重试仍绑定原确认版本。系统下载完成通知不提供未校验 APK 的安装快捷入口。
4. 实际文件截断、摘要错误、错误包名 / 版本 / 最低 SDK / 签名均拒绝，内部文件复制中断可恢复；低空间失败可重试。JVM 测试中的验证器是模拟器，不能证明 Android PackageManager 在真实 APK 上的行为。
5. 拒绝与允许未知来源授权；授权返回后不自动安装，必须再次点击；FileProvider 仅能共享内部更新目录，安装器可读取授权 URI。
6. 后台完成、设备/进程重启不自动打开安装器；取消安装后可再次点击；成功后以实际已安装版本判断并删除残留任务和文件。

校园真实预约和依赖图书馆内部 Wi-Fi 的签到等交易未在本轮执行。之前设备记录保留在 [VALIDATION.md](VALIDATION.md)，不能作为本次 release APK 的新增测试结果。
