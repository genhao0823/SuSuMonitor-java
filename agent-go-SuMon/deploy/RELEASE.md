# Agent 远程发布与一键安装

## 正式一键命令

正式环境必须使用 HTTPS 域名。新 Linux x86_64 主机执行：

```bash
curl --fail --silent --show-error --location https://monitor.example.com/agent/install-agent.sh | sudo -E bash
```

脚本会通过 `/dev/tty` 隐藏读取管理员用户名、密码和 Agent 名称。密码不会作为命令行参数传递，也不会输出到日志。管理员账号必须具有 `admin` 角色。

可选的非敏感环境变量：

```bash
curl --fail --silent --show-error --location https://monitor.example.com/agent/install-agent.sh | \
  sudo -E env AGENT_VERSION=1.0.0 AGENT_NAME=prod-node-01 bash
```

已有主机更新 Agent 时，可传入已有 `AGENT_SERVER_ID`；脚本会读取现有 `/etc/susumonitor/agent.env` 中的 token，不会自动 rotate 正在使用的 token：

```bash
curl --fail --silent --show-error --location https://monitor.example.com/agent/install-agent.sh | \
  sudo -E env AGENT_VERSION=1.0.0 AGENT_SERVER_ID=123 bash
```

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

生产环境已启用 HTTPS/WSS（域名 `genhaosan.online`）。一键安装使用 HTTPS 地址：

```bash
curl --fail --silent --show-error --location https://genhaosan.online/agent/install-agent.sh | \
  sudo -E env \
    AGENT_BASE_URL=https://genhaosan.online \
    AGENT_TERMINAL_ENABLED=true \
    AGENT_VERSION=1.0.0 \
    bash
```

> 历史临时 IPv4 明文模式（`AGENT_BASE_URL=http://82.156.245.102` + `AGENT_ALLOW_INSECURE_HTTP=true`）仅用于授权测试 IP，生产勿用；脚本已自动把 `https:` 转为 `wss:` 写入 agent 配置。

执行前提是云端已发布以下文件，并且新机器是 Linux x86_64：

```text
/agent/install-agent.sh
/agent/releases/1.0.0/susumonitor-agent-linux-amd64
/agent/releases/1.0.0/susumonitor-agent-linux-amd64.sha256
/agent/releases/1.0.0/susumonitor-agent.service
/agent/releases/1.0.0/susumonitor-agent.logrotate
```

脚本会明确提示：管理员密码、JWT 请求和 Agent 二进制都通过明文 HTTP 传输。只应在当前受控联调阶段使用；不能用于不受信任网络或生产环境。域名和 HTTPS 配置完成后，去掉 `AGENT_ALLOW_INSECURE_HTTP=true`，改用正式 HTTPS 命令。
