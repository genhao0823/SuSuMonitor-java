package com.susumonitor.server.common.limit;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 基于 Redis 的固定窗口计数器：Lua 脚本原子执行 INCR 与首次 EXPIRE，
 * 计数跨实例共享；各限流器以不同 key 前缀与阈值复用本实现。
 *
 * <p>原子脚本消除了手写版 INCR/EXPIRE 两步写入的首窗口竞态
 * （进程在两步间失败会导致计数 key 永不过期）。</p>
 */
public class RedisFixedWindowRateLimiter {

    /** 原子 INCR + 首次 EXPIRE：窗口起点为首个请求，TTL 到期自动清理计数。 */
    private static final RedisScript<Long> INCR_WITH_WINDOW = RedisScript.of(
            "local count = redis.call('INCR', KEYS[1]) "
                    + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return count", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final long maxRequests;
    private final long windowSeconds;

    /** 注入 Redis 模板、计数 key 前缀与窗口参数。 */
    public RedisFixedWindowRateLimiter(StringRedisTemplate redisTemplate, String keyPrefix,
            long maxRequests, long windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    /**
     * 为 key 记录一次尝试（检查即计数）。
     *
     * @param key 限流维度标识（如客户端 IP 或管理员 ID 字符串）
     * @return 窗口内未超过 maxRequests 时为 true；Redis 返回 null（不可用）时放行
     */
    public boolean tryAcquire(String key) {
        Long count = redisTemplate.execute(INCR_WITH_WINDOW, List.of(keyPrefix + key),
                String.valueOf(windowSeconds));
        return count == null || count <= maxRequests;
    }
}
