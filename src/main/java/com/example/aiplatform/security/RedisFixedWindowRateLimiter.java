package com.example.aiplatform.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A shared, self-expiring fixed-window counter in Redis.
 *
 * <p>Replaces the in-memory Resilience4j registry that previously backed rate
 * limiting, which had two problems that only appeared in the shapes production
 * actually takes.
 *
 * <p><b>It was per-instance.</b> Every replica kept its own counters, so N
 * replicas behind a load balancer permitted N times the configured budget. A
 * limit that quietly scales with your deployment is not a limit.
 *
 * <p><b>It grew without bound.</b> {@code registry.rateLimiter(key, config)}
 * creates a limiter per distinct key and never evicts one. Unique keys - and
 * the unauthenticated path keyed by remote IP in particular - accumulated
 * forever, making the defense against resource exhaustion into a slow leak of
 * its own. Redis keys here carry a TTL, so a window that stops being written to
 * simply disappears.
 *
 * <p>INCR-then-EXPIRE has a well-known hazard: if the process dies between the
 * two commands the key never expires and the caller is limited forever. Running
 * both inside one Lua script removes it - Redis executes the script atomically,
 * so the TTL is always set on the same call that created the key.
 *
 * <p>A fixed window (rather than a sliding one or a token bucket) permits up to
 * 2x the limit across a window boundary. That is a known and accepted
 * imprecision: this exists to stop a runaway script or a compromised credential
 * from hammering billed LLM endpoints, not to meter traffic exactly.
 */
@Component
public class RedisFixedWindowRateLimiter {

    /**
     * KEYS[1] the counter key, ARGV[1] the window length in seconds.
     * Sets the TTL only when the counter is first created, so the window is
     * fixed from its first request rather than sliding forward on every hit.
     * Returns the post-increment count.
     */
    private static final RedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    /**
     * KEYS[1] the counter key, ARGV[1] the amount to add, ARGV[2] the window
     * length in seconds. The INCRBY sibling of the script above, for budgets
     * denominated in something other than "one request" - token spend, in
     * practice. Same atomicity guarantee and the same reason for it: the TTL is
     * set on the call that created the key, so a process dying between the two
     * commands cannot leave a caller counted against forever.
     */
    private static final RedisScript<Long> ADD_SCRIPT = new DefaultRedisScript<>(
            """
            local total = redis.call('INCRBY', KEYS[1], ARGV[1])
            if total == tonumber(ARGV[1]) then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return total
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisFixedWindowRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Records one hit against {@code key} and reports whether the caller is
     * still within {@code limit} for the current window.
     *
     * <p>Fails OPEN: if Redis is unreachable this returns true rather than
     * rejecting the request. Rate limiting is a protective measure, not an
     * authorization decision - taking the whole API down because the limiter's
     * backing store is unavailable would turn a degraded dependency into an
     * outage. Note the contrast with {@code OwnerScopedChatMemory}, which fails
     * CLOSED, because that one IS an authorization decision.
     */
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        Long count;
        try {
            count = redisTemplate.execute(INCREMENT_SCRIPT, List.of(key), Integer.toString(windowSeconds));
        } catch (RuntimeException e) {
            return true;
        }
        return count == null || count <= limit;
    }

    /**
     * Adds {@code amount} to {@code key}'s window and returns the new total.
     *
     * <p>Fails OPEN the same way {@link #tryAcquire} does, and reports 0 when
     * it does - an unreachable Redis must degrade to "unmetered", never to
     * "everyone is over budget". Note the asymmetry that makes this safe: a
     * failed <em>record</em> under-counts spend, while a failed <em>check</em>
     * would over-reject requests, and only one of those is recoverable by
     * looking at the bill afterwards.
     */
    public long addAndGet(String key, long amount, int windowSeconds) {
        if (amount <= 0) {
            return currentCount(key);
        }
        try {
            Long total = redisTemplate.execute(
                    ADD_SCRIPT, List.of(key), Long.toString(amount), Integer.toString(windowSeconds));
            return total == null ? 0 : total;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Current count without incrementing - for checks that must not consume budget. */
    public long currentCount(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public void reset(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            // Best effort - a stale counter expires on its own.
        }
    }
}
