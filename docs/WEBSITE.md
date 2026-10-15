# 宣传网页

正式官网：<https://library.miofelix.com/>，已于 2026-10-16 启用 R2 下载，生产部署使用 Cloudflare Pages 项目 `bucea-library-seat-website`。GitHub Actions 用于构建并部署官网，Cloudflare R2 提供 APK 与更新清单分发；签名发布成品仍保存在 GitHub Releases。首次上线和五种 APK 完整下载验收见 [UPDATES.md](UPDATES.md)。

网页源码和公开资源位于 [`website/`](../website/)，使用原生 HTML、CSS 和 JavaScript。发布构建读取已验证的下载元数据并生成页面，不编译 Android 或复制 APK 到 Pages。

## 本地预览

直接预览源码：

```sh
python3 -m http.server 8000 --directory website
```

打开 <http://localhost:8000/>，修改后刷新。按 `Ctrl+C` 停止服务。该方式展示源码中的默认内容，保留 GitHub Release 下载入口。正式部署使用构建生成的版本、大小与 R2 链接。

## 使用 R2 元数据构建

R2 bucket、token 和仓库 Actions 已配置，下载元数据已公开。正式构建从以下地址读取 JSON：

`https://download.miofelix.com/bucea-library-seat/releases/latest.json`

在仓库根目录运行：

```sh
python3 .github/scripts/build_website.py \
  --metadata-url https://download.miofelix.com/bucea-library-seat/releases/latest.json \
  --output dist/website
python3 -m http.server 8000 --directory dist/website
```

也可使用 R2 发布任务输出的本地元数据：

```sh
python3 .github/scripts/build_website.py \
  --metadata build/website-release/release.json \
  --output dist/website
```

## Cloudflare Pages 设置

Pages 项目 `bucea-library-seat-website` 使用 **上传资源（Direct Upload）**，生产分支为 `main`，采用 **GitHub Actions 作为唯一生产部署入口**，不连接 Git 仓库。项目已绑定 `library.miofelix.com`，CNAME 指向 `bucea-library-seat-website.pages.dev`，域名处于活动状态且 SSL 已启用。

Actions 使用 Wrangler 上传 `dist/website` 到 `bucea-library-seat-website` 的 `main` 生产分支。对应构建参数为：

| 项目         | 值                                                                                                                                                    |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| 生产分支     | `main`                                                                                                                                                |
| 根目录       | 仓库根目录                                                                                                                                            |
| 构建命令     | `python3 .github/scripts/build_website.py --metadata-url https://download.miofelix.com/bucea-library-seat/releases/latest.json --output dist/website` |
| 构建输出目录 | `dist/website`                                                                                                                                        |

GitHub variable `CLOUDFLARE_PAGES_PROJECT_NAME` 填写 `bucea-library-seat-website`，不能填写官网域名。Account ID 填入 `CLOUDFLARE_ACCOUNT_ID`，独立的账户级 **Pages → Write** token 填入 secret `CLOUDFLARE_PAGES_DEPLOY_API_TOKEN`。R2 S3 Access Key 不能用作 Pages token。创建 token 与逐项填写步骤见 [UPDATES.md](UPDATES.md)。

## GitHub Actions 部署

[`pages.yml`](../.github/workflows/pages.yml) 的 **Build and deploy website** 工作流负责 `main` 分支源码更新与手动官网部署；[`release.yml`](../.github/workflows/release.yml) 的 **Publish release to Cloudflare** 工作流处理已发布 Release 和手动 tag 同步，先完成 R2 上传与公开检查，再使用最新下载元数据自动更新官网。

Pages 发布依赖正确的账户 ID、项目名称与 API token。`pages.yml` 检测这些设置缺失时只构建 GitHub 下载备用页面并上传供审查的 artifact，跳过 Cloudflare 部署；设置齐全时读取 R2 最新元数据、验证公开下载地址并部署。`release.yml` 则要求 R2 与 Pages 的全部配置齐全，先完成 R2 同步及公开检查，随后由独立的 `website` job 自动部署官网，无需首次同步后另行启用。

