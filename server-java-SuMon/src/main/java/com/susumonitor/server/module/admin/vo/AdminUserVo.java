package com.susumonitor.server.module.admin.vo;

import java.time.OffsetDateTime;
import lombok.Data;

// 自动生成当前 VO 的 getter、setter、toString、equals 和 hashCode 方法。
@Data
public class AdminUserVo {

    private Long id;

    private String username;

    private String role;

    private String reviewStatus;

    private OffsetDateTime createdAt;

}
