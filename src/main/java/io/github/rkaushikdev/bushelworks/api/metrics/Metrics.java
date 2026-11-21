package io.github.rkaushikdev.bushelworks.api.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Request counters and a coarse latency picture, for the box to be asked about.
 *
 * <p>Not a monitoring system. The office box has no scrape target and nobody is going to install
 * one, so this is a handful of counters in memory and one route that prints them. What it is for is
 * the telephone call that starts "it has been slow since lunchtime", which is otherwise
 * unanswerable. Everything resets when the process restarts, which is fine: the question is always
 * about today.
 */
public final class Metrics {

    private final long started = System.nanoTime();
    private int requests;
    private final Map<Integer, Integer> byStatus = new LinkedHashMap<>();
    private final Map<String, Integer> byPath = new LinkedHashMap<>();
    private double totalMs;
    private double slowestMs;
    private String slowestPath = "";

    /** Count one request to {@code path} answered with {@code status} after {@code ms} milliseconds. */
    public synchronized void record(String path, int status, double ms) {
        requests++;
        byStatus.merge(status, 1, Integer::sum);
        byPath.merge(path, 1, Integer::sum);
        totalMs += ms;
        // The first request sets the slowest even if it was too quick to measure. Comparing on the
        // number alone leaves the path blank on a fast box, which reads as "nothing has been served".
        if (slowestPath.isEmpty() || ms > slowestMs) {
            slowestMs = ms;
            slowestPath = path;
        }
    }

    /** Whole seconds since the counters started. */
    public long uptimeSeconds() {
        return (System.nanoTime() - started) / 1_000_000_000L;
    }

    public synchronized int requests() {
        return requests;
    }

    /** Requests per status, in the order each status was first seen. */
    public synchronized Map<Integer, Integer> byStatus() {
        return Map.copyOf(byStatus);
    }

    /** Requests per path. */
    public synchronized Map<String, Integer> byPath() {
        return Map.copyOf(byPath);
    }

    /** The mean time a request took, to one place; zero before the first. */
    public synchronized double averageMs() {
        return requests == 0 ? 0.0 : oneDecimal(totalMs / requests);
    }

    public synchronized double slowestMs() {
        return slowestMs;
    }

    /** The path of the slowest request so far; empty before the first. */
    public synchronized String slowestPath() {
        return slowestPath;
    }

    /** Requests the house failed on: every status of 500 and up. */
    public synchronized int errors() {
        return byStatus.entrySet().stream()
                .filter(entry -> entry.getKey() >= 500)
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    /** Everything the house said no to, every status in the 400s. Not errors; often the point. */
    public synchronized int refusals() {
        return byStatus.entrySet().stream()
                .filter(entry -> entry.getKey() >= 400 && entry.getKey() < 500)
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    /** The path called most, the first one seen winning a tie; empty before the first request. */
    public synchronized String busiestPath() {
        String busiest = "";
        int most = 0;
        for (Map.Entry<String, Integer> path : byPath.entrySet()) {
            if (path.getValue() > most) {
                most = path.getValue();
                busiest = path.getKey();
            }
        }
        return busiest;
    }

    /** Everything above as the route prints it, statuses in numeric order. */
    public synchronized Map<String, Object> asMap() {
        Map<String, Integer> statuses = new LinkedHashMap<>();
        new TreeMap<>(byStatus).forEach((status, count) -> statuses.put(String.valueOf(status), count));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uptime_seconds", uptimeSeconds());
        out.put("requests", requests);
        out.put("average_ms", averageMs());
        out.put("slowest_ms", oneDecimal(slowestMs));
        out.put("slowest_path", slowestPath);
        out.put("busiest_path", busiestPath());
        out.put("errors", errors());
        out.put("refusals", refusals());
        out.put("by_status", statuses);
        return out;
    }

    /** {@code value} to one place, a tie going to the even digit. */
    private static double oneDecimal(double value) {
        return new BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
    }
}
