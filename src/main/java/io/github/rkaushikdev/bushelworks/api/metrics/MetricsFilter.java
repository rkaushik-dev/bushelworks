package io.github.rkaushikdev.bushelworks.api.metrics;

import io.github.rkaushikdev.bushelworks.api.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts every request into the {@link Metrics}, including the ones that failed: an exception
 * nobody answered counts as a 500. It runs inside the rate limit, so a call refused for going too
 * fast is not counted.
 */
@Component
@Order(MetricsFilter.ORDER)
public class MetricsFilter extends OncePerRequestFilter {

    /** Where the filter chain runs this: inside the request id and the rate limit. */
    public static final int ORDER = RequestIdFilter.ORDER + 20;

    private final Metrics metrics;

    public MetricsFilter(Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        String path = RequestIdFilter.pathOf(request);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failed) {
            metrics.record(path, 500, millisSince(started));
            throw failed;
        }
        metrics.record(path, response.getStatus(), millisSince(started));
    }

    private static double millisSince(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
    }
}