两份工作流的官网部署共享 `cloudflare-production` 队列，串行执行；Release 官网 job 等待获得部署位置后才重新读取 R2 最新元数据，减少并发发布覆盖新版本的可能。Direct Upload 项目不配置额外的 Git 自动生产部署。

工作流不再使用 GitHub Pages artifact 或 `github-pages` 环境。[首次生产部署](https://github.com/miofelix/bucea-library-seat/actions/runs/38788641625) 已成功，官网展示 `v1.0.0` 并提供五种 R2 APK 下载。后续每次发布仍需核对 Actions 结果、页面版本和下载链接。

## 内容维护

- 发布新版 APK 后，R2 发布任务生成版本、包大小和下载元数据，官网构建据此更新页面，避免手动维护多份版本号。下载按钮指向 `https://download.miofelix.com/bucea-library-seat/releases/<tag>/<name>`；保留 GitHub Release 页面作为发行说明与备用来源。确认 R2 对象和元数据可读后再部署页面。
- 现有 `v1.0.0` 已同步到 R2，其 APK 字节与内置更新地址不变；App 的新更新地址需后续构建发布后生效。本次未执行来自 R2 的真实 Android 更新下载与安装。
- 推荐 ARM64 包供大多数现代手机使用，并保留通用包；ARM32、x86 和 x86_64 通过下载区的紧凑链接提供，五种 APK 均直接从 R2 下载。GitHub 发行页保留备用下载与更新日志。安装要求、校园网络和馆内 Wi-Fi 说明应与 README 保持一致。
- 网页中的手机界面使用 HTML/CSS 示意 UI，按当前 Android 版本的页面结构维护，不直接嵌入真实账号截图，也不展示实时数据。更新 App UI 时，同步检查示意中的页面顺序、标题、分段标签、筛选条件、底部导航和设置条目。
- 首屏与下方图库共用同一套示意页面，覆盖馆内概况、各区域座位、自选座位、布局选座、我的图书馆、账号管理与设置。前两项均属于首页，区域视角展示首页滚动后的内容。页面内容在 `index.html`，切换和图库复用逻辑在 `script.js`，示意专用样式在 `preview.css`；图库通过等比缩放复用页面，避免两处展示不一致。
- 所有示意 UI 默认静态展示，`.mock-screen` 的 `data-preview-static` 属性禁用内部鼠标、触摸和键盘滚动，保留滚动容器与预设展示位置；不影响网页正常滚动、首屏外部页面切换或图库横向浏览。恢复内部滚动时移除该属性，并由 `showIllustration` 重新设置对应页面的焦点与说明。
- 装饰标记使用 SVG，不使用可能被 iOS 等系统显示为彩色 emoji 的文字符号。
- 布局选座示意按 App 直接进入区域的页面绘制，保留固定的选座标题和模式栏，依次展示区域名称、日期与馆层信息、地图/列表切换、地图及其下方的状态图例。地图资源 `assets/seat-layout-inner-ring.svg` 按二层内环区实际布局重新绘制第 9–14 行、第 3–9 列（从 0 开始）的 36 个座位，保留真实编号、相对坐标和过道；仅展示局部，放大座椅到约 30px 宽。椅子比例、编号、状态文字与配色遵循 `NativeRoomCanvas`；六种座位状态为合成示例，不代表实时可用情况。不添加实际布局中没有的桌子、窗、墙、入口等设施或标注，不展示未知状态、点选详情或真实截图。
- 页面资源引用使用相对路径，兼容官网根路径和本地预览。发布前在桌面和手机宽度检查布局，并验证示意 UI 与当前 App 结构、下载链接、发行页和反馈链接。

GitHub Releases 保存签名发布成品，Cloudflare R2 提供官网 APK 下载及 Android 更新清单；Pages 部署产物只包含网页与公开下载元数据，不包含 APK、凭据或签名密钥。
