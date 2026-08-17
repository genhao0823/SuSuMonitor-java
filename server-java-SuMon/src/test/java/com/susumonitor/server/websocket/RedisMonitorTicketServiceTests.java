package com.susumonitor.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 验证 Redis ticket 的签发（SET EX）、原子消费（GETDEL）与 TTL 语义。 */
class RedisMonitorTicketServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC);
    private static final String KEY_PREFIX = "susumonitor:ticket:";

    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
    // 与 Spring Boot 自动配置一致（jsr310 支持 OffsetDateTime），生产由容器注册。
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private AppProperties appProperties;
    private RedisMonitorTicketServiceImpl service;

    private final AuthenticatedUser user = new AuthenticatedUser(
            7L, "monitor", "USER", "approved",
            OffsetDateTime.parse("2026-08-17T00:00:00Z"), OffsetDateTime.parse("2026-08-17T00:00:00Z"));

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        appProperties = new AppProperties();
        appProperties.getRedis().setEnabled(true);
        appProperties.getRedis().setTicketTtlSeconds(30);
        service = new RedisMonitorTicketServiceImpl(redisTemplate, objectMapper, appProperties, CLOCK);
    }

    /** 签发：随机 ticket 写入 Redis，值为用户 JSON，TTL 30 秒，返回带 UTC 过期时间的 VO。 */
    @Test
    void issueShouldStoreUserJsonWithTtl() {
        MonitorTicketVo vo = service.issue(user);

        assertNotNull(vo.ticket());
        assertEquals(30, Duration.between(Instant.now(CLOCK), vo.expiresAt().toInstant()).toSeconds());
        verify(valueOps).set(eq(KEY_PREFIX + vo.ticket()), contains("\"username\":\"monitor\""), eq(Duration.ofSeconds(30)));
    }

    /** 消费命中：GETDEL 取回 JSON 并反序列化为用户快照。 */
    @Test
    void consumeShouldReturnUserWhenTicketExists() throws Exception {
        String json = objectMapper.writeValueAsString(user);
        when(valueOps.getAndDelete(KEY_PREFIX + "abc")).thenReturn(json);

        AuthenticatedUser loaded = service.consume("abc");

        assertEquals(7L, loaded.id());
        assertEquals("monitor", loaded.username());
        assertEquals("USER", loaded.role());
        verify(valueOps).getAndDelete(KEY_PREFIX + "abc");
    }

    /** 消费未命中（缺失/已消费/已过期）：GETDEL 返回 null → 401。 */
    @Test
    void consumeShouldRejectWhenTicketMissing() {
        when(valueOps.getAndDelete(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.consume("missing"));
        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    /** 空/空白 ticket 直接拒绝，不触达 Redis。 */
    @Test
    void consumeShouldRejectBlankTicket() {
        assertThrows(BusinessException.class, () -> service.consume(null));
        assertThrows(BusinessException.class, () -> service.consume("  "));
        verify(valueOps, never()).getAndDelete(any());
    }

    /** 损坏 JSON（理论上不可达的防御路径）→ 401。 */
    @Test
    void consumeShouldRejectCorruptJson() {
        when(valueOps.getAndDelete(any())).thenReturn("{not-json");

        BusinessException exception = assertThrows(BusinessException.class, () -> service.consume("bad"));
        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    /** Redis 实现无需定时清理（TTL 自动过期），空实现保持接口契约。 */
    @Test
    void purgeExpiredTicketsShouldBeNoop() {
        service.purgeExpiredTickets();
        verify(redisTemplate, never()).delete(anyString());
    }
}
