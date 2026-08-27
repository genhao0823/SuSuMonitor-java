package com.susumonitor.server.websocket;

import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** 在 Agent WebSocket Upgrade 前执行客户端 IP 握手限流，并保存已解析 IP。 */
@Component
public class AgentHandshakeInterceptor implements HandshakeInterceptor {

    public static final String CLIENT_IP_ATTRIBUTE = "agentClientIp";
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private final AgentClientIpResolver clientIpResolver;
    private final int ratePerMinute;
    private final int maxTrackedClientIps;
    private final Clock clock;
    private final Map<String, TokenBucket> buckets;

    /** 注入握手 IP 解析器、启动期配置和时钟。 */
    public AgentHandshakeInterceptor(AgentClientIpResolver clientIpResolver, AppProperties appProperties, Clock clock) {
        this.clientIpResolver = clientIpResolver;
        this.ratePerMinute = appProperties.getAgent().getHandshakeRatePerMinute();
        this.maxTrackedClientIps = appProperties.getAgent().getMaxTrackedClientIps();
        this.clock = clock;
        this.buckets = new LinkedHashMap<>(16, 0.75F, true);
    }

    /** 超限时拒绝 Upgrade 并设置 Retry-After，不创建 WebSocket 会话。 */
    @Override
    public synchronized boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String clientIp;
        try {
            clientIp = clientIpResolver.resolve(request);
        } catch (IllegalArgumentException exception) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        TokenBucket bucket = buckets.get(clientIp);
        if (bucket == null) {
            if (buckets.size() >= maxTrackedClientIps) {
                Iterator<String> iterator = buckets.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
            bucket = new TokenBucket(ratePerMinute, Instant.now(clock));
            buckets.put(clientIp, bucket);
        }
        if (!bucket.tryConsume(Instant.now(clock))) {
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            response.getHeaders().set("Retry-After", "60");
            return false;
        }
        attributes.put(CLIENT_IP_ATTRIBUTE, clientIp);
        return true;
    }

    /** 握手完成后无需额外清理，桶按 LRU 数量上限管理。 */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
    }

    /** 仅供测试读取当前受跟踪 IP 数量。 */
    int trackedClientIpCount() {
        return buckets.size();
    }

    /** 单 IP 握手桶，容量与分钟补充速率相同。 */
    private static final class TokenBucket {
        private final int capacity;
        private final int ratePerMinute;
        private double tokens;
        private Instant refreshedAt;

        /** 创建容量和分钟补充速率相同的令牌桶。 */
        private TokenBucket(int ratePerMinute, Instant now) {
            this.capacity = ratePerMinute;
            this.ratePerMinute = ratePerMinute;
            this.tokens = ratePerMinute;
            this.refreshedAt = now;
        }

        /** 尝试消耗一个令牌，令牌不足时返回 false。 */
        private boolean tryConsume(Instant now) {
            long elapsedMillis = Duration.between(refreshedAt, now).toMillis();
            if (elapsedMillis > 0) {
                tokens = Math.min(capacity, tokens + elapsedMillis * (double) ratePerMinute / ONE_MINUTE.toMillis());
                refreshedAt = now;
            }
            if (tokens < 1) {
                return false;
            }
            tokens--;
            return true;
        }
    }
}
