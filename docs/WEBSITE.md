# 宣传网页

网站地址：<https://miofelix.github.io/bucea-library-seat/>。

网页源码和公开资源位于 [`website/`](../website/)，使用原生 HTML、CSS 和 JavaScript，无需安装依赖或运行构建工具。

## 本地预览

在仓库根目录运行：

```sh
python3 -m http.server 8000 --directory website
```

然后打开 <http://localhost:8000/>。修改后刷新浏览器即可查看，按 `Ctrl+C` 停止服务。

## 自动部署

GitHub Pages 使用 GitHub Actions 发布。[`pages.yml`](../.github/workflows/pages.yml) 会在 `main` 分支的 `website/` 内容或该工作流文件发生变化后自动部署。也可以在仓库的 Actions → Deploy promotional website → Run workflow 手动发布。

工作流直接上传 `website/`，再部署到 `github-pages` 环境。首次启用时，仓库 Settings → Pages → Build and deployment → Source 应选择 **GitHub Actions**。部署进度和失败原因可在 Actions 中查看。

## 内容维护

- 发布新版 APK 后，同步更新网页中的版本号、包大小和下载地址。下载按钮使用 GitHub Release 的完整版本地址，例如 `https://github.com/miofelix/bucea-library-seat/releases/download/v1.0.0/BUCEA-Library-Seat-v1.0.0-arm64-v8a.apk`；通用包为同目录下的 `BUCEA-Library-Seat-v1.0.0.apk`。确认这些资源已上传，再发布页面。
- 推荐 ARM64 包供大多数现代手机使用，并保留通用包和完整发行页入口。安装要求、校园网络和馆内 Wi-Fi 说明应与 README 保持一致。
- 网页中的手机界面使用 HTML/CSS 示意 UI，按当前 Android 版本的页面结构维护，不直接嵌入真实账号截图，也不展示实时数据。更新 App UI 时，同步检查示意中的页面顺序、标题、分段标签、筛选条件、底部导航和设置条目。
- 页面资源引用使用相对路径，以兼容 `/bucea-library-seat/` 项目路径。发布前在桌面和手机宽度检查布局，并验证示意 UI 与当前 App 结构、下载链接、发行页和反馈链接。

APK 继续由 GitHub Releases 提供，网页目录只保存介绍内容和展示资源。
