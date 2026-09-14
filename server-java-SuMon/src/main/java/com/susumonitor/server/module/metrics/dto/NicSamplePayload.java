package com.susumonitor.server.module.metrics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Agent 上报的单个网卡吞吐条目（websocket-protocol.md v1.5）。
 *
 * <p>速率为采集间隔内的平均值，单位 kbps；连接明细与凭据禁止进入协议字段。</p>
 */
public record NicSamplePayload(
        @JsonProperty("name") String name,
        @JsonProperty("rx_kbps") BigDecimal rxKbps,
        @JsonProperty("tx_kbps") BigDecimal txKbps) {
}
