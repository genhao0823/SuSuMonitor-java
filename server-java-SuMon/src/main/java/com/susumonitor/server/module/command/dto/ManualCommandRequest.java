package com.susumonitor.server.module.command.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** 手动创建待审批命令请求：引用白名单模板 ID + 类型化参数。 */
@Schema(description = "手动创建待审批命令请求")
@JsonIgnoreProperties(ignoreUnknown = false)
public class ManualCommandRequest {

    @NotNull
    @Min(1)
    @JsonProperty("server_id")
    @Schema(description = "目标服务器 ID", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long serverId;

    @NotBlank
    @Size(max = 64)
    @JsonProperty("template_id")
    @Schema(description = "白名单模板 ID（见 GET /api/ai/commands/templates）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String templateId;

    @Size(max = 16)
    @Schema(description = "模板具名参数（键值均为字符串，规则见模板定义）")
    private Map<String, String> params;

    public Long getServerId() { return serverId; }
    public void setServerId(Long value) { serverId = value; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String value) { templateId = value; }
    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> value) { params = value; }
}
