package com.susumonitor.server.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档全局配置，为 springdoc 生成的 /api-docs 提供标题、版本与认证方案声明。
 *
 * <p>接口契约的权威来源是 docs-SuMon/OpenApi-SuMon/*.json 手写契约，
 * springdoc 生成的文档是运行时辅助；各端点的 summary/description/operationId
 * 在 Controller 注解中与契约保持对齐，本配置只声明全局元数据。</p>
 */
// 声明 OpenAPI 全局信息：标题、描述和版本，Swagger UI 首页展示这些元数据。
@OpenAPIDefinition(
        info = @Info(
                title = "SuSuMonitor API",
                version = "0.1.0",
                description = "SuSuMonitor 监控系统后端 REST API。"
                        + "权威契约源为 docs-SuMon/OpenApi-SuMon/*.json，"
                        + "本文档由 springdoc 自动生成，供联调与查阅。"))
// 声明 Bearer JWT 认证方案，使 Swagger UI 出现 Authorize 按钮。
// 项目使用自定义 Bearer 过滤器而非 OAuth2 资源服务器，springdoc 无法自动推断认证方案，必须显式声明。
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "JWT supplied only through the Authorization: Bearer <token> request header.")
@Configuration
public class OpenApiConfig {
}
