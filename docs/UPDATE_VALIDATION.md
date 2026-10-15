# 更新功能验证 — 2026-10-15

> 历史部署记录：下文树莓派、systemd、Tunnel、镜像状态与服务测试均保留记录当日的事实。旧服务器实现现已退役，当前 Cloudflare Pages/R2 配置与启用检查见 [UPDATES.md](UPDATES.md)；不能用这些结果证明 R2 已上线或验收通过。

已发布正式版 [**v1.0.0 / versionCode 14**](https://github.com/miofelix/bucea-library-seat/releases/tag/v1.0.0)，包含紧凑座位卡片、全部结果连续展示、布局列表筛选与收藏页直接预约，并提供压缩后的通用包和四种架构包；本版重新执行的构建、安装、发布工具和七个公开附件的验证状态见 [ADB_ACCEPTANCE_1.0.0.md](ADB_ACCEPTANCE_1.0.0.md)。此前 code 13 的实现验收保留在 [ADB_ACCEPTANCE_0.3.8.md](ADB_ACCEPTANCE_0.3.8.md)，code 12 的分阶段界面验收保留在 [ADB_ACCEPTANCE_UI_2026-10-15.md](ADB_ACCEPTANCE_UI_2026-10-15.md)，不计作 code 14 重新执行的结果。本次镜像部署与同步按用户要求暂缓；GitHub 发布及 ADB 覆盖安装不计作真实公网更新事务。先前已发布的 0.3.7 附件与镜像检查见 [ADB_ACCEPTANCE_0.3.7.md](ADB_ACCEPTANCE_0.3.7.md)；校园访问故障与修复见 [CAMPUS_ACCESS_DIAGNOSIS.md](CAMPUS_ACCESS_DIAGNOSIS.md)。真实公网更新结果见 [ADB_ACCEPTANCE_0.3.6.md](ADB_ACCEPTANCE_0.3.6.md) 和 [ADB_ACCEPTANCE_0.3.5.md](ADB_ACCEPTANCE_0.3.5.md)，引导版本设备测试见 [ADB_ACCEPTANCE_0.3.4.md](ADB_ACCEPTANCE_0.3.4.md)。以下旧版本记录保留各自实际测试结果。

## 0.3.6 公开更新源验收

- 手机已完成真实公网 **0.3.5 / code 10 → 0.3.6 / code 11** 下载、校验、系统安装与自动清理。实际安装字节匹配 release，原账号/偏好/收藏/主题保留，手动检查显示“已是最新版本”。
- **v0.3.6-beta.1 / code 11** 已发布三个完整附件并同步到树莓派。公开清单返回 200，GitHub Release ID **412403499**、APK asset ID **639478202** 和本机发布元数据一致；清单条件请求返回 304。
- origin 完整 HTTP 流读取 **102488215 字节**，SHA-256 为 `c4edbf17818ce555ff0c57e3c8f822e157a64980e521ba889b9d52ff6cee8579`，与已签名并实机安装验收的候选 APK 一致。公开 APK 的 HEAD 为 200、大小正确，ETag 为 304；首段、续段和末段各 1024 字节的 Range 为 206，逐字节匹配候选。旧 code 10 的 APK asset **639250049** 仍可下载。
- 公开无效 Range 仍由 Cloudflare 缓存返回 **416 / immutable**，origin 为 **416 / no-store**，符合此前记录的转发层差异。未修改 Cloudflare 配置。本次有界公开 Range 不计为完整公网传输；手机原生 **0.3.5 → 0.3.6** 下载与安装的实际结果单独记录在本版 ADB 验收中。

证据位于 `.private/qa-0.3.6/public-audit.log` 和 `public-audit-v0.3.6-beta.1/`；更新镜像和 HTTP 服务的已部署脚本摘要与本地一致。

## 公开更新源验收与最终版本接续

- 完整引导版本 **v0.3.4-beta.1 / code 9** 已发布并同步。公开清单返回 200，GitHub Release ID **412320050**、APK asset ID **639131095** 和三个附件一致。真实公开下载得到 **102488215 字节**，SHA-256 为 `55d01ce683e4dbcc33b77e856691bf1a02ff8a2acce1bbd5510443b5f0c2cd98`，既有签名证书验证通过。
- 公开清单 / APK 的 ETag 请求返回 304，HEAD 为 200，Range 为 206 且内容一致；部署 HTTP 服务重启后可继续读取下一段。origin 的无效 Range 为 **416 / no-store**，Cloudflare 从缓存 APK 生成的 416 保留 **immutable** 响应头。此转发层差异已记录，未修改用户维护的 Cloudflare 配置。
- 真实应用原生公网 **debug code 8 → 已发布 release code 9** 流程通过。实际检查显示更新提示；“稍后”后进程重启安静，更新文件和该应用管理的 DownloadManager 行均为 0。实际公网 APK 达到 READY，摘要与公开附件一致；删除校验文件后任务与残留归零。
- 断网确认的唯一任务实际处于 **PAUSED / 等待网络，0 字节**，原生取消按钮清除任务。第二次断网任务经 force-stop、恢复 Wi-Fi 和应用重启后保留同一 token / Download ID / 已确认清单，完成实际下载并校验到 READY；后台完成未自动启动安装器。
- 用户明确点击后实际 OEM 安装器成功安装公开 code 9。SDK-only 最终验收通过：实际安装版本 / 关闭调试 / 精确 APK 摘要一致，加密账号、偏好、收藏和主题指纹保留，任务和更新文件自动清理。原始 Wi-Fi 开启、移动数据关闭状态均已恢复。
- 原生 UpdateHttpClient 的独立诊断 **1 项通过，2.028 秒**，实际读到公开清单 code 9；恢复 Wi-Fi 后的临时检查失败保留为过程证据，不计为完成的检查。原生 UI 最终验证以成功重连后的结果为准。
- 最终 **0.3.5 / code 10** 仅递增版本，构建、**23 项 JVM 测试**与两种 Lint 已通过。**v0.3.5-beta.1** 已发布，真实手机从已发布 code 9 原生下载并安装到 code 10；SDK-only 验收确认非调试包、精确公开 APK 摘要、原账号/偏好/收藏/主题保留和任务自动清理。手动检查显示“已是最新版本”。最终公开附件、ETag、Range、服务重启续传与旧 code 9 资产保留审计通过。

公开服务证据位于 `.private/qa-0.3.4/bootstrap-public-audit-final.log` 及 `public-audit-v0.3.4-beta.1/`。首次含更新模块的版本仍需手动安装。

## 已完成的 0.3.4 引导版本验收

- **106 项独立设备测试通过，21.277 秒，无跳过**；其中 **32 项更新服务测试通过，0.827 秒**。另有 **23 项 JVM 测试**与 **38 项 Python 测试**通过；Python 包含 13 项真实 HTTP 集成测试。
- 实际 PackageManager 接受同证书、更高版本、关闭调试的通用 APK，拒绝截断、错误摘要、非 APK、错误包名 / 版本 / 最低 SDK / 签名、debuggable 构建、设备不支持的 SDK 和已安装版本。APK 测试夹具与替代测试密钥均留在忽略目录。
- 实际 DownloadProvider、文件描述符和内部原子复制覆盖下载恢复、确认去重、取消、已确认版本重试、中断 `.part` 文件、孤立任务以及存储恢复。已完成下载行是本地注册的测试行，不用于声称公网下载或网络进度已经验证。
- 实际 FileProvider 验证授权 URI 可读、内部更新目录范围、相邻目录隔离与路径穿越拒绝。SharedPreferences / AtomicFile 测试覆盖损坏记录和备份恢复。更新任务恢复与清理已修复为检查应用更新目录中的规范路径，设备测试确认其他目录的同名下载不会被误认或删除；外部存储不可用时仍可清理内部文件。
- 旧 release **0.3.3 / code 7** 与 debug **0.3.4 / code 8** 的实际覆盖安装保留了加盐比较的解密账号、偏好、收藏、主题及完全一致的已存 Cookie。未清除生产数据。最终 release **code 9** 已通过系统安装器安装、SDK-only 验收和完整原生界面回归：实际包关闭调试，安装字节与候选一致，账号/偏好/收藏/主题保留，任务与安装残留自动清理。未知来源拒绝、允许后再次点击、安装取消及再次安装均已验证。
- 原生手动检查更新控件和主题选择通过设备 UI 验收；真实校园只读查询和状态清单各通过一次，四个返回楼层的原生地图均通过 Canvas / 列表 / 拖动 / 适应区域检查。密码入口的未实现 Toast 有独立设备测试证据。
- Debug、release 与 AndroidTest APK 构建通过，`lint` 与 `lintRelease` 均无错误。版本 **0.3.4 / code 9** 沿用既有签名证书；发布字节大小及摘要以最终 `update.json` 和 `SHA256SUMS` 为准。
- 已部署树莓派用户 systemd 服务实测 HTTP 重启和监听恢复、镜像手动同步成功、timer 保持 active / waiting。监听 `0.0.0.0:9080`；部署脚本与本地源码摘要相同。私有状态及环境配置为 0600；HTTP 私有路径返回 404。HTTP 与同步器使用同一既有 OS 用户，未配置独立 HTTP 账号。
- 首个完整 Release 发布前，现有三个 GitHub Release 均缺少 `update.json`。树莓派 origin 与公开 `https://bucea-library-seat.miofelix.com/updates/latest.json` 的 GET / HEAD 均返回预期 **503 / no-store**。这证明空源响应与部署可用，尚不证明真实公开 APK 下载。

实机、服务和构建证据位于忽略目录 `.private/qa-0.3.4/`。这些设备和本地安装结果属于 0.3.4 引导阶段；公开源结果见本文上方，最终版本结果在单独的 ADB 文档记录。

## 尚无本次验证的条件

Android 8 / API 26 设备或模拟器不可用，API 36 结果不能代替 API 26 验收。本轮未提交真实预约、取消、签到或后续座位使用操作；用户确认仅校园网，并暂不测馆内签到及其后续流程，第二个已授权真实账号不可用。修改密码当前未实现，入口提示通过不等于密码修改成功。

实际公网检查、确认、下载、取消、等待网络、进程重启恢复和系统安装已经在引导 code 9 上通过。移动网络切换、人工系统暂停、整机重启和实际空间耗尽仍未实测，不能从本地测试行或 JVM 测试推断完成。最终 code 10 的安装与发布后结果以 [ADB_ACCEPTANCE_0.3.5.md](ADB_ACCEPTANCE_0.3.5.md) 为准。

# 2026-10-14 初始实现的历史记录

以下记录保留初始 MacBook 实现阶段的实际结果。当时无法连接 ADB，没有执行 Android 设备测试、GitHub Release 发布、树莓派部署或 CF Tunnel 修改。后续 ADB 与已部署服务验收见本文上方和 [ADB_ACCEPTANCE_0.3.4.md](ADB_ACCEPTANCE_0.3.4.md)。这些历史测试总数与待验清单不代表本轮最终状态。

## 初始离线验证

- 按用户补充要求，服务改为直接监听端口的独立 Python HTTP 服务，域名和 CF Tunnel 交由用户配置。Python **38 个测试通过**，无需外部 HTTP 服务器或依赖，包含 **13 个真实 HTTP 集成测试**。同步器覆盖最大版本选择、预发布、完整分页、条件请求、限流退避、草稿/不完整附件、撤回与分页移动、元数据及摘要失败、原子切换、崩溃恢复、资产内容不变、故障保留和至少 7 天清理。检查了公开文件 0644 与私有状态 0600 权限。
- HTTP 服务验证清单 200 / 304 / 503、缓存重新验证、Range 206 / 416、If-Range、HEAD、旧资产切换与重启续传、并发下载与连接限额、私有路径和符号链接隔离、缺失 APK 不缓存、空目录启动及正常停止。请求保留公网 Host 头，默认端口为 9080。
- Android JVM **21 个测试通过**，其中 16 个状态机测试、5 个清单/客户端/签名测试。覆盖确认前无下载、重复点击和并发检查合并、版本快照、失败重试、取消、下载与校验重启恢复、丢失任务、实际已安装版本比较、空间不足、24 小时限频及暂停提示、非法 URL/字段、客户端隔离和签名集合。
- 设置页顶部提供全宽“手动检查更新”按钮、检查进度和结果反馈，无需连接校园网。本次入口调整重新通过上述 21 个 JVM 测试、Debug / release 构建及两种 Lint；设备上的实际点击和显示仍待验证。
- Debug APK、release APK、AndroidTest APK 构建通过；`lint` 与全量 `lintRelease` 均为 **0 errors**，Debug 有 45 个建议性警告，release 有 46 个建议性警告。将仅 Debug 存在的预览 Activity 移除规则迁至 Debug Manifest，解决原有 release Lint 的 MissingClass 错误。
- `stageBetaApk` 输出 APK、`SHA256SUMS`、`update.json`。`aapt` 验证包名 `cn.edu.bucea.library`、版本 **0.3.4 / code 8**、最低 **API 26**、无 `application-debuggable`，包含 arm64-v8a、armeabi-v7a、x86、x86_64，使用通用 APK。
- `apksigner` 验证签名通过，与本机之前 0.3.3 APK 的证书 SHA-256 一致：`211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801`。打包任务会拒绝不同证书；发布脚本在远端操作前验证附件大小和摘要。
- 对真实 GitHub 仓库进行了一次只读同步：API 扫描成功，现有旧 Release 因无 `update.json` 被跳过，没有候选。因此空镜像返回 503 是预期行为。

最终 APK 的字节数和摘要以 `app/build/outputs/beta-release/update.json` 与 `SHA256SUMS` 为准。交接包包含可审查的源码补丁与基础提交，不含 APK 或签名密钥。

## 当时执行的命令（历史，旧服务工具已移除）

```sh
python3 -m unittest discover -s tools/tests -v
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint :app:lintRelease :app:stageBetaApk
python3 tools/update_server.py --root /var/lib/bucea-updates/public --listen 0.0.0.0 --port 9080
python3 tools/make_update_handoff.py
```

当时的 HTTP 测试直接启动 Python 服务，MacBook 和树莓派均运行全部测试；服务由 systemd 管理。这些命令仅保留历史证据，旧服务与交接工具已移除，当前部署不执行此段。

## 初始树莓派待验清单（历史）

HTTP 服务与同步器的 systemd 运行、独立服务账号读取权限、服务器重启和实际监听端口，须在树莓派执行。部署完成后报告 HTTP 地址和端口，用户负责配置域名及 CF Tunnel，再请求公开 HTTPS 入口验证。首个完整 Release 发布后验证真实 APK 下载、摘要与 Range。旧接续手册已移除；当前迁移与部署步骤见 [UPDATES.md](UPDATES.md)。

## 初始 Android 8 / 16 待验清单（历史）

初始实现阶段以下均未实测。Android 16 上已补做的项目和仍未覆盖的条件以本文上方及分阶段 ADB 记录为准：

1. 安装旧版并保存账号、登录会话、手动收藏；用当前同签名 release APK 覆盖安装，确认数据保留及 OCR / 登录 / 收藏 / 原生选座回归。现有离线 instrumentation 回归只编译了测试 APK，没有运行。
2. 首次手动安装更新模块后，用更大 `versionCode` 的同签名测试版本验证检查与安装。版本检查、关闭提示及“稍后”均无 APK 下载；断网自动失败安静，手动有结果；业务对话框、输入和请求进行中不显示更新提示。
3. 连续点击仅创建一个下载；Wi-Fi / 移动网络切换、系统暂停、进程终止与重启恢复；取消和失败清理；重试仍绑定原确认版本。系统下载完成通知不提供未校验 APK 的安装快捷入口。
4. 实际文件截断、摘要错误、错误包名 / 版本 / 最低 SDK / 签名均拒绝，内部文件复制中断可恢复；低空间失败可重试。JVM 测试中的验证器是模拟器，不能证明 Android PackageManager 在真实 APK 上的行为。
5. 拒绝与允许未知来源授权；授权返回后不自动安装，必须再次点击；FileProvider 仅能共享内部更新目录，安装器可读取授权 URI。
6. 后台完成、设备/进程重启不自动打开安装器；取消安装后可再次点击；成功后以实际已安装版本判断并删除残留任务和文件。

校园真实预约和依赖图书馆内部 Wi-Fi 的签到等交易未在本轮执行。之前设备记录保留在 [VALIDATION.md](VALIDATION.md)，不能作为本次 release APK 的新增测试结果。
