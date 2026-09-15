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
// 自动生成字段的访问方法及对象基础方法。
@Data
// 生成全参构造器，配合不可变式赋值使用（字段全部由构造注入，无 setter 语义需求）。
@AllArgsConstructor
// 类级 @Schema 描述响应数据模型，供 springdoc 生成 /api-docs 的响应模型说明。
@Schema(description = "首管理员初始化状态")
public class BootstrapStatusVo {

    // 为 true 表示系统仍等待首个管理员创建，注册需要携带一次性初始化令牌。
    @Schema(description = "系统是否仍待初始化首管理员（true 时注册需要一次性初始化令牌）")
    private boolean bootstrapPending;
}
