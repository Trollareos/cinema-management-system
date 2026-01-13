package com.example.demo.ratelimit;

import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {

    public static final class Result {
        public final boolean allowed;
        public final long retryAfterSeconds;

        public Result(boolean allowed, long retryAfterSeconds) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private static final class Bucket {
        long windowStartMs;
        int count;
        long lastSeenMs;
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Simple fixed-window rate limit.
     * key: unique key (rule + user/ip)
     * limit: max requests within windowMs
     */
    public Result tryConsume(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();

        Bucket b = buckets.compute(key, (k, old) -> {
            Bucket bb = (old == null) ? new Bucket() : old;

            if (bb.windowStartMs == 0) {
                bb.windowStartMs = now;
                bb.count = 0;
            }

            // new window
            if (now - bb.windowStartMs >= windowMs) {
                bb.windowStartMs = now;
                bb.count = 0;
            }

            bb.count++;
            bb.lastSeenMs = now;
            return bb;
        });

        boolean allowed = b.count <= limit;
        long remainingMs = windowMs - (now - b.windowStartMs);
        long retryAfterSec = allowed ? 0 : Math.max(1, (remainingMs + 999) / 1000);

        // small cleanup to avoid memory growth
        maybeCleanup(now, windowMs);

        return new Result(allowed, retryAfterSec);
    }

    private void maybeCleanup(long now, long windowMs) {
        // ~1/128 probability
        if ((now & 127) != 0) return;

        long ttlMs = Math.max(windowMs * 10, 5 * 60_000L);

        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Bucket> e = it.next();
            Bucket b = e.getValue();
            if (b == null) {
                it.remove();
                continue;
            }
            if (now - b.lastSeenMs > ttlMs) {
                it.remove();
            }
        }
    }
}
