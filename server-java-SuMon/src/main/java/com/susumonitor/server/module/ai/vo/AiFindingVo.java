package com.susumonitor.server.module.ai.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 诊断中的单项事实发现。 */
@Schema(description = "诊断发现")
public class AiFindingVo {
    private String title;
    private String description;
    private String confidence;
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String value) { confidence = value; }
}
