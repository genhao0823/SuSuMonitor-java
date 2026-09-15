package com.susumonitor.server.security;

import com.susumonitor.server.module.auth.service.BootstrapTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动期装配首管理员一次性初始化令牌（批次 8）。
 *
 * <p>在应用就绪阶段调用 {@link BootstrapTokenService#ensureTokenReady()}：
 * 首管理员未初始化时生成或载入令牌并经启动横幅投递；装配失败（如环境变量
 * 预置值长度非法）时异常向上传播、阻止应用启动（fail-fast，与 AesGcmKeyConfig
 * 的密钥校验语义一致，防止不安全实例对外服务）。</p>
 */
// 将装配器注册为 Spring Bean，使其随应用启动自动执行一次。
@Slf4j
@Component
// 通过构造器注入令牌服务，保持与项目既有 Bean 风格一致。
@RequiredArgsConstructor
public class BootstrapTokenInitializer implements ApplicationRunner {

    private final BootstrapTokenService bootstrapTokenService;

    /**
     * 应用就绪后执行令牌装配；仅首管理员未初始化时产生实际效果。
     *
     * @param args 启动参数（本装配器不消费命令行参数）
     */
    @Override
    public void run(ApplicationArguments args) {
        bootstrapTokenService.ensureTokenReady();
    }
}
