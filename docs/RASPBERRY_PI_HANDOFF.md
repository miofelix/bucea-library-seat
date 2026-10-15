# 从 MacBook 接续到树莓派

## 接续范围

截至 2026-10-15，MacBook 已发布 **v1.0.0 / code 14** 正式版，完成构建、144 项设备测试及通用/ARM64 包覆盖安装核对。包含通用包、四种分架构包和两份元数据。树莓派本次部署按用户要求暂缓；其 Tailscale 节点密钥过期，已知 SSH 路径不可达，需先恢复访问。上次确认的镜像版本为 0.3.7 / code 12。详细证据见 [ADB_ACCEPTANCE_1.0.0.md](ADB_ACCEPTANCE_1.0.0.md)。

树莓派只部署独立 Python HTTP 服务和 GitHub Release 同步定时器，不需要 JDK、Gradle、Android SDK、签名密钥或额外 Web 服务器。恢复访问后更新当前同步器，使其接受五包元数据和五行校验文件；它仍只缓存通用 APK。不要重新发布 1.0.0 或替换公开附件。

**域名和 CF Tunnel 由用户配置。** 部署任务只负责把服务运行在树莓派端口上，并报告实际监听地址、端口和验证结果。Android 和公开清单使用固定域名 `https://bucea-library-seat.miofelix.com`。

以下命令保留首次部署参考。现有服务已运行时，应先检查实际用户、目录和 unit，再只更新本应用脚本；不要重复创建服务或改动其他站点。旧 Release 缺少 `update.json` 会跳过，无完整源且无缓存时返回 503。

## 把代码带过去

可将已提交的代码推送后在树莓派获取，或直接复制 MacBook 上的 `build/updates-handoff.tar.gz`，解压到单独目录。交接包包含 HTTP 服务、同步器、systemd 配置、Python 测试、文档，以及相对上游基础提交的完整源码补丁；不含 APK、Token 或签名密钥。本地已提交但尚未推送的实现也包含在补丁中。

只部署服务器时直接使用包中的脚本。需要审查完整源码时，在干净的仓库中切到 `BASE_COMMIT.txt` 指定的基础提交；补丁非空时先 `git apply --check updates-source.patch`，再应用补丁。重新生成交接包是在 MacBook 上运行 `python3 tools/make_update_handoff.py`。

## 本地运行

要求 Python 3.9+，无需安装 Python 依赖。默认监听 `0.0.0.0:9080`：

```sh
python3 tools/update_server.py --root /var/lib/bucea-updates/public --listen 0.0.0.0 --port 9080
```

如果 9080 已被占用，选择其他可用端口并将最终端口告诉用户。服务接受 Tunnel 保留的公网 Host 头，提供 `/updates/latest.json` 和 `/updates/apks/<资产ID>.apk`，其余路径返回 404。只有 GET / HEAD 可读取文件。

## 部署 systemd 服务

先检查树莓派的 Python 版本、磁盘、端口占用及同名应用服务。只更新本应用的服务文件，保留现有站点和其他服务。在代码所在目录执行以下示例，账号已存在时跳过创建：

```sh
sudo useradd --system --home-dir /var/lib/bucea-updates --shell /usr/sbin/nologin bucea-updates
sudo install -d -m 0755 /opt/bucea-library/tools
sudo install -m 0644 tools/update_mirror.py tools/update_server.py /opt/bucea-library/tools/
sudo install -d -o bucea-updates -g bucea-updates -m 0755 /var/lib/bucea-updates/public/updates/apks
sudo install -m 0644 deploy/updates/bucea-update-mirror.service /etc/systemd/system/
sudo install -m 0644 deploy/updates/bucea-update-mirror.timer /etc/systemd/system/
sudo install -m 0644 deploy/updates/bucea-updates-http.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now bucea-updates-http.service
sudo systemctl start bucea-update-mirror.service
sudo systemctl enable --now bucea-update-mirror.timer
```

同步器以 `bucea-updates` 账号运行；HTTP 服务通过 systemd 的 `DynamicUser` 使用独立账号，仅读取公开文件。HTTP 服务不依赖首次同步成功，即使缓存目录为空也能启动并对清单返回 503。

GitHub token 可选，用于避免公开 API 的每小时限额。需要时将 `deploy/updates/bucea-updates.env.example` 复制为 `/etc/bucea-updates.env`，编辑值并设 root 所有、0600 权限，不要输出 token。HTTP 服务不读取这个文件。

修改监听地址或端口时，将 `deploy/updates/bucea-updates-http.env.example` 复制为 `/etc/bucea-updates-http.env`，修改 `UPDATE_LISTEN` / `UPDATE_PORT` 并重启 `bucea-updates-http.service`。

## 验证与回报

执行 `python3 -m unittest discover -s tools/tests -v`，所有 HTTP 测试直接启动 Python 服务，无额外依赖或跳过。交接包包含镜像和 HTTP 测试，完整源码仓库还包含打包及发布测试。

```sh
systemctl status bucea-updates-http.service bucea-update-mirror.timer bucea-update-mirror.service
journalctl -u bucea-updates-http.service -u bucea-update-mirror.service -n 50 --no-pager
curl -i http://127.0.0.1:9080/updates/latest.json
```

上游全是旧版时清单应返回 503；HTTP 服务和 timer 必须 active，oneshot 同步服务执行后 inactive 是正常现象。GitHub 限流/网络故障应保留已有公开文件。

首个完整 Release 发布后，手动启动同步服务，验证清单版本、预发布标识、下载文件的大小和摘要。APK 支持 `Range: bytes=0-1023`，要求 206 和正确 `Content-Range`；使用 ETag 重新验证要求 304。重启本应用 HTTP 服务和同步器后，旧资产仍可下载和续传。用隔离测试检查撤回、故障和清理，不修改真实 GitHub Release。

部署完成后把树莓派可访问的 HTTP 地址和端口告诉用户，由用户将域名转发到该端口。用户配置完成后，只请求公开 URL 验证：

```sh
curl -i https://bucea-library-seat.miofelix.com/updates/latest.json
```

## 可以直接粘贴的接续提示词

> 请阅读 docs/RASPBERRY_PI_HANDOFF.md、docs/UPDATES.md 和 docs/UPDATE_VALIDATION.md，然后在树莓派实际部署独立 Python HTTP 更新服务和 GitHub Release 同步定时器。默认端口 9080，如有占用选其他可用端口并告诉我。域名和 CF Tunnel 由我配置，不要安装或配置其他 Web 服务器，不要修改我的 Tunnel。你只需验证本地服务、Range、ETag、503、重启续传和同步故障保留，最后报告可供 Tunnel 转发的 HTTP 地址与端口；我配置域名后再请求固定地址 https://bucea-library-seat.miofelix.com/updates/latest.json 验证。树莓派不构建、签名或发布 APK。没有完整 Release 时 503 属于预期。当前无法连接 ADB，不做或声称通过设备测试，也不要修改真实 GitHub Release 做故障测试。
