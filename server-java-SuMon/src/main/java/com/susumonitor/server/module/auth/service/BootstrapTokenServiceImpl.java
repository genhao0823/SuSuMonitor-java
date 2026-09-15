package com.susumonitor.server.module.auth.service;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.auth.entity.AuthBootstrapStateEntity;
import com.susumonitor.server.module.auth.mapper.AuthBootstrapStateMapper;
import com.susumonitor.server.security.CredentialCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首管理员一次性初始化令牌业务实现：启动装配、横幅投递与注册校验（批次 8）。
 *
 * <p>令牌明文只允许出现在启动横幅（stdout/日志的唯一显式例外，见开发计划 D4）
 * 与本次比较的局部变量中；严禁写入请求日志、审计记录或任何响应体。
 * 存储形态为 AES-256-GCM 密文（AAD 绑定系统维度固定上下文），明文永不落库。</p>
 */
// 将令牌服务注册为 Spring Bean，供注册事务与启动装配器注入。
@Slf4j
@Service
public class BootstrapTokenServiceImpl implements BootstrapTokenService {

    /** 自动生成令牌的随机字节数：32 字节 → base64url 43 字符（约 256-bit 熵）。 */
    private static final int TOKEN_RANDOM_BYTES = 32;

    /** 预置令牌允许的最小长度（字符），与注册 DTO 校验下限一致。 */
    private static final int TOKEN_MIN_LENGTH = 32;

    /** 预置令牌允许的最大长度（字符），与注册 DTO 校验上限及密文列容量一致。 */
    private static final int TOKEN_MAX_LENGTH = 128;

    private final AuthBootstrapStateMapper authBootstrapStateMapper;

    private final CredentialCipher credentialCipher;

    private final AppProperties appProperties;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 注入令牌装配与校验所需的依赖。
     *
     * @param authBootstrapStateMapper 初始化状态 Mapper
     * @param credentialCipher AES-256-GCM 凭据密码器（bootstrap 固定 AAD 上下文）
     * @param appProperties 应用配置（读取 AUTH_BOOTSTRAP_TOKEN 预置值）
     */
    public BootstrapTokenServiceImpl(
            AuthBootstrapStateMapper authBootstrapStateMapper,
            CredentialCipher credentialCipher,
            AppProperties appProperties) {
        this.authBootstrapStateMapper = authBootstrapStateMapper;
        this.credentialCipher = credentialCipher;
        this.appProperties = appProperties;
    }

    /**
     * 启动装配：按「环境变量 > 状态行未消费密文 > 自动生成」优先级保证令牌可用。
     *
     * <p>独立短事务写状态行；首管理员已初始化时为空操作（打 debug 日志便于排查）。
     * 环境变量预置值长度非法时抛出 IllegalStateException 阻止启动（fail-fast，
     * 防止弱令牌随公网实例上线）。</p>
     */
    @Override
    @Transactional
    public void ensureTokenReady() {
        // 预置值校验先于状态读取：非法配置无论数据库处于何种状态都必须阻止启动。
        String presetToken = appProperties.getSecurity().getBootstrapToken();
        if (presetToken != null && !presetToken.isBlank()
                && (presetToken.length() < TOKEN_MIN_LENGTH || presetToken.length() > TOKEN_MAX_LENGTH)) {
            throw new IllegalStateException(
                    "AUTH_BOOTSTRAP_TOKEN length must be between "
                            + TOKEN_MIN_LENGTH + " and " + TOKEN_MAX_LENGTH);
        }

        AuthBootstrapStateEntity state = authBootstrapStateMapper.selectState();
        if (state == null || Boolean.TRUE.equals(state.getAdminInitialized())) {
            log.debug("bootstrap token not required: admin already initialized");
            return;
        }

        if (presetToken != null && !presetToken.isBlank()) {
            saveToken(presetToken);
            log.info("bootstrap token loaded from AUTH_BOOTSTRAP_TOKEN (value not echoed)");
            return;
        }

        if (state.getBootstrapTokenCipher() != null && !state.getBootstrapTokenCipher().isBlank()) {
            // 重启且令牌仍未消费：解密后重新回显横幅，避免明文仅打印一次而丢失。
            log.info("bootstrap token pending since {}, banner re-printed", state.getBootstrapTokenGeneratedAt());
            printBanner(credentialCipher.decryptForBootstrap(state.getBootstrapTokenCipher()));
            return;
        }

        String generated = generateToken();
        saveToken(generated);
        log.info("bootstrap token generated (256-bit random)");
        printBanner(generated);
    }

    /**
     * 校验候选令牌：未携带抛 40310，不匹配或状态行无密文抛 40311（fail-closed）。
     *
     * <p>比较使用解密后常量时间比较，避免时序侧信道；校验发生在注册事务的
     * selectForUpdate 行锁内，与消费写同事务保证令牌至多使用一次。</p>
     *
     * @param state 行锁内的初始化状态
     * @param candidateToken 注册请求携带的候选令牌
     */
    @Override
    public void verifyAgainst(AuthBootstrapStateEntity state, String candidateToken) {
        if (candidateToken == null || candidateToken.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_BOOTSTRAP_REQUIRED);
        }
        String cipher = state.getBootstrapTokenCipher();
        // 状态行无密文属于服务端未签发的异常态（如启动装配尚未完成），一律拒绝并留痕。
        if (cipher == null || cipher.isBlank()) {
            log.error("bootstrap token missing in state row while admin not initialized; registration denied");
            throw new BusinessException(ErrorCode.AUTH_BOOTSTRAP_TOKEN_INVALID);
        }
        String issued = credentialCipher.decryptForBootstrap(cipher);
        // MessageDigest.isEqual 为常量时间比较，防止逐字符猜测的时序侧信道。
        boolean matches = MessageDigest.isEqual(
                issued.getBytes(StandardCharsets.UTF_8),
                candidateToken.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            log.warn("bootstrap token mismatch during registration attempt");
            throw new BusinessException(ErrorCode.AUTH_BOOTSTRAP_TOKEN_INVALID);
        }
    }

    /**
     * 将令牌密文写入状态行（仅在 admin_initialized=0 时生效）。
     *
     * @param plaintext 令牌明文（加密后即弃，不进入任何日志）
     */
    private void saveToken(String plaintext) {
        String cipher = credentialCipher.encryptForBootstrap(plaintext);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (authBootstrapStateMapper.saveBootstrapToken(cipher, now) != 1) {
            // 首管理员并发初始化刚好完成：装配放弃写入即可，不影响启动。
            log.warn("bootstrap token save skipped: admin initialized concurrently");
        }
    }

    /**
     * 生成 256-bit 随机令牌（base64url 编码，无填充，43 字符）。
     *
     * @return 令牌明文
     */
    private String generateToken() {
        byte[] randomBytes = new byte[TOKEN_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * 打印一次性初始化令牌横幅（「日志不输出敏感信息」规范的唯一显式例外，
     * 见开发计划决策 D4；令牌消费后立即失效，横幅不得被应用重复记录到请求日志）。
     *
     * @param token 令牌明文
     */
    private void printBanner(String token) {
        log.info("{}",
                "\n"
                + "================================================================\n"
                + "  [SuSuMonitor] 系统尚未初始化管理员，一次性初始化令牌：\n"
                + "      " + token + "\n"
                + "  请尽快使用该令牌调用 POST /api/auth/register 完成首管理员注册；\n"
                + "  令牌消费后立即失效。也可通过环境变量 AUTH_BOOTSTRAP_TOKEN 预置。\n"
                + "================================================================");
    }
}
