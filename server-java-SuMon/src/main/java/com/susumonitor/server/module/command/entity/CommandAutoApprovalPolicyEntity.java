package com.susumonitor.server.module.command.entity;

import java.time.LocalDateTime;

/** 映射 AI 命令域自动审批策略（V33 ai_command_auto_approval_policies，单行 id=1）。 */
public class CommandAutoApprovalPolicyEntity {
    private Integer id;
    private Boolean enabled;
    private String maxRiskLevel;
    private Long updatedBy;
    private LocalDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer value) { id = value; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { enabled = value; }
    public String getMaxRiskLevel() { return maxRiskLevel; }
    public void setMaxRiskLevel(String value) { maxRiskLevel = value; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long value) { updatedBy = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
