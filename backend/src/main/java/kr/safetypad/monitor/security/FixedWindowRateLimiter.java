package kr.safetypad.monitor.security;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-process fixed-window limiter. Adequate for a single-instance pilot;
 * with multiple app instances replace by a shared store or limit at the proxy.
 */
public class FixedWindowRateLimiter {
    private record Window(long startMinute, int count) {}

    private static final int PRUNE_THRESHOLD = 10_000;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public FixedWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Current count in this minute without consuming budget. */
    public int peek(String key) {
        Window w = windows.get(key);
        return w != null && w.startMinute() == clock.millis() / 60_000 ? w.count() : 0;
    }

    /** @return true if the call is within {@code limitPerMinute}. */
    public boolean tryAcquire(String key, int limitPerMinute) {
        long minute = clock.millis() / 60_000;
        if (windows.size() > PRUNE_THRESHOLD) {
            windows.entrySet().removeIf(e -> e.getValue().startMinute() < minute);
        }
        Window w = windows.merge(key, new Window(minute, 1),
                (old, fresh) -> old.startMinute() == minute ? new Window(minute, old.count() + 1) : fresh);
        return w.count() <= limitPerMinute;
    }
}
