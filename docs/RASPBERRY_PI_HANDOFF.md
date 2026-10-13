# 从 MacBook 接续到树莓派

## 合适的接续时机

MacBook 上代码、离线测试、release 构建和 Lint 完成后，就可以转到树莓派部署缓存服务。**现在已达到这个时机。** 不需要在树莓派安装 JDK、Gradle、Android SDK 或签名密钥。

当前没有执行 Git push、GitHub Release 发布或树莓派部署。旧 Release 没有 `update.json` 时会全部跳过，因此第一次部署返回 503 是预期状态。服务准备好后回 MacBook 发布包含三个附件的版本；没有 ADB 的设备验收仍单独待办。

## 把当前代码带过去

优先把审查后的代码提交并推送到你自己的分支，再在树莓派获取该分支。如果还没有推送，可将 MacBook 生成的 `build/updates-handoff.tar.gz` 拷贝过去，解压到单独目录。交接包包含运行服务所需的脚本、配置、Python 测试和这些文档，以及整个实现的源码补丁和基础提交 SHA；没有 APK、账号、Token 或签名密钥。

只部署服务器时可以直接从交接目录安装 `tools/update_mirror.py`。若需要在树莓派仓库审查完整实现，切到 `BASE_COMMIT.txt` 中的基础提交，然后先 `git apply --check updates-source.patch`，再应用补丁；补丁包含新增文件。

重新生成交接包：

```sh
python3 tools/make_update_handoff.py
```

## 部署顺序

先检查树莓派的发行版、磁盘空间、Python 版本、已有 Caddy 和 cloudflared 的运行方式，以及该域名当前 Tunnel ingress。备份现有配置，保留现有站点和服务。GitHub token 可选，用于避免公开 API 的每小时限额；不要在日志或回复中打印 token。

下面是使用系统 Caddy、`/opt/bucea-library` 和服务账号的示例，在代码所在目录执行。已有同名目录/服务时先比较再更新：

```sh
sudo useradd --system --home-dir /var/lib/bucea-updates --shell /usr/sbin/nologin bucea-updates
sudo install -d -m 0755 /opt/bucea-library/tools
sudo install -m 0644 tools/update_mirror.py /opt/bucea-library/tools/update_mirror.py
sudo install -d -o bucea-updates -g bucea-updates -m 0755 /var/lib/bucea-updates
sudo install -m 0644 deploy/updates/bucea-update-mirror.service /etc/systemd/system/
sudo install -m 0644 deploy/updates/bucea-update-mirror.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl start bucea-update-mirror.service
sudo systemctl enable --now bucea-update-mirror.timer
```

账号已存在时跳过创建。若配置 token，复制 `.env.example` 到 `/etc/bucea-updates.env`，编辑值并设 root 所有、0600 权限。同步器读取该环境文件，Caddy 不需要 token。

安装或沿用 Caddy，配置见 `deploy/updates/Caddyfile`。默认只监听 `http://127.0.0.1:9080`，静态根目录是 `/var/lib/bucea-updates/public`。可以把这个 site 加入已有 Caddy 配置，或将同样的三个 handle 合并到现有 site。先 `caddy validate`，成功后再 reload；不要直接覆盖已有 Caddyfile。

现有 CF Tunnel 的 ingress 中，将该域名 `/updates/*` 路径映射到这个 HTTP origin，并将这条规则放在该域名的通配路径前。例如：

```yaml
ingress:
  - hostname: bucea-library-seat.miofelix.com
    path: ^/updates/.*$
    service: http://127.0.0.1:9080
  # 后续沿用已有站点规则和最终兜底规则
```

如果 cloudflared 在容器中，`127.0.0.1` 指容器自身，需要按现有容器网络让它能够访问 Caddy，例如沿用已有代理或共享宿主机网络；使用宿主机其他接口地址时也要相应调整 Caddy 的 `bind`。不要只修改 Tunnel 地址。由 Cloudflare 托管的 Tunnel 在现有管理界面配置等效路径路由。清单必须绕过覆盖源站头的 CDN 强制缓存规则。

## 验证

在树莓派代码目录执行 `python3 -m unittest discover -s tools/tests -v`；安装了 Caddy 后，三个真实 HTTP 测试会自动运行。交接包只包含镜像及 HTTP 测试，完整源码仓库还包含打包和发布测试。检查：

```sh
systemctl status bucea-update-mirror.timer bucea-update-mirror.service
journalctl -u bucea-update-mirror.service -n 50 --no-pager
curl -i http://127.0.0.1:9080/updates/latest.json
curl -i https://bucea-library-seat.miofelix.com/updates/latest.json
```

上游全是旧版时两个入口应返回 503，含重新验证缓存头，且 APK 目录为空。GitHub 限流/网络故障应记录错误并保留已有公开文件；oneshot 服务执行后显示 inactive 是正常现象，timer 必须 active。

首次完整 Release 发布后，手动启动同步服务，确认清单为 200、`apk.url` 是固定域名资产 ID 地址，版本代码正确，测试版标记正确。下载文件应与公开清单和 GitHub 的 SHA-256、字节数一致。用 `curl -H 'Range: bytes=0-1023'` 请求 APK，要求 206 和正确 `Content-Range`；使用清单的 ETag 再请求，要求 304。

重启 Caddy 和同步服务，验证缓存仍可下载；停止 timer 并用隔离测试验证上游故障，不删除或修改真实 GitHub Release。确认旧资产 URL 在新版本切换后保持内容。没有完整 Release 时先完成本地 HTTP 回归，不把 503 标记为部署失败。

## 可以直接粘贴的接续提示词

> 我已从 MacBook 带来 bucea-library 的更新服务代码或交接包。请先阅读 docs/RASPBERRY_PI_HANDOFF.md、docs/UPDATES.md 和 docs/UPDATE_VALIDATION.md，检查当前树莓派环境及已有 Caddy / Cloudflare Tunnel 配置，然后实际部署并验证 GitHub Release 缓存服务。使用固定入口 https://bucea-library-seat.miofelix.com/updates/latest.json 和 /updates/apks/资产ID.apk；沿用我管理的 CF Tunnel。树莓派只同步、校验、缓存 APK，不构建、不签名、不发布 Android 版本。保留现有网站和服务，先备份、比较并验证配置，再启用 systemd 的十分钟同步 timer。没有完整 Release 时 503 是预期状态；完成本地 Range、ETag、503、原子切换、撤回、故障保留和清理测试，再检查公网路径。当前无法连接 ADB，不执行或声称通过设备测试，也不要修改真实 GitHub Release 来做故障测试。继续完成所有可在树莓派完成的工作，最后告诉我部署结果及何时回 MacBook 发布首个带 update.json 的版本。
