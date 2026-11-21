package io.github.rkaushikdev.bushelworks.api.throttle;

/**
 * One caller's counter inside one fixed window. Times are seconds on a monotonic clock.
 *
 * <p>Not thread safe on its own; the {@link Limiter} that owns it serializes every use.
 */
public final class Window {

    /** How long a window lasts: a minute. */
    public static final double WINDOW_SECONDS = 60.0;

    private double started;
    private int hits;

    /** A window opening at {@code started}, with nothing counted yet. */
    public Window(double started) {
        this.started = started;
    }

    public double started() {
        return started;
    }

    /** How many calls this window has counted. */
    public int hits() {
        return hits;
    }

    /** Count one more call. */
    int hit() {
        return ++hits;
    }

    /** Whether a minute window has run out by {@code now}. */
    public boolean expired(double now) {
        return expired(now, WINDOW_SECONDS);
    }

    /** Whether a window of {@code length} seconds has run out by {@code now}. */
    public boolean expired(double now, double length) {
        return now - started >= length;
    }

    /** Start the window again at {@code now}, with nothing counted. */
    public void reset(double now) {
        started = now;
        hits = 0;
    }

    /** Whole seconds until a minute window ends, never below zero. */
    public int remainingSeconds(double now) {
        return remainingSeconds(now, WINDOW_SECONDS);
    }

    /** Whole seconds until a window of {@code length} seconds ends, to the nearest, never below zero. */
    public int remainingSeconds(double now, double length) {
        return Math.max(0, (int) Math.rint(started + length - now));
    }
}
