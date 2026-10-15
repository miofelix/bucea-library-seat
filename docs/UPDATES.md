# GitHub Release 更新服务

开发机负责构建、签名与发布；树莓派运行独立 Python HTTP 服务和同步器，缓存 GitHub Release 成品。服务直接提供 HTTP 端口，域名和 Cloudflare Tunnel 由用户配置。

- 清单：`https://bucea-library-seat.miofelix.com/updates/latest.json`
- APK：`https://bucea-library-seat.miofelix.com/updates/apks/<GitHub资产ID>.apk`
- 包名：`cn.edu.bucea.library`，Android 8.0 / API 26 起，单一更新流、通用 APK。
- 测试版与正式版一起按最大的 `versionCode` 选择；同代码时优先正式版，再以 release ID 决定。不要复用 `versionCode`。

## 发布格式

每个发布必须在同一 Release 内上传 APK、`SHA256SUMS` 和 `update.json`。旧 Release 缺少清单会被跳过。示例：

```json
{
  "schemaVersion": 1,
  "packageName": "cn.edu.bucea.library",
  "versionCode": 8,
  "versionName": "0.3.4",
  "minSdk": 26,
  "notes": "本次更新说明",
  "apk": {
    "name": "BUCEA-Library-Seat-v0.3.4-beta.1.apk",
    "size": 102426255,
    "sha256": "构建任务生成的64位小写十六进制摘要"
  }
}
```

大小和摘要以实际生成的清单为准。清单与 APK 元数据来自 `aapt dump badging`，发布前由 `apksigner` 验证签名，并拒绝 `debuggable=true` 的 APK。`SHA256SUMS` 是一行 `<摘要><两个空格><APK文件名>`。

从 0.3.8 起，GitHub Release 中的 APK、源 `update.json` 的 `apk.name` 与 UTF-8 `SHA256SUMS` 统一使用 `北建大图书馆选座-vx.x.x.apk`。测试版后缀只用于 Release 标签。新版镜像先按中文原名定位附件并校验，再将公开清单的 `apk.name` 转为旧客户端支持的 ASCII 兼容名（例如 `BUCEA-Library-Seat-v0.3.8.apk`），并在 `apk.displayName` 保留中文附件名。实际下载仍使用原附件的资产 ID、大小及 SHA-256；历史英文附件继续按原规则处理。镜像须部署包含该兼容处理的新脚本后才能选择中文附件，本次用户明确将镜像部署留待稍后处理。

镜像公开清单补充 `prerelease`、`releaseTag`、`releaseId`、`apk.assetId`、`apk.url`。预发布标记取自 GitHub；公开下载地址只使用项目 HTTPS 域名。

## 镜像行为

`tools/update_mirror.py` 使用 Python 3.9+ 标准库，每次运行完成一次同步。systemd timer 每 10 分钟启动；文件锁防止并发同步。Release 和附件列表都完整分页，使用 ETag / Last-Modified 条件请求；遇到限流记录持久退避时间，下一次定时运行继续，不在进程里长时间等待。

`/var/lib/bucea-updates/` 包含 `state/`、`scratch/` 和 `public/updates/`。HTTP 服务只允许访问公开清单与 APK，不暴露状态目录。GitHub token 可放在 root 所有、权限 0600 的 `/etc/bucea-updates.env`，只需读取该公开仓库的权限。

只有非草稿、附件完整、清单字段和校验文件一致的 Release 才进入候选。APK 先下载到私有临时目录，验证大小和 SHA-256、fsync 后原子移动到资产 ID 路径，最后原子替换 `latest.json`。资产 ID 文件已有内容时必须一致，不能覆盖为不同内容。

网络、GitHub、分页或磁盘故障保留最后成功清单。完整扫描后，如果当前发布消失，再用单独的 release ID 请求确认撤回，避免移动分页误撤回。确认撤回或附件不再有效后重新选择；没有候选时删除公开清单，由 HTTP 服务返回 503。缓存 APK 继续可下载。

