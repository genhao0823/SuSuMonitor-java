package com.susumonitor.server.module.command.dto;

/** 观察期评审报告的模板用量聚合行（GROUP BY template_id，MyBatis 别名直映射，非实体）。 */
public class TemplateUsageAggregate {

    /** 模板 ID（ai_command_runs.template_id）。 */
    private String templateId;

    /** 窗口内该模板的运行次数。 */
    private Long runs;

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String value) { templateId = value; }

    public Long getRuns() { return runs; }
    public void setRuns(Long value) { runs = value; }
}
