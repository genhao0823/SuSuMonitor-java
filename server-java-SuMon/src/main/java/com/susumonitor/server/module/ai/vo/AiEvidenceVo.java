package com.susumonitor.server.module.ai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 诊断所引用的白名单监控证据。 */
@Schema(description = "监控证据")
public class AiEvidenceVo {
    private String metric;
    private Object value;
    @JsonProperty("observed_at")
    private OffsetDateTime observedAt;
    private String source;
    public String getMetric() { return metric; }
    public void setMetric(String value) { metric = value; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
    public OffsetDateTime getObservedAt() { return observedAt; }
    public void setObservedAt(OffsetDateTime value) { observedAt = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
}
