package com.susumonitor.server.module.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 首管理员初始化状态公开响应对象，供三端决定是否展示初始化令牌输入。
 *
 * <p>仅暴露一个布尔值：pending 状态本身通过注册接口的 40310 也可推断，
 * 本端点不泄露令牌存在性之外的任何信息。</p>
 */
@Data
@AllArgsConstructor
@Schema(description = "首管理员初始化状态")
public class BootstrapStatusVo {

    @Schema(description = "系统是否仍待初始化首管理员（true 时注册需要一次性初始化令牌）")
    private boolean bootstrapPending;
}
