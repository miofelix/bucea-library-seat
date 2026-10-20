# Cloudflare 更新分发

Android APK 在保存原签名密钥的开发机上构建、验证并发布到 GitHub Releases。GitHub Actions 从完整 Release 读取成品，通过 `tools/publish_cloudflare.py` 校验并上传 Cloudflare R2；官网由 Cloudflare Pages 托管，并同步部署 GitHub Pages 镜像。服务器同步器、独立 HTTP 服务和 systemd 配置已从仓库移除，当前分发流程无需树莓派。

官网使用 <https://library.miofelix.com/>，GitHub Pages 镜像为 <https://miofelix.github.io/bucea-library-seat/>。APK、应用更新与官网元数据均由共享 R2 bucket 的 `download.miofelix.com` 自定义域名提供，本项目统一使用 `bucea-library-seat/` 前缀：

| 用途                    | 地址                                                                          |
| ----------------------- | ----------------------------------------------------------------------------- |
| Android 更新清单        | `https://download.miofelix.com/bucea-library-seat/updates/latest.json`        |
| 官网与 Android APK 下载 | `https://download.miofelix.com/bucea-library-seat/releases/<tag>/<APK文件名>` |
| 官网最新下载元数据      | `https://download.miofelix.com/bucea-library-seat/releases/latest.json`       |

清单和 APK 直接返回内容，不经过官网跳转；正常 GET 返回 200，Range 请求返回 206。Cloudflare Pages 与 GitHub Pages 仅托管同一份网页产物，两站的 APK 均留在 R2。

R2 和官网已于 2026-10-16 启用。bucket 为 `miofelix-software-releases`，`download.miofelix.com` 自定义域名绑定处于活动状态。官网使用 Direct Upload Pages 项目 `bucea-library-seat-website`，生产分支为 `main`，不连接 Git 仓库；`library.miofelix.com` 已活动且启用 SSL。三项仓库 variables 和三项 secrets 已配置，首次上传、完整下载与官网上线结果见下文验证记录。官网源码保留 GitHub Release 备用下载入口；正式构建由 R2 元数据生成下载链接。此前服务的验收保留在 [UPDATE_VALIDATION.md](UPDATE_VALIDATION.md)，其中旧服务器与端口记录均属历史。

`1.0.3 / versionCode 17` 包含图书馆 Wi-Fi 签到与续约修复、紧凑预约信息和全部预约座位定位，发行说明见 [releases/1.0.3.md](releases/1.0.3.md)，本次构建、设备与发布验证见 [RELEASE_VALIDATION_1.0.3.md](RELEASE_VALIDATION_1.0.3.md)。

`1.0.2 / versionCode 16` 已正式发布并同步 R2，包含预约状态、字母学号登录和北京时间处理修复。五包完整下载、SHA-256、清单、官网和 GitHub Pages 验证均通过，最终结果见 [RELEASE_VALIDATION_1.0.2.md](RELEASE_VALIDATION_1.0.2.md)。上一版 `1.0.1` 的构建、发布与新来源验收见 [RELEASE_VALIDATION_1.0.1.md](RELEASE_VALIDATION_1.0.1.md)。

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
3. 官网最新元数据写入 `bucea-library-seat/releases/latest.json`，本地同时输出 `<website-output>/release.json`。两站使用已选中发布的版本、大小和下载地址，部署流程见 [WEBSITE.md](WEBSITE.md)。

`versionCode` 只递增，正式版与测试版共用同一更新流。重复同步相同发布应保持对象内容一致，不能覆盖成不同字节。较旧发布不能将 `latest.json` 降级，上传中断时保留上一份成功清单。相同 tag 的同步串行执行，不同 tag 可并行上传；R2 使用 ETag 条件写入（CAS）防止并发发布降低清单版本。网页部署任务另用共享的 `cloudflare-production` 队列串行执行，获得部署位置后重新读取最新元数据，构建并验证一次 `dist/website` 后将同一产物部署到 Cloudflare Pages 和 GitHub Pages，避免排队期间的新发布被旧页面覆盖。`main` 源码更新与手动网页部署也使用该队列和双站流程。历史版本 APK 保持不可变。

APK 使用 `public, max-age=31536000, immutable`，清单和可变下载元数据使用 `no-store, max-age=0`。R2 原生提供 HEAD、ETag、条件请求和 Range；APK 内容的可信依据是发布元数据中的 SHA-256，不把 R2 ETag 当作 SHA-256。Android 安装前仍执行摘要和签名验证。

## 一次性配置

### 1. 创建 R2 bucket

1. 打开 Cloudflare 控制台，进入保存 `miofelix.com` 的账户。记录账户首页或右侧 API 信息中的 **Account ID**。
2. 进入 **存储和数据库 → R2 对象存储（R2 Object Storage）**。首次使用时按控制台提示启用 R2。
3. 点击 **创建存储桶（Create bucket）**，建议名称为 `miofelix-software-releases`，保留默认标准存储设置后创建。这个 bucket 可存放其他项目，本项目全部对象统一使用 `bucea-library-seat/` 前缀。
4. 在 bucket 的 **设置 → 自定义域（Settings → Custom Domains）** 中连接 `download.miofelix.com`，按提示完成 DNS 与证书配置。域名直接映射到 bucket 根目录，项目名前缀属于对象键。