清理保留当前和前两个成功选中的资产；其他资产自最后选中或首次缓存起至少保留 7 天。整个同步和清理使用同一把锁，当前清单引用的文件永远受到保护。磁盘不足时保留现状，需运维清理或扩容。

## HTTP 服务

`tools/update_server.py` 仅使用 Python 3.9+ 标准库，默认监听 `0.0.0.0:9080`，可通过 `--listen` / `--port` 调整。systemd 服务为 `bucea-updates-http.service`。服务接受公网 Host 头，域名由用户转发到这个 HTTP 端口；部署不涉及修改 Tunnel 或安装其他 Web 服务器。

服务只提供清单和资产 ID APK 两种路径，支持 GET / HEAD、单段 Range、ETag、Last-Modified 和条件请求。Range 支持指定起止、从某字节继续和末尾片段；无效范围返回 416，If-Range 不匹配时返回完整文件。每次响应持有独立文件描述符，原子切换或清理不改变正在传输的内容。并发连接上限为 32，空闲读取超时 30 秒。

清单成功响应为 `no-cache, max-age=0, must-revalidate`，APK 成功响应为 `public, max-age=604800, immutable`，错误响应为 `no-store`。HTTP 服务以独立的 systemd 动态账号运行，只有读取权限，不需要 GitHub token。由用户维护的转发应保留这些缓存语义。

## Android 行为

更新模块与校园账号模块独立。固定清单使用新建的 HTTPS OkHttp 客户端，没有 Cookie、认证、缓存和重定向。自动检查按最近尝试时间限为每 24 小时一次，失败不打扰；手动检查始终可用，单次并发检查合并，最近尝试与成功时间分别持久保存。

手动入口位于设置页顶部的“应用更新”卡片，按钮为“手动检查更新”。检查中显示进度并暂时禁止重复点击，结束后显示最新版本、可用更新或失败信息。检查无需连接校园网，也不会直接下载 APK；发现更新后仍需用户确认。

提示在前台、业务不忙、业务对话框全部关闭、座位号输入结束且停止触摸 1.5 秒后出现。它显示版本、测试版标记、说明、大小、“下载更新”和“稍后”。稍后暂停该 `versionCode` 的自动提示 24 小时，手动检查可以重新打开。

确认后用 DownloadManager 创建唯一任务，允许 Wi-Fi 和移动数据，禁止漫游。系统只显示下载中的通知，完成入口由应用在校验后提供；在支持的系统上隐藏公共下载列表中的任务。确认的完整清单先原子保存，再创建系统下载；崩溃时按专用文件名恢复任务。重启不会自动创建新下载。任务失败后由用户重试，仍下载已确认版本；取消会移除系统任务和临时文件。

下载成功后复制到内部的 `files/updates/install/`，原子保存前验证大小、摘要、实际包名、版本代码、显示版本、最低 SDK、关闭调试、通用 APK 及当前签名证书集合。创建下载和复制前检查空间。当前签名必须完全一致，签名轮换另行设计。

用户点击“安装更新”后再次校验文件。如果尚未允许安装未知来源，打开该应用的授权页；返回后需再次点击安装。通过只暴露 `updates/install/` 的非导出 FileProvider 调用系统安装器。下载完成、进程重启、授权返回都不会自动启动安装。只有实际已安装 `versionCode` 达到目标才清理任务；取消安装后保留已校验文件供再次点击。

DownloadManager 无法逐跳限制重定向，所以初始 URL 严格限定本项目固定 HTTPS 地址，落地文件仍必须通过摘要及 APK 签名校验。

## 接续与验证

树莓派部署和接续提示词见 [RASPBERRY_PI_HANDOFF.md](RASPBERRY_PI_HANDOFF.md)，本轮 MacBook 验证及实机待验项见 [UPDATE_VALIDATION.md](UPDATE_VALIDATION.md)。首个含更新模块的版本必须手动安装；本次代码没有上传 Release 或修改远端服务。
