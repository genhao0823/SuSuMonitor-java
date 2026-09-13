# 2026-09-14 AI 个人 Provider 配置更新必现 50001（ON DUPLICATE KEY UPDATE 行数误判）

- **级别**：高（已存在配置的管理员无法再次保存/修改个人 AI 配置，前端报未知错误）
- **发现方式**：全功能联调 Phase 3a —— 内网真实环境对已存在配置执行第二次保存
- **影响版本**：V32 功能上线以来（更新路径必现；首存路径正常）
- **修复提交**：随本轮联调批次入库

## 一、现象

1. 首次保存个人 AI 配置：HTTP 200 正常。
2. 已存在配置时再次保存（改 base_url/model/key 任一）：`HTTP 500 {"code":50001,"message":"database error"}`。
3. **数据实际已更新落库**（`updated_at` 与新 base_url 均已写入），错误完全虚假。

## 二、根因

`AiUserProviderConfigMapper.upsert` 使用 `INSERT ... ON DUPLICATE KEY UPDATE`；服务层以
`mapper.upsert(config) != 1` 判定失败。在 MySQL Connector/J 默认 found_rows 语义下：

- 首存（真插入）返回 **1** → 通过；
- 更新（ON DUPLICATE 命中唯一键）返回 **2**（matched rows）→ `!= 1` 判定失败 → 抛 50001。

单元测试（Mockito mock Mapper 恒返回 1）无法暴露该路径；MySQL IT 未覆盖此 Mapper。

## 三、复现证据（内网联调环境）

```
DELETE /api/ai/provider-config → 200
PUT（首存）                    → 200 {"configured":true,...}
PUT（再次保存，数据同）        → 500 {"code":50001,"data":null}
DB: ai_user_provider_configs 行存在且 updated_at 已刷新（数据实际更新成功）
```

## 四、修复

- `AiUserProviderConfigService.upsert`：判定改为 `mapper.upsert(config) < 1`（0 行才失败），
  注释说明 1=insert、2=update 的 Connector/J 语义。
- 回归单测：`upsertShouldSucceedWhenUpdatePathReturnsTwoRows`（mock 返回 2 必须成功）。

## 五、预防

与同日 `agent-token-rotate-500` 缺陷同类：**手写 SQL 的返回行数语义（found_rows vs affected_rows、
ON DUPLICATE、SET 求值顺序）必须有真实 MySQL 集成断言**；仅靠 mock Mapper 的单测对 SQL 层零覆盖。
