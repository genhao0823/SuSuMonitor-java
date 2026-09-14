package com.susumonitor.server.module.metrics.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Agent 上报的单个挂载点容量条目（websocket-protocol.md v1.5）。
 *
 * <p>只携带挂载点、设备名与字节数；文件系统内容与凭据禁止进入协议字段。</p>
 */
public record DiskSamplePayload(
        @JsonProperty("mount_point") String mountPoint,
        @JsonProperty("device") String device,
        @JsonProperty("total") Long total,
        @JsonProperty("free") Long free) {
}
