package com.susumonitor.server.module.metrics.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * 单个挂载点容量视图（协议 v1.5，内存保留不落库）。
 */
public record DiskSampleVo(
        @JsonProperty("mount_point") @Schema(description = "挂载点路径", minLength = 1, maxLength = 128) String mountPoint,
        @JsonProperty("device") @Schema(description = "设备名", minLength = 1, maxLength = 128) String device,
        @JsonProperty("total") @Schema(description = "总容量（字节）", minimum = "0") Long total,
        @JsonProperty("free") @Schema(description = "剩余空间（字节）", minimum = "0") Long free) {
}
