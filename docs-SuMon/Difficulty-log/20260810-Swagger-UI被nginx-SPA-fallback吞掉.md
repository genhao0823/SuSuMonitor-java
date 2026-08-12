# 2026-08-10 Swagger UI 公网访问被 nginx SPA fallback 吞掉（两层根因）

**日期**: 2026-08-10
**操作人**: ZCode / 用户
**关联**: 项目集成 springdoc-openapi 2.8.9 + swagger-ui 5.21.0，但 `https://genhaosan.online/swagger-ui.html` 与 `/api-docs` 均返回前端 SPA 页面（页面内容仅 "SuSuMonitor"），无法访问 Swagger 文档。

## 一、现象

- `curl https://genhaosan.online/swagger-ui.html` → 返回前端 index.html（SPA，title "SuSuMonitor"），不是 Swagger UI
- `curl https://genhaosan.online/api-docs` → 同样返回 SPA 页面，不是 OpenAPI JSON
- `curl https://genhaosan.online/api/health` → 正常返回 `{"code":0,...}`（后端代理正常）
- 后端本地 `curl http://127.0.0.1:18080/swagger-ui.html` → 302 到 `/swagger-ui/index.html`，200 正常

即：后端本身完整支持 Swagger（jar 内含 springdoc 2.8.9 + swagger-ui 5.21.0），问题只在公网入口。

## 二、根因（两层叠加）

### 1. nginx 路由：SPA fallback 吞掉 swagger 路径（主因）

云端 nginx 配置 `/www/server/panel/vhost/nginx/susumonitor.conf` 主站 HTTPS server block：

```nginx
location / {                       # 前端 SPA fallback
    try_files $uri $uri/ /index.html;
}
location /api/ {                   # 只有 /api 前缀代理到 Java 后端
    proxy_pass http://127.0.0.1:18080/api/;
}
```

`/swagger-ui.html`、`/api-docs` 不匹配 `/api/`、`/ws/*`、`/agent/`、`/pack` 任何 location → 落入 `location /` 的 `try_files` → 找不到文件 → 回退 `/index.html`（前端 SPA）。

### 2. Spring Security：代理过去也会被拦截（次因）

`server-java-SuMon/src/main/java/com/susumonitor/server/security/SecurityConfig.java` 的 permitAll 只有 health/ready/register/login/ws；`/swagger-ui/**`、`/api-docs`、`/webjars/**` 落入 `.anyRequest().authenticated()` → 即使 nginx 放行代理，后端也会返回 40100。

**验证**：修复前 `curl http://127.0.0.1:18080/v3/api-docs/swagger-config` 返回 `{"code":40100,...}`。

## 三、修复

### 1. 后端 SecurityConfig 放行 Swagger（代码 + 重打包部署）

`SecurityConfig.java` permitAll 区新增（仅 GET，最小权限）：

```java
// Swagger UI 与 OpenAPI 文档为只读开发/联调资产，公开放行（仅 GET）。
.requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**",
        "/api-docs", "/api-docs/**", "/webjars/**").permitAll()
```

重新打包并部署：`mvn test` 全量通过 → `mvn -DskipTests package` → 上传到 `/opt/susumonitor/releases/swagger-ui-20260810/` → 更新符号链接 → `systemctl restart susumonitor-server`。本地/远端 jar SHA-256 一致（`e2eed73d...`）。

### 2. nginx 增加 Swagger 代理 location（云端配置）

在 `susumonitor.conf` 的 `location /`（SPA fallback）之前插入 4 个 location，代理到后端 18080，Host 头设为 `genhaosan.online`（保证 springdoc 生成的 server url 正确）：

```nginx
# Swagger UI / OpenAPI 文档（代理到 Java 后端）
location = /swagger-ui.html {
    proxy_pass http://127.0.0.1:18080;
    proxy_set_header Host genhaosan.online;
    ...X-Real-IP / X-Forwarded-For / X-Forwarded-Proto https
}
location /swagger-ui/ { proxy_pass http://127.0.0.1:18080; (同上 headers) }
location /api-docs { proxy_pass http://127.0.0.1:18080; (同上 headers) }
location /webjars/ { proxy_pass http://127.0.0.1:18080; (同上 headers) }
```

修改前备份：`/www/server/panel/vhost/nginx/susumonitor.conf.bak.swagger.20260810`。`nginx -t` 通过后 `nginx -s reload`。

## 四、验证（公网实测）

| 路径 | 结果 |
|---|---|
| `https://genhaosan.online/swagger-ui.html` | 302 → `/swagger-ui/index.html` |
| `https://genhaosan.online/swagger-ui/index.html` | 200，`<title>Swagger UI</title>` |
| `https://genhaosan.online/swagger-ui/swagger-ui.css` | 200（154 KB） |
| `https://genhaosan.online/swagger-ui/swagger-ui-bundle.js` | 200（1.4 MB） |
| `https://genhaosan.online/api-docs` | 200，OpenAPI 3.1 JSON，`servers.url=https://genhaosan.online` |
| `https://genhaosan.online/api-docs/swagger-config` | 200，`configUrl=/api-docs/swagger-config` |
| `https://genhaosan.online/api/health` | 200（回归正常） |
| `https://genhaosan.online/` | 200（前端 SPA 回归正常） |

## 五、教训

1. **HTTPS 迁移/上线扫一遍"新路径"而不是只看业务路径**：nginx 里 `location /` 的 `try_files $uri /index.html` 会静默吞掉所有未显式代理的路径（返回 SPA 而非 404），这正是之前"Web 终端建链中-agent 指向明文地址"类似的遗漏模式——**SPA fallback 是"假 200"大坑**，遇到"路径返回前端页面"先查 nginx location 是否被 fallback 命中。
2. **两层问题要一起修**：nginx 放行代理后，后端 Spring Security 仍可能拦截（`anyRequest().authenticated()`），必须同时核对 SecurityConfig 的 permitAll。诊断时先在后端本机（绕过 nginx）直接 curl `:18080` 验证后端是否 OK，再决定改哪层。
3. **springdoc 的 server url 跟随 Host 头**：nginx 代理到后端时若 Host 设为 `127.0.0.1:18080`，springdoc 生成的 `servers.url` 也会是 `127.0.0.1`，导致 Swagger UI 的 "Try it out" 请求本地地址；代理 swagger 路径时 Host 应设为公网域名。
4. **webjars 资源路径**：本版本 swagger-ui 页面用相对路径（`./swagger-ui.css`）引用资源，实际不依赖 `/webjars/`；但保留 `/webjars/` 代理可兼容 springdoc 内部转发，成本为零。

## 六、安全边界

Swagger UI 公开后暴露全部 REST 接口路径与错误码表（对内部学习/demo 项目可接受）。如需收敛，后续可加 nginx IP 白名单或 Basic Auth（`auth_basic`），或设置 `springdoc.swagger-ui.enabled=false` 关闭。