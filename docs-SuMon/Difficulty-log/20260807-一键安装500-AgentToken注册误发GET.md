# 2026-08-07 一键安装 500-注册 Agent Token 误发 GET(install-agent.sh bug)

**日期**: 2026-08-07
**操作人**: opencode / 用户
**关联**: 在云端服务器(VM-0-11-opencloudos)上用一键安装部署 txcloud agent,预建 server 成功、注册 token 时报 500

## 一、现象

用新的一键安装指令在云服务器上装 agent:

```bash
curl --fail --silent --show-error --location https://genhaosan.online/agent/install-agent.sh | \
  sudo -E env AGENT_BASE_URL=https://genhaosan.online AGENT_TERMINAL_ENABLED=true AGENT_VERSION=1.0.0 bash
```

流程走到交互输入 admin 账密和机器名后:

```
Administrator username: smoke
Administrator password:
Agent name [VM-0-11-opencloudos]: txcloud
curl: (22) The requested URL returned error: 500
curl: (22) The requested URL returned error: 500
curl: (22) The requested URL returned error: 500
[ERROR] Agent token request failed.
```

- 建 server 成功(DB 里出现 `tencentcloud`/`txcloud` 两条,是两次运行留下的)
- 注册 token 一步反复 500(500 触发 curl `--retry 2`,共 3 次尝试)

## 二、排查过程

### 1. 后端日志看真实异常
```
HttpRequestMethodNotSupportedException: Request method 'GET' is not supported
```
后端收到的是 **GET**,说明是 HTTP 方法不对,不是业务异常。

### 2. 直接复现(决定性)
- `POST /api/servers`(脚本建 server 的调用)→ **200 成功**(复现时创建了 repro-test id=9)
- `GET /api/servers/5/agent/register`(脚本实际发出的)→ **500 internal server error**(GET 不支持)
- `POST /api/servers/8/agent/register`(真实存在的 id)→ **200 成功,token 签发** ← 端点本来就好好的!

> 插曲:中间用不存在的 id=5 测 POST 返回 404(RESOURCE_NOT_FOUND),一度误判"部署 jar 缺该端点";
> 查 jar 里其实有 `AgentTokenController.class`,404 只是 server id 不存在。**测端点要先用真实存在的资源 id。**

### 3. 看脚本字节(根因)
`agent-go-SuMon/deploy/install-agent.sh` 的 register 调用:

```bash
TOKEN_JSON="$(curl --fail --silent --show-error --location "${CURL_TRANSPORT[@]}" \
    --retry 2 --connect-timeout 10 --max-time 30 \
    -H "Authorization: Bearer ${JWT}" \
    "${AGENT_BASE_URL}/api/servers/${AGENT_SERVER_ID}/agent/register")"
```

**curl 没有 `--data` 也没有 `-X POST` → 默认发 GET**。而后端该端点是
`AgentTokenController` 的 `@PostMapping("/{id}/agent/register")`,GET 打到 POST-only
端点 → Spring `HttpRequestMethodNotSupportedException` → GlobalExceptionHandler 500。

## 三、根因

**install-agent.sh 注册 token 的 curl 漏了 HTTP 方法(默认 GET)**,与后端的
`@PostMapping` 端点不匹配。这不是服务器/后端问题,是部署脚本的 bug(从写脚本起就存在,
只是此前安装流程可能没走到这一步或没被触发)。

## 四、修复

1. 脚本加 `-X POST`:
   ```bash
   TOKEN_JSON="$(curl --fail --silent --show-error --location "${CURL_TRANSPORT[@]}" \
       --retry 2 --connect-timeout 10 --max-time 30 \
       -X POST \
       -H "Authorization: Bearer ${JWT}" \
       "${AGENT_BASE_URL}/api/servers/${AGENT_SERVER_ID}/agent/register")"
   ```
2. 重新部署脚本到 `/www/wwwroot/susumonitor/agent/install-agent.sh`
3. 对已建好的 txcloud(id=8)用已签发 token 非交互补装:
   ```
   curl ... https://genhaosan.online/agent/install-agent.sh | sudo -E env \
     AGENT_BASE_URL=https://genhaosan.online AGENT_TERMINAL_ENABLED=true AGENT_VERSION=1.0.0 \
     AGENT_NAME=txcloud AGENT_SERVER_ID=8 AGENT_TOKEN=<token> bash
   ```
   (AGENT_SERVER_ID + AGENT_TOKEN 同时给,脚本跳过交互注册,直接装)

## 五、验证

| 测试 | 结果 | 说明 |
|---|---|---|
| `GET /api/servers/{id}/agent/register`(脚本旧行为) | 500 GET not supported | 复现脚本 bug |
| `POST /api/servers/8/agent/register`(正确方法) | 200 + agent_token | 端点正常,误判已排除 |
| 修复后脚本重跑 | 安装成功,无 500 | 修复生效 |
| txcloud agent 日志 | `agent authenticated` server_id=8,backend wss:// | 认证成功 |
| 后端 server 8 状态 | `agent_status: online` + 心跳 | 心跳正常 |
| 清理 | 删除 repro-test(9)、tencentcloud(7) | 清除复现/失败残留 |

## 六、教训

1. **curl 不带 `--data`/`-X` 就是 GET**:写部署脚本调 REST 端点时,明确用 `-X POST`
   或 `--data`,别依赖默认行为。这坑一次就够。
2. **500 先看后端日志的异常类型**:`HttpRequestMethodNotSupportedException` 直接指向
   方法不匹配,别先怀疑后端缺功能/部署版本。
3. **测端点用真实存在的资源**:用不存在的 id 会得到 404(RESOURCE_NOT_FOUND),容易误判成
   "端点不存在"。先在 DB/列表里确认资源 id。
4. **一键安装脚本与后端契约要同步验证**:脚本调用的每个端点都应对照 Controller 映射做一次
   端到端冒烟(建 server → 注册 token → agent 心跳 → 终端),本次就是脚本/契约脱节的例子。
