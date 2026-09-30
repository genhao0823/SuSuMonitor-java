package com.susumonitor.server.module.metrics.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * 单个网卡吞吐视图（协议 v1.5，采集间隔均值，内存保留不落库）。
 */
public record NicSampleVo(
        @JsonProperty("name") @Schema(description = "网卡名", minLength = 1, maxLength = 128) String name,
        @JsonProperty("rx_kbps") @Schema(description = "接收速率（kbps）", minimum = "0") BigDecimal rxKbps,
        @JsonProperty("tx_kbps") @Schema(description = "发送速率（kbps）", minimum = "0") BigDecimal txKbps) {
}
