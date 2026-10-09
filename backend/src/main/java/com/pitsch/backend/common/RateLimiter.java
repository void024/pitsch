package com.pitsch.backend.common;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiter (per key, per minute) held in memory. It is a per-instance defence against bursts and
 * abuse; account lockout (database-backed) and monthly quotas (database-backed) are the cross-instance controls.
 * See ADR-007 for the Redis-backed option when running many instances.
 */
@Component
public class RateLimiter {

    private record Window(long minute, int count) { }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @return true if the call is allowed */
    public boolean tryAcquire(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return true;
        }
        long minute = clock.millis() / 60_000;
        Window w = windows.compute(key, (k, old) ->
                old == null || old.minute() != minute ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        return w.count() <= limitPerMinute;
    }

    public void check(String key, int limitPerMinute) {
        if (!tryAcquire(key, limitPerMinute)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many requests. Please wait a minute and try again.");
        }
    }

    @Scheduled(fixedDelay = 120_000)
    void evictOldWindows() {
        long minute = clock.millis() / 60_000;
        windows.entrySet().removeIf(e -> e.getValue().minute() < minute - 1);
    }
}
