package io.github.rkaushikdev.bushelworks.api.throttle;

import java.util.HashMap;
import java.util.Map;

/**
 * Fixed window counters, keyed by whatever identifies the caller.
 *
 * <p>A fixed window rather than a token bucket. The window is a minute and the counters are in
 * memory, both deliberately, for a house that runs one process on one box: there is no shared cache
 * to keep them in and there is not going to be one.
 */
public final class Limiter {

    private final int perMinute;
    private final double window;
    private final Map<String, Window> windows = new HashMap<>();

    /** A limiter allowing {@code perMinute} calls per caller in each minute window. */
    public Limiter(int perMinute) {
        this(perMinute, Window.WINDOW_SECONDS);
    }

    /**
     * A limiter allowing {@code perMinute} calls per caller in each window of {@code window}
     * seconds.
     *
     * @throws IllegalArgumentException when the limit would allow no call at all
     */
    public Limiter(int perMinute, double window) {
        if (perMinute < 1) {
            throw new IllegalArgumentException("a rate limit has to allow at least one call");
        }
        this.perMinute = perMinute;
        this.window = window;
    }

    /**
     * What came of counting one call.
     *
     * @param allowed whether the call is inside the limit
     * @param left how many more calls the window allows
     * @param resetsIn whole seconds until the window starts again
     */
    public record Verdict(boolean allowed, int left, int resetsIn) {}

    public int perMinute() {
        return perMinute;
    }

    /** Count a call from {@code key} now. */
    public Verdict check(String key) {
        return check(key, System.nanoTime() / 1_000_000_000.0);
    }

    /**
     * Count a call from {@code key} at {@code now}, seconds on a monotonic clock.
     *
     * <p>A new caller's window starts at the moment it is passed rather than on the minute. The two
     * are the same in production and are not in a test, and the test is the one that catches the
     * arithmetic.
     */
    public synchronized Verdict check(String key, double now) {
        Window counter = windows.computeIfAbsent(key, unseen -> new Window(now));
        if (counter.expired(now, window)) {
            counter.reset(now);
        }
        int hits = counter.hit();
        return new Verdict(hits <= perMinute, Math.max(0, perMinute - hits), counter.remainingSeconds(now, window));
    }

    /** Stop counting {@code key}. */
    public synchronized void forget(String key) {
        windows.remove(key);
    }

    /** How many callers are being counted. */
    public synchronized int tracked() {
        return windows.size();
    }
}
