# Cloudflare 更新分发

Android APK 在保存原签名密钥的开发机上构建、验证并发布到 GitHub Releases。GitHub Actions 从完整 Release 读取成品，通过 `tools/publish_cloudflare.py` 校验并上传 Cloudflare R2；官网由 Cloudflare Pages 托管。服务器同步器、独立 HTTP 服务和 systemd 配置已从仓库移除，当前分发流程无需树莓派。

官网使用 <https://library.miofelix.com/>。APK、应用更新与官网元数据均由共享 R2 bucket 的 `download.miofelix.com` 自定义域名提供，本项目统一使用 `bucea-library-seat/` 前缀：

| 用途                    | 地址                                                                          |
| ----------------------- | ----------------------------------------------------------------------------- |
| Android 更新清单        | `https://download.miofelix.com/bucea-library-seat/updates/latest.json`        |
| 官网与 Android APK 下载 | `https://download.miofelix.com/bucea-library-seat/releases/<tag>/<APK文件名>` |
| 官网最新下载元数据      | `https://download.miofelix.com/bucea-library-seat/releases/latest.json`       |

清单和 APK 直接返回内容，不经过官网跳转；正常 GET 返回 200，Range 请求返回 206。Cloudflare Pages 仅托管网页，APK 留在 R2。

当前 R2 bucket 尚未创建。本文描述已实现的自动化与后续启用步骤；官网源码保留 GitHub Release 下载入口，在 R2 准备完成前继续可用。先创建 bucket、配置全部凭据并绑定下载域名，再运行首次同步；该 Release 工作流在 R2 上传和公开检查成功后直接构建并部署官网。实际启用以 Actions 日志和下文的公开地址检查为准。此前服务的验收保留在 [UPDATE_VALIDATION.md](UPDATE_VALIDATION.md)，其中旧服务器与端口记录均属历史。

本次只修改 App 源码中的下载域名、清单地址与 URL 校验，未重新打包、替换或发布现有 `v1.0.0` APK。现有 Release 字节及其内置的旧更新地址保持原样；同步 `v1.0.0` 到 R2 可以先启用官网 APK 下载，但不会改写 APK 内的更新地址。新地址将在后续构建发布的 App 中生效，本次不提供旧地址兼容。

## Release 格式

包名固定为 `cn.edu.bucea.library`，最低 Android 8.0 / API 26。每个 Release 包含通用 APK、`SHA256SUMS` 和 `update.json`；提供分架构包时同时包含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四包，共七个附件。应用内更新仍选择通用包，官网提供 ARM64 等分架构包。

源清单的通用字段示例：

```json
{
  "schemaVersion": 1,
  "packageName": "cn.edu.bucea.library",
  "versionCode": 14,
  "versionName": "1.0.0",
  "minSdk": 26,
  "notes": "本次更新说明",
  "apk": {
    "name": "BUCEA-Library-Seat-v1.0.0.apk",
    "size": 55527607,
    "sha256": "构建任务生成的64位小写十六进制摘要"
  }
}
```

大小和摘要以发布成品为准。`SHA256SUMS` 每个 APK 对应一行 `<摘要><两个空格><APK文件名>`。可选 `apkVariants` 列出各分架构包的 `abi`、`name`、`size`、`sha256` 和 `minSdk`。暂存、GitHub 发布和 R2 发布均校验全部声明的附件；校验失败不更新公开清单。

正式版安装包使用 `BUCEA-Library-Seat-vx.x.x.apk`，分架构包增加 `-arm64-v8a` 等 ABI 后缀，测试版后缀只进入 Release 标签。历史已发布文件保持原名。实际包名、版本、最低 SDK、关闭调试及原签名证书由 Android 构建与发布验收确认；R2 自动化不接收签名密钥，也不重新构建 APK。

## 自动发布

[`release.yml`](../.github/workflows/release.yml) 将已发布的 GitHub Release 同步到 R2，也提供手动 tag 入口，用于首次同步或在网络故障后重新执行同一发布。所有文件先下载到工作目录，核对 Release 元数据、附件大小和 SHA-256，再上传：

1. 全部 APK 写入 `bucea-library-seat/releases/<tag>/<name>`，官网与 Android 更新共用这些下载 URL。
2. 完成 APK 上传与校验后，最后写入 `bucea-library-seat/updates/latest.json`。公开清单保留 Android 协议字段，补充 Release 标识及 R2 下的通用 APK URL。
3. 官网最新元数据写入 `bucea-library-seat/releases/latest.json`，本地同时输出 `<website-output>/release.json`。官网使用已选中发布的版本、大小和下载地址，部署流程见 [WEBSITE.md](WEBSITE.md)。