R2 S3 API endpoint 为 `https://<CLOUDFLARE_ACCOUNT_ID>.r2.cloudflarestorage.com`，不是公开下载域名。

### 2. 创建 R2 对象读写凭据

1. 在 R2 概览中打开 **管理 R2 API 令牌（Manage R2 API Tokens）**，创建账户级令牌，名称使用 **BUCEA Library Seat GitHub Actions R2 Release Publisher**。
2. 权限选择 **对象读和写（Object Read & Write）**；范围选择 **仅应用于特定存储桶（Apply to specific buckets only）**，只勾选上一步的 bucket。
3. 创建后保存 **Access Key ID** 和 **Secret Access Key**。它们分别用于 GitHub 的 `CLOUDFLARE_R2_ACCESS_KEY_ID` 和 `CLOUDFLARE_R2_SECRET_ACCESS_KEY`；Secret Access Key 只在创建完成时显示。
4. 不将 R2 S3 凭据填写为 Pages API token，也不把凭据写进仓库文件或命令行参数。

### 3. 创建 Pages 项目和账户级部署 token

1. 在 **管理帐户 → 帐户 API 令牌 → 创建令牌** 中创建自定义令牌，名称使用 **BUCEA Library Seat GitHub Actions Pages Production Deployment**。使用账户级令牌，使自动化凭据由账户管理。
2. 自定义策略选择托管官网的目标账户（本次为 `fanyu`），权限选择 **Pages → Write**；创建前的审核摘要应显示 **Pages Write**。
3. 创建后将 token 保存为 GitHub 的 `CLOUDFLARE_PAGES_DEPLOY_API_TOKEN`。它负责 Pages 部署，R2 对象上传仍使用上一步的 S3 凭据。保存后用令牌验证接口检查令牌有效性，再运行发布工作流。
4. 通过控制台或 Pages API 创建 **上传资源（Direct Upload）** 类型的 `bucea-library-seat-website`，生产分支使用 `main`。不连接 Git 仓库，后续部署由 GitHub Actions 的 Wrangler 完成。项目名称是项目标识，不是 `library.miofelix.com` 域名。
5. 首次成功部署后，在项目的 **自定义域（Custom domains）** 中添加 `library.miofelix.com`，等待 DNS 与证书状态变为活动。

### 4. 配置 GitHub Actions

打开仓库 **Settings → Secrets and variables → Actions**。在 **Variables** 页签创建以下 repository variables：

| 名称                                 | 值                           |
| ------------------------------------ | ---------------------------- |
| `CLOUDFLARE_ACCOUNT_ID`              | Cloudflare Account ID        |
| `CLOUDFLARE_R2_RELEASES_BUCKET_NAME` | `miofelix-software-releases` |
| `CLOUDFLARE_PAGES_PROJECT_NAME`      | `bucea-library-seat-website` |

在 **Secrets** 页签创建以下 repository secrets：

| 名称                                | 值                                |
| ----------------------------------- | --------------------------------- |
| `CLOUDFLARE_R2_ACCESS_KEY_ID`       | R2 token 的 Access Key ID         |
| `CLOUDFLARE_R2_SECRET_ACCESS_KEY`   | R2 token 的 Secret Access Key     |
| `CLOUDFLARE_PAGES_DEPLOY_API_TOKEN` | 独立的 Cloudflare Pages API token |

工作流将上述仓库配置映射到 Wrangler 所需的 `CLOUDFLARE_API_TOKEN` 和 R2 S3 客户端所需的 `AWS_ACCESS_KEY_ID`、`AWS_SECRET_ACCESS_KEY`。这些是工具内部使用的环境变量，无需另建同名 GitHub secrets。

GitHub 的 `GITHUB_TOKEN` 由 Actions 提供，用于读取 Release 和部署 GitHub Pages，无需手动创建。仓库 **Settings → Pages** 的构建来源选择 **GitHub Actions**，网页部署 job 授予 `contents: read`、`pages: write`、`id-token: write` 权限，并使用 `github-pages` environment；该环境允许 `main` 分支和 `v*` 标签部署，以支持 Release 事件的标签 OIDC 引用。GitHub Pages 不配置自定义域名或 `CNAME`，官网域名与 canonical 继续使用 Cloudflare 主站。Android 签名密钥不上传到 Actions 或 Cloudflare。

### 5. 首次同步并部署官网

R2 下载域名和证书应已生效，三项 variables 与三项 secrets 均已配置。Cloudflare Pages 使用不连接 Git 仓库的 Direct Upload 项目，两站由 Actions 统一部署，详见 [WEBSITE.md](WEBSITE.md)。

