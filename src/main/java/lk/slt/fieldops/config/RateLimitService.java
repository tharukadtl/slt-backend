package lk.slt.fieldops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fixed-window rate limiter — 100 requests/minute per key (NFR 7.1.3).
 *
 * Keyed by "user:{id}" for authenticated requests and "ip:{address}" for
 * unauthenticated ones (pre-auth endpoints like login/register, or requests
 * with no/invalid Bearer token), so brute-force and credential-stuffing
 * attempts against auth endpoints are capped just like everything else.
 *
 * KNOWN LIMITATION: single-instance only — windows live in local heap memory,
 * so a multi-node deployment would let each node grant its own 100/minute,
 * effectively multiplying the cap by node count. Fixing that needs a shared
 * store (e.g. Redis) behind tryAcquire; out of scope here, this only fixes
 * which requests get rate-limited, not how the counter is stored/shared.
 */
@Component
public class RateLimitService {

    @Value("${app.rate-limit.max-requests-per-minute:100}")
    private int maxRequestsPerMinute;

    private static final long WINDOW_MILLIS = 60_000L;

    private static class Window {
        final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        final AtomicInteger count = new AtomicInteger(0);
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** Returns true if the request is allowed; false if the key has exceeded the per-minute cap. */
    public boolean tryAcquire(String key) {
        Window w = windows.computeIfAbsent(key, id -> new Window());
        long now = System.currentTimeMillis();
        long start = w.windowStart.get();

        if (now - start >= WINDOW_MILLIS) {
            // Roll over to a new window — only one thread wins the reset race.
            if (w.windowStart.compareAndSet(start, now)) {
                w.count.set(0);
            }
        }

        return w.count.incrementAndGet() <= maxRequestsPerMinute;
    }
}
