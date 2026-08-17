package com.susumonitor.server.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.security.AuthenticatedUser;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 的一次性 Monitor ticket（多实例化阶段一，2026-08-17）。
 *
 * <p>与 {@link MonitorTicketServiceImpl}（单 JVM 内存）互斥：{@code susumonitor.redis.enabled=true}
 * 时注册本实现，实现跨实例共享——任一实例签发的 ticket 可在另一实例消费。
 * 一次性语义由 {@code GETDEL} 原子取删保证；过期由 Redis TTL 自动清理（无需定时任务）。
 * Redis 不可达时 fail-fast（消费抛 401），不做内存降级——降级会掩盖"未配置 Redis 的多实例"配置缺失。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.redis.enabled", havingValue = "true")
public class RedisMonitorTicketServiceImpl implements MonitorTicketService {

    private static final String TICKET_KEY_PREFIX = "susumonitor:ticket:";
    private static final int TICKET_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration ticketTtl;
    private final Clock clock;

    /** Spring 注入统一 UTC Clock 与自动配置的 StringRedisTemplate。 */
    public RedisMonitorTicketServiceImpl(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
            AppProperties appProperties, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ticketTtl = Duration.ofSeconds(appProperties.getRedis().getTicketTtlSeconds());
        this.clock = clock;
    }

    /** 为已认证用户签发一次性 ticket，写入 Redis（SET EX，TTL 自动过期）。 */
    @Override
    public MonitorTicketVo issue(AuthenticatedUser user) {
        byte[] bytes = new byte[TICKET_BYTES];
        secureRandom.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now(clock).plus(ticketTtl);
        try {
            redisTemplate.opsForValue().set(TICKET_KEY_PREFIX + ticket,
                    objectMapper.writeValueAsString(user), ticketTtl);
        } catch (JsonProcessingException exception) {
            // 用户快照为纯值 record，理论不可达；防御性处理避免伪造 ticket 值。
            log.warn("monitor ticket serialize failed: {}", exception.getMessage());
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return new MonitorTicketVo(ticket, expiresAt.atOffset(ZoneOffset.UTC));
    }

    /** 原子消费 ticket（GETDEL 取删一体）；缺失、过期或重复使用均视为未认证。 */
    @Override
    public AuthenticatedUser consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String json = redisTemplate.opsForValue().getAndDelete(TICKET_KEY_PREFIX + ticket);
        if (json == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return objectMapper.readValue(json, AuthenticatedUser.class);
        } catch (JsonProcessingException exception) {
            log.warn("monitor ticket deserialize failed: {}", exception.getMessage());
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    /** Redis TTL 自动清理过期 ticket，无需定时任务（空实现保持接口契约）。 */
    @Override
    public void purgeExpiredTickets() {
    }
}