1. 打开仓库 **Actions → Publish release and deploy websites → Run workflow**，选择 `main`，填写已发布的 tag（例如 `v1.0.0`），同步已有 Release。
2. 检查 `publish` job 的附件校验、R2 上传和公开地址检查。对象全部位于 `bucea-library-seat/` 下，包括 `releases/<tag>/<name>`、`updates/latest.json` 和 `releases/latest.json`；公开检查失败会阻止后续官网部署。
3. `publish` 成功后，`website` job 自动读取最新 R2 元数据，构建并检查下载地址，再将同一产物部署到 `bucea-library-seat-website` 和 GitHub Pages。首次 Cloudflare 部署后绑定官网自定义域名，后续发布直接同步两站。
4. 打开 <https://library.miofelix.com/> 和 <https://miofelix.github.io/bucea-library-seat/>，核对两站版本、大小、五种下载链接及开源许可页面，完整下载 APK 并核对摘要；R2 URL 直接返回内容，不重定向到 `r2.dev`、S3 endpoint 或官网。
5. 对包含新地址的 `1.0.1` 构建执行真实 Android 更新检查与下载验收。首次同步 `v1.0.0` 只验证分发，不能证明该旧 APK 已切换更新地址。

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

### 2026-10-16 首次上线记录

| 验证项           | 结果                                                                                                                                                                                        |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 仓库配置         | 三项 variables 与三项 secrets 已配置；R2 令牌仅有指定 bucket 的 Object Read & Write，Pages 令牌为账户级 Pages Write。                                                                       |
| Release 同步     | [`38788641625`](https://github.com/miofelix/bucea-library-seat/actions/runs/38788641625) 成功；`publish` 与 `website` 两个 job 均通过，Pages 部署 9 个文件。                                |
| Pages 部署       | [本次部署](https://2a235de8.bucea-library-seat-website.pages.dev) 成功；官网显示 `v1.0.0`，五个 APK 下载链接均指向 `download.miofelix.com/bucea-library-seat/releases/v1.0.0/`。            |
| 官网域名         | `library.miofelix.com` 状态活动且 SSL 已启用；自动创建的 CNAME 指向 `bucea-library-seat-website.pages.dev`，公开 GET 返回 `200`。浏览器重载后，图标与座位布局 SVG 均返回 `200` 且正常加载。 |
| 清单             | 公开清单为 `versionCode: 14`、`versionName: 1.0.0`、`packageName: cn.edu.bucea.library`，元数据中的五个 APK 摘要均与 GitHub Release 一致。                                                  |
| 完整下载         | 从公开下载域名完整 GET 五个 APK，共 `192704714` 字节；各包字节数与 SHA-256 全部匹配发布元数据。                                                                                             |
| HEAD 与 Range    | 五个 APK 的 HEAD 长度正确；首段 Range 返回 `206` 和 `1024` 字节，内容含正确 ZIP 魔数。                                                                                                      |
| Android 更新事务 | 本次未执行来自 R2 的真实 Android 更新下载与安装；现有 `v1.0.0` APK 的内置更新地址未改变。                                                                                                   |

## Android 行为

更新模块与校园账号模块独立。固定清单使用新建的 HTTPS OkHttp 客户端，没有 Cookie、认证、缓存和重定向。自动检查按最近尝试时间限为每 24 小时一次，失败不打扰；手动检查始终可用，单次并发检查合并，最近尝试与成功时间分别持久保存。

手动入口位于设置页顶部的“应用更新”卡片，按钮为“手动检查更新”。检查中显示进度并暂时禁止重复点击，结束后显示最新版本、可用更新或失败信息。检查无需连接校园网，也不会直接下载 APK；发现更新后仍需用户确认。

提示在前台、业务不忙、业务对话框全部关闭、座位号输入结束且停止触摸 1.5 秒后出现。它显示版本、测试版标记、说明、大小、“下载更新”和“稍后”。稍后暂停该 `versionCode` 的自动提示 24 小时，手动检查可以重新打开。

确认后用 DownloadManager 创建唯一任务，允许 Wi-Fi 和移动数据，禁止漫游。系统只显示下载中的通知，完成入口由应用在校验后提供；在支持的系统上隐藏公共下载列表中的任务。确认的完整清单先原子保存，再创建系统下载；崩溃时按专用文件名恢复任务。重启不会自动创建新下载。任务失败后由用户重试，仍下载已确认版本；取消会移除系统任务和临时文件。

下载成功后复制到内部的 `files/updates/install/`，原子保存前验证大小、摘要、实际包名、版本代码、显示版本、最低 SDK、关闭调试、通用 APK 及当前签名证书集合。创建下载和复制前检查空间。当前签名必须完全一致，签名轮换另行设计。

用户点击“安装更新”后再次校验文件。如果尚未允许安装未知来源，打开该应用的授权页；返回后需再次点击安装。通过只暴露 `updates/install/` 的非导出 FileProvider 调用系统安装器。下载完成、进程重启、授权返回都不会自动启动安装。只有实际已安装 `versionCode` 达到目标才清理任务；取消安装后保留已校验文件供再次点击。

DownloadManager 无法逐跳限制重定向，所以初始 URL 严格限定本项目固定 HTTPS 地址，落地文件仍必须通过摘要及 APK 签名校验。
