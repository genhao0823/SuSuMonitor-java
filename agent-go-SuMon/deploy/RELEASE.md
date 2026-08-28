# Agent 远程发布与一键安装

## 正式一键命令

正式环境必须使用 HTTPS 域名。新 Linux x86_64 主机执行：

```bash
curl --fail --silent --show-error --location https://SERVER_IP_OR_DOMAIN/agent/install-agent.sh | \
  sudo -E env AGENT_BASE_URL=https://SERVER_IP_OR_DOMAIN bash
```

脚本会通过 `/dev/tty` 隐藏读取管理员用户名、密码和 Agent 名称。密码不会作为命令行参数传递，也不会输出到日志。管理员账号必须具有 `admin` 角色。

可选的非敏感环境变量：

```bash
curl --fail --silent --show-error --location https://SERVER_IP_OR_DOMAIN/agent/install-agent.sh | \
  sudo -E env AGENT_BASE_URL=https://SERVER_IP_OR_DOMAIN AGENT_VERSION=1.0.0 AGENT_NAME=prod-node-01 bash
```

已有主机更新 Agent 时，可传入已有 `AGENT_SERVER_ID`；脚本会读取现有 `/etc/susumonitor/agent.env` 中的 token，不会自动 rotate 正在使用的 token：

```bash
curl --fail --silent --show-error --location https://SERVER_IP_OR_DOMAIN/agent/install-agent.sh | \
  sudo -E env AGENT_BASE_URL=https://SERVER_IP_OR_DOMAIN AGENT_VERSION=1.0.0 AGENT_SERVER_ID=123 bash
```

> 生产安装应显式传入 `AGENT_BASE_URL=https://SERVER_IP_OR_DOMAIN`；脚本会将 HTTPS origin 转为对应的 `wss://SERVER_IP_OR_DOMAIN` 写入 Agent 配置。不要依赖发布环境的脚本默认域名。

## 发布目录

由发布机在 Agent 工程目录执行：

```bash
make build-linux
VERSION=1.0.0
BASE=/srv/susumonitor-agent/agent/releases/${VERSION}
install -d "${BASE}"
install -m 0755 bin/susumonitor-agent-linux-amd64 "${BASE}/susumonitor-agent-linux-amd64"
sha256sum "${BASE}/susumonitor-agent-linux-amd64" > "${BASE}/susumonitor-agent-linux-amd64.sha256"
install -m 0644 deploy/susumonitor-agent.service "${BASE}/susumonitor-agent.service"
install -m 0644 deploy/logrotate.conf "${BASE}/susumonitor-agent.logrotate"
install -m 0755 deploy/install-agent.sh /srv/susumonitor-agent/agent/install-agent.sh
```

Nginx 静态目录应提供：

```text
/agent/install-agent.sh
/agent/releases/<version>/susumonitor-agent-linux-amd64
/agent/releases/<version>/susumonitor-agent-linux-amd64.sha256
/agent/releases/<version>/susumonitor-agent.service
/agent/releases/<version>/susumonitor-agent.logrotate
```

Nginx 必须只通过 HTTPS 发布这些文件。安装脚本拒绝 HTTP、带路径的 `AGENT_BASE_URL` 和未知字符的版本号，并验证下载二进制的 SHA-256 与 ELF 架构。

## 安装行为

- 新机器自动登录、创建 server、注册 Agent token、安装二进制并启动 systemd 服务。
- 创建 server 使用 Agent 模式 SSH 占位字段；不会使用目标机 SSH 凭据。
- 已存在 `/etc/susumonitor/agent.env` 时保留现有配置，不覆盖 token。
- 默认关闭 Web 终端，可在安装后由管理员明确修改配置并重启。
- 配置文件权限为 `0600`，日志目录为 `/var/log/susumonitor`。
- 新版本下载、校验失败或服务启动失败时，脚本尝试恢复旧二进制、service 和配置。
- `AGENT_ROTATE=true` 目前不作为自动轮换开关；生产环境轮换 token 应先显式调用后端 rotate 接口，再更新配置，避免误断开正在运行的 Agent。

## 当前云端限制

生产环境必须启用 HTTPS/WSS。一键安装使用实际受控域名的 HTTPS 地址：

```bash
curl --fail --silent --show-error --location https://SERVER_IP_OR_DOMAIN/agent/install-agent.sh | \
  sudo -E env \
    AGENT_BASE_URL=https://SERVER_IP_OR_DOMAIN \
    AGENT_TERMINAL_ENABLED=true \
    AGENT_VERSION=1.0.0 \
    bash
```

> **历史记录（2026-07-31，禁止执行）**：曾存在临时 IPv4 明文模式（`AGENT_BASE_URL=http://SERVER_IP_OR_DOMAIN` + `AGENT_ALLOW_INSECURE_HTTP=true`），仅用于受控联调；公网生产禁止设置 `AGENT_ALLOW_INSECURE_HTTP=true`。正式路径统一使用 HTTPS/WSS。

执行前提是云端已发布以下文件，并且新机器是 Linux x86_64：

```text
/agent/install-agent.sh
/agent/releases/1.0.0/susumonitor-agent-linux-amd64
/agent/releases/1.0.0/susumonitor-agent-linux-amd64.sha256
/agent/releases/1.0.0/susumonitor-agent.service
/agent/releases/1.0.0/susumonitor-agent.logrotate
```

**历史记录（2026-07-31，禁止执行）**：临时明文联调会使管理员密码、JWT 请求和 Agent 下载暴露在 HTTP 传输中。该模式不得用于不受信任网络或生产环境；生产部署必须去掉 `AGENT_ALLOW_INSECURE_HTTP=true` 并使用正式 HTTPS 命令。