`versionCode` 只递增，正式版与测试版共用同一更新流。重复同步相同发布应保持对象内容一致，不能覆盖成不同字节。较旧发布不能将 `latest.json` 降级，上传中断时保留上一份成功清单。相同 tag 的同步串行执行，不同 tag 可并行上传；R2 使用 ETag 条件写入（CAS）防止并发发布降低清单版本。官网部署任务另用共享的 `cloudflare-production` 队列串行执行，获得部署位置后重新读取最新元数据，避免排队期间的新发布被旧页面覆盖。历史版本 APK 保持不可变。

APK 使用 `public, max-age=31536000, immutable`，清单和可变下载元数据使用 `no-store, max-age=0`。R2 原生提供 HEAD、ETag、条件请求和 Range；APK 内容的可信依据是发布元数据中的 SHA-256，不把 R2 ETag 当作 SHA-256。Android 安装前仍执行摘要和签名验证。

## 一次性配置

### 1. 创建 R2 bucket

1. 打开 Cloudflare 控制台，进入保存 `miofelix.com` 的账户。记录账户首页或右侧 API 信息中的 **Account ID**。
2. 进入 **存储和数据库 → R2 对象存储（R2 Object Storage）**。首次使用时按控制台提示启用 R2。
3. 点击 **创建存储桶（Create bucket）**，建议名称为 `miofelix-software-releases`，保留默认标准存储设置后创建。这个 bucket 可存放其他项目，本项目全部对象统一使用 `bucea-library-seat/` 前缀。
4. 在 bucket 的 **设置 → 自定义域（Settings → Custom Domains）** 中连接 `download.miofelix.com`，按提示完成 DNS 与证书配置。域名直接映射到 bucket 根目录，项目名前缀属于对象键。

R2 S3 API endpoint 为 `https://<CLOUDFLARE_ACCOUNT_ID>.r2.cloudflarestorage.com`，不是公开下载域名。

### 2. 创建 R2 对象读写凭据

1. 在 R2 概览中打开 **管理 R2 API 令牌（Manage R2 API Tokens）**，创建令牌。
2. 权限选择 **对象读和写（Object Read & Write）**；范围选择 **仅应用于特定存储桶（Apply to specific buckets only）**，只勾选上一步的 bucket。
3. 创建后保存 **Access Key ID** 和 **Secret Access Key**。它们分别用于 GitHub 的 `R2_ACCESS_KEY_ID` 和 `R2_SECRET_ACCESS_KEY`；Secret Access Key 只在创建完成时显示。
4. 不将 R2 S3 凭据填写为 Pages API token，也不把凭据写进仓库文件或命令行参数。

### 3. 获取 Pages 项目名称并创建 Pages token

1. 进入 **Workers 和 Pages（Workers & Pages）**，打开已托管官网的 Pages 项目，记录**项目名称**。它是项目标识，不是 `library.miofelix.com` 域名。
2. 在 Cloudflare 个人资料的 **API 令牌（API Tokens）** 中点击 **创建令牌 → 自定义令牌（Create Custom Token）**。
3. 添加权限 **账户（Account）→ Cloudflare Pages → 编辑（Edit）**，账户资源限定到托管官网的这个账户。
4. 创建后将 token 保存为 GitHub 的 `CLOUDFLARE_API_TOKEN`。它负责 Pages 部署，R2 对象上传仍使用上一步的 S3 凭据。

### 4. 配置 GitHub Actions

打开仓库 **Settings → Secrets and variables → Actions**。在 **Variables** 页签创建以下 repository variables：

| 名称                       | 值                                                    |
| -------------------------- | ----------------------------------------------------- |
| `CLOUDFLARE_ACCOUNT_ID`    | Cloudflare Account ID                                 |
| `R2_BUCKET`                | 创建的 bucket 名称，建议 `miofelix-software-releases` |
| `CLOUDFLARE_PAGES_PROJECT` | 已有 Pages 项目名称                                   |

在 **Secrets** 页签创建以下 repository secrets：

| 名称                   | 值                                |
| ---------------------- | --------------------------------- |
| `R2_ACCESS_KEY_ID`     | R2 token 的 Access Key ID         |
| `R2_SECRET_ACCESS_KEY` | R2 token 的 Secret Access Key     |
| `CLOUDFLARE_API_TOKEN` | 独立的 Cloudflare Pages API token |

GitHub 的 `GITHUB_TOKEN` 由 Actions 提供，用于读取 Release，无需手动创建；Android 签名密钥不上传到 Actions 或 Cloudflare。

### 5. 首次同步并部署官网

R2 下载域名和证书应已生效，三项 variables 与三项 secrets 均已配置。按 [WEBSITE.md](WEBSITE.md) 关闭现有 Pages 项目的 Git 自动生产部署，保留现有项目及官网域名，由 Actions 统一部署。

