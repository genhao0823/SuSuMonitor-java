package com.susumonitor.server.module.ai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** 一次问答中模型实际调用的只读工具概要（调用审计对客户端可见）。 */
@Schema(description = "问答中模型调用的只读工具")
public class AiToolCallVo {

    @Schema(description = "工具名（平台注册白名单之一）", maxLength = 64)
    @JsonProperty("tool")
    private String tool;

    @Schema(description = "受控参数概要（仅白名单参数，无敏感数据）", maxLength = 500)
    @JsonProperty("args")
    private String args;

    public AiToolCallVo() {
    }

    public AiToolCallVo(String tool, String args) {
        this.tool = tool;
        this.args = args;
    }

    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }
    public String getArgs() { return args; }
    public void setArgs(String args) { this.args = args; }
}
