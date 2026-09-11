package com.susumonitor.server.module.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.Data;

/** 手动触发一次健康报告生成的请求；report_date 缺省时由服务端取昨日。 */
@Data
@Schema(description = "手动触发健康报告生成请求")
public class GenerateHealthReportRequest {

    @JsonProperty("report_date")
    @Schema(description = "报告覆盖的自然日（不可晚于当日）；缺省生成昨日报告")
    private LocalDate reportDate;
}