1. 打开仓库 **Actions → Publish release to Cloudflare → Run workflow**，选择 `main`，填写已发布的 tag（例如 `v1.0.0`），同步已有 Release。
2. 检查 `publish` job 的附件校验、R2 上传和公开地址检查。对象全部位于 `bucea-library-seat/` 下，包括 `releases/<tag>/<name>`、`updates/latest.json` 和 `releases/latest.json`；公开检查失败会阻止后续官网部署。
3. `publish` 成功后，`website` job 自动读取最新 R2 元数据，构建、检查下载地址并部署到现有 Pages 项目。无需另行手动启用部署。
4. 打开 <https://library.miofelix.com/>，核对版本、大小与下载链接，完整下载 APK 并核对摘要；R2 URL 直接返回内容，不重定向到 `r2.dev`、S3 endpoint 或官网。
5. 后续构建发布包含新地址的 App 后，执行真实 Android 更新检查与下载验收。首次同步现有 `v1.0.0` 只验证分发，不能证明该旧 APK 已切换更新地址。

Cloudflare 的缓存、重写、Access 和挑战规则应允许原生 Android 客户端直接读取清单及 APK。

## 发布工具与离线检查

工作流安装 `tools/requirements-publish.txt`，通过 R2 S3 API 发布。本地可对已经下载并校验的 Release 运行只读计划；`--directory` 目录只包含该 Release 的 APK、`SHA256SUMS` 和 `update.json`，GitHub REST Release 响应另存为 `--release-json` 指定的文件：

```sh
python3 tools/publish_cloudflare.py \
  --directory /path/to/release-assets \
  --release-json /path/to/github-release.json \
  --bucket miofelix-software-releases \
  --endpoint-url "https://<CLOUDFLARE_ACCOUNT_ID>.r2.cloudflarestorage.com" \
  --website-output build/website-release \
  --download-origin https://download.miofelix.com \
  --prefix bucea-library-seat \
  --dry-run
```

`--dry-run` 只验证输入并输出计划，不访问 R2。实际上传由工作流把仓库 R2 secrets 映射为 `AWS_ACCESS_KEY_ID`、`AWS_SECRET_ACCESS_KEY`，不在命令行传递密钥。

## 上线验证

对下载域名执行 GET / HEAD，检查清单 JSON 的包名、版本、通用 APK URL、字节数和摘要；下载通用包和各分架构包，核对 `SHA256SUMS`。首段、续段和末段 Range 应返回 206 且字节正确，条件请求应符合实际返回的 ETag / Last-Modified。检查响应没有 3xx、登录页或 HTML 挑战页。

抽样 Range 和 HEAD 不等于完整下载；完整文件校验也不等于 Android 安装成功。新架构的公网检查、确认下载、断点续传与系统安装需要分别记录实际结果，不能复用历史树莓派验收作为 R2 上线结果。

## Android 行为

更新模块与校园账号模块独立。固定清单使用新建的 HTTPS OkHttp 客户端，没有 Cookie、认证、缓存和重定向。自动检查按最近尝试时间限为每 24 小时一次，失败不打扰；手动检查始终可用，单次并发检查合并，最近尝试与成功时间分别持久保存。

手动入口位于设置页顶部的“应用更新”卡片，按钮为“手动检查更新”。检查中显示进度并暂时禁止重复点击，结束后显示最新版本、可用更新或失败信息。检查无需连接校园网，也不会直接下载 APK；发现更新后仍需用户确认。

提示在前台、业务不忙、业务对话框全部关闭、座位号输入结束且停止触摸 1.5 秒后出现。它显示版本、测试版标记、说明、大小、“下载更新”和“稍后”。稍后暂停该 `versionCode` 的自动提示 24 小时，手动检查可以重新打开。

确认后用 DownloadManager 创建唯一任务，允许 Wi-Fi 和移动数据，禁止漫游。系统只显示下载中的通知，完成入口由应用在校验后提供；在支持的系统上隐藏公共下载列表中的任务。确认的完整清单先原子保存，再创建系统下载；崩溃时按专用文件名恢复任务。重启不会自动创建新下载。任务失败后由用户重试，仍下载已确认版本；取消会移除系统任务和临时文件。

下载成功后复制到内部的 `files/updates/install/`，原子保存前验证大小、摘要、实际包名、版本代码、显示版本、最低 SDK、关闭调试、通用 APK 及当前签名证书集合。创建下载和复制前检查空间。当前签名必须完全一致，签名轮换另行设计。

用户点击“安装更新”后再次校验文件。如果尚未允许安装未知来源，打开该应用的授权页；返回后需再次点击安装。通过只暴露 `updates/install/` 的非导出 FileProvider 调用系统安装器。下载完成、进程重启、授权返回都不会自动启动安装。只有实际已安装 `versionCode` 达到目标才清理任务；取消安装后保留已校验文件供再次点击。

DownloadManager 无法逐跳限制重定向，所以初始 URL 严格限定本项目固定 HTTPS 地址，落地文件仍必须通过摘要及 APK 签名校验。
