package com.susumonitor.server.security;

import com.susumonitor.server.module.auth.mapper.UserMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 配置无状态 Bearer 鉴权和公开接口边界。
 */
// 将当前类注册为 Spring 安全配置类。
@Configuration
// 启用 Web 安全过滤器链。
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 创建 JWT 过滤器，由 Spring Security 过滤器链管理，避免 Servlet 容器重复注册。
     *
     * @param jwtTokenService JWT 服务
     * @param userMapper 用户 Mapper
     * @param securityErrorHandler 安全错误处理器
     * @param tokenBlacklist Redis 黑名单（可选，Redis 未启用时为空）
     * @param appProperties 应用配置（读取黑名单故障语义开关；WebMvcTest 切片可缺席）
     * @return JWT 认证过滤器
     */
    // 将 JWT 过滤器注册为 Spring Bean，供安全链引用。
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenService jwtTokenService,
            UserMapper userMapper,
            SecurityErrorHandler securityErrorHandler,
            ObjectProvider<RedisTokenBlacklist> tokenBlacklist,
            org.springframework.beans.factory.ObjectProvider<com.susumonitor.server.config.AppProperties> appProperties) {
        return new JwtAuthenticationFilter(jwtTokenService, userMapper, securityErrorHandler, tokenBlacklist,
                appProperties);
    }

    /**
     * 禁止 Servlet 容器自动注册 JWT Filter，确保它只在 Spring Security 链中执行一次。
     *
     * @param jwtAuthenticationFilter JWT 过滤器
     * @return 禁用状态的 Servlet Filter 注册配置
     */
    // 注册禁用的 Servlet Filter 包装，防止每个请求重复验签和回查数据库。
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter jwtAuthenticationFilter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(jwtAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 建立无状态安全链，仅公开健康检查、就绪检查、注册和登录。
     *
     * <p>CORS 通过 {@code Customizer.withDefaults()} 启用，实际配置由
     * {@code CorsConfig} 提供的 {@code CorsConfigurationSource} Bean 决定。
     * 在 {@code @WebMvcTest} 环境中该 Bean 不存在时，CORS 不处理但不会启动失败。</p>
     *
     * @param httpSecurity Spring Security HTTP 配置
     * @param jwtAuthenticationFilter JWT 过滤器
     * @param securityErrorHandler 统一 401/403 处理器
     * @return 安全过滤器链
     * @throws Exception 安全链构建失败
     */
    // 注册项目唯一的 HTTP 安全过滤器链。
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity httpSecurity,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            SecurityErrorHandler securityErrorHandler) throws Exception {
        return httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // 启用 CORS 处理，配置源由 CorsConfig 提供 CorsConfigurationSource Bean。
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/ready").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // 首管理员初始化状态查询（批次 8）：公开只读，仅返回一个布尔值，
                        // 供注册页决定是否展示一次性初始化令牌输入框。
                        .requestMatchers(HttpMethod.GET, "/api/auth/bootstrap-status").permitAll()
                        // Swagger UI 与 OpenAPI 文档为只读开发/联调资产，公开放行（仅 GET）。
                        .requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**",
                                "/api-docs", "/api-docs/**", "/webjars/**").permitAll()
                         .requestMatchers("/api/admin/**").hasRole("ADMIN")
                         .requestMatchers(HttpMethod.POST, "/api/servers").hasRole("ADMIN")
                         .requestMatchers("/api/servers/*/agent/**").hasRole("ADMIN")
                        .requestMatchers("/api/servers/*/ssh/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/servers/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/servers/*").hasRole("ADMIN")
                        // 告警规则创建、更新和删除需要 admin 角色。
                        .requestMatchers(HttpMethod.POST, "/api/alerts/rules").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/alerts/rules/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/alerts/rules/*").hasRole("ADMIN")
                        // AI 告警解释回看仅管理员（AI 域端点口径与诊断/命令域一致）；
                        // 必须先于下方 records/** 的 authenticated 匹配注册。开关关闭时 Controller 不装配返回 404。
                        .requestMatchers(HttpMethod.GET, "/api/alerts/records/*/explanation").hasRole("ADMIN")
                        // 告警规则查询和告警记录查询、标记已读需要已认证。
                        .requestMatchers(HttpMethod.GET, "/api/alerts/rules", "/api/alerts/rules/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/alerts/records", "/api/alerts/records/**").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/alerts/records/*/read").authenticated()
                         .requestMatchers(HttpMethod.GET, "/api/servers", "/api/servers/**").authenticated()
                         // 只读 AI 诊断显式限制为管理员，避免被 anyRequest 的认证规则意外放宽。
                         .requestMatchers(HttpMethod.POST, "/api/ai/diagnoses").hasRole("ADMIN")
                         // 运维问答（F2）仅管理员；qa 开关关闭时 Service 短路 50304。
                         .requestMatchers(HttpMethod.POST, "/api/ai/qa").hasRole("ADMIN")
                         // 命令域 M1（审批制）全部端点仅管理员；开关关闭时 Controller 不装配返回 404。
                         .requestMatchers("/api/ai/commands/**").hasRole("ADMIN")
                         // 定时健康报告（F3）查询与手动触发仅管理员；开关关闭时 Controller 不装配返回 404。
                         .requestMatchers("/api/ai/health-reports/**").hasRole("ADMIN")
                         // 个人 AI 服务商配置（按管理员隔离，api_key 仅密文存储）仅管理员；开关关闭时 Controller 不装配返回 404。
                         .requestMatchers("/api/ai/provider-config/**").hasRole("ADMIN")
                         // RabbitMQ 运行监控快照仅管理员可见（MVP-14 监控收尾）。
                         .requestMatchers(HttpMethod.GET, "/api/system/rabbitmq/**").hasRole("ADMIN")
                         .requestMatchers("/ws/agent", "/ws/monitor").permitAll()
                         .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
