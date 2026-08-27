package com.susumonitor.server.websocket;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.security.AuthenticatedUser;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 管理单 JVM 内存中的一次性 Monitor ticket，避免浏览器把长期 JWT 放入 WebSocket URL。
 *
 * <p>多实例化阶段一（2026-08-17）：Redis 关闭时（默认）注册本内存实现；
 * Redis 启用时由 {@link RedisMonitorTicketServiceImpl} 取代（互斥条件）。</p>
 */
@Service
@ConditionalOnProperty(name = "susumonitor.redis.enabled", havingValue = "false", matchIfMissing = true)
public class MonitorTicketServiceImpl implements MonitorTicketService {

    private static final Duration DEFAULT_TICKET_TTL = Duration.ofSeconds(30);
    private static final int TICKET_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentMap<String, TicketEntry> tickets = new ConcurrentHashMap<>();
    private final Duration ticketTtl;
    private final Clock clock;

    /** Spring 组件扫描使用统一 UTC Clock，ticket 有效期 30 秒。 */
    // 多构造器场景下明确指定生产注入入口，避免 Spring 尝试寻找无参构造器。
    @Autowired
    public MonitorTicketServiceImpl(Clock clock) {
        this(DEFAULT_TICKET_TTL, clock);
    }

    /** 包私有构造，允许测试注入 TTL 和可控 Clock。 */
    MonitorTicketServiceImpl(Duration ticketTtl, Clock clock) {
        this.ticketTtl = ticketTtl;
        this.clock = clock;
    }

    /** 为已认证用户签发一次性 ticket。 */
    public MonitorTicketVo issue(AuthenticatedUser user) {
        byte[] bytes = new byte[TICKET_BYTES];
        secureRandom.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now(clock).plus(ticketTtl);
        tickets.put(ticket, new TicketEntry(user, expiresAt));
        return new MonitorTicketVo(ticket, expiresAt.atOffset(ZoneOffset.UTC));
    }

    /** 原子消费 ticket，过期或重复使用均视为未认证。 */
    public AuthenticatedUser consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        TicketEntry entry = tickets.remove(ticket);
        if (entry == null || !entry.expiresAt().isAfter(Instant.now(clock))) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return entry.user();
    }

    /** 每分钟清理已过期但从未被消费的 ticket，防止内存无界累积。 */
    @Scheduled(fixedDelay = 60_000)
    public void purgeExpiredTickets() {
        Instant now = Instant.now(clock);
        tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private record TicketEntry(AuthenticatedUser user, Instant expiresAt) {
    }
}
