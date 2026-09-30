package com.susumonitor.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 提供 REST API 的 CORS 配置源。
 *
 * <p>白名单精确匹配，不使用通配符 "*"。允许 Authorization、Content-Type 和
 * X-Correlation-ID 请求头。预检缓存由配置控制。部署时通过环境变量
 * CORS_ALLOWED_ORIGINS 配置实际前端域名。</p>
 *
 * <p>独立于 {@code SecurityConfig}，使 {@code @WebMvcTest} 环境在
 * 不导入此类时也能正常启动。{@code SecurityConfig} 通过
 * {@code .cors(Customizer.withDefaults())} 引用此 Bean。</p>
 */
@Configuration
public class CorsConfig {

    /**
     * 从 AppProperties 读取前端 Origin 白名单并构造 CORS 配置源。
     *
     * @param appProperties 应用配置属性
     * @return CORS 配置源
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties appProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(appProperties.getCors().getAllowedOrigins());
        configuration.setAllowedMethods(appProperties.getCors().getAllowedMethods());
        configuration.setAllowedHeaders(appProperties.getCors().getAllowedHeaders());
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(appProperties.getCors().getMaxAgeSeconds());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
