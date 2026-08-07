package com.susumonitor.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 注册安全相关的 Spring Bean，提供密码编码器等安全组件。
 */
// 将当前类注册为 Spring 配置类，使其中的 @Bean 方法生效。
@Configuration
public class SecurityBeansConfig {

    /**
     * 将 BCryptPasswordEncoder 注册为 Spring Bean，供用户注册和登录服务注入使用。
     *
     * @return BCrypt 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
