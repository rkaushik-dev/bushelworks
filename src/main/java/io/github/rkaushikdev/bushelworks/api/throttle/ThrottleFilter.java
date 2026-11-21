package io.github.rkaushikdev.bushelworks.api.throttle;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.RequestIdFilter;
import io.github.rkaushikdev.bushelworks.api.security.Security;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A rate limit, for the one caller that needs one.
 *
 * <p>The scale house terminal and the office cannot overload anything. The producer portal can: it
 * is on the public internet and it polls, and a script left running over a weekend has twice filled
 * the log and once filled the disk. The house turns the limit on with a number of calls a minute per
 * caller; zero, the default, leaves it off and adds nothing to any answer.
 *
 * <p>A call inside the limit is answered as usual with {@code X-RateLimit-Limit} and {@code
 * X-RateLimit-Remaining} beside it. A call past it gets a 429 saying when to try again, and never
 * reaches the route.
 */
@Component
@Order(ThrottleFilter.ORDER)
public class ThrottleFilter extends OncePerRequestFilter {

    /** Where the filter chain runs this: inside the request id, outside the counters. */
    public static final int ORDER = RequestIdFilter.ORDER + 10;

    /**
     * Paths that are never limited. A health check that gets a 429 takes the box out of the load
     * balancer, which is the opposite of what a limit is for.
     */
    public static final List<String> EXEMPT =
            List.of("/health", "/ready", "/metrics", "/openapi.json", "/docs", "/redoc");

    private final Optional<Limiter> limiter;
    private final ObjectMapper json;

    public ThrottleFilter(Settings settings, ObjectMapper json) {
        int perMinute = settings.rateLimitPerMinute();
        this.limiter = perMinute < 1 ? Optional.empty() : Optional.of(new Limiter(perMinute));
        this.json = json;
    }

    /**
     * Who to count a call against: the API key when there is one, because that identifies the
     * account rather than whatever address it happens to be behind, and otherwise the client address,
     * which is good enough for a portal.
     */
    public static String callerKey(HttpServletRequest request) {
        String key = request.getHeader(Security.HEADER);
        if (key != null && !key.isEmpty()) {
            return "key:" + key.substring(0, Math.min(8, key.length()));
        }
        String address = request.getRemoteAddr();
        return "ip:" + (address == null ? "unknown" : address);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return limiter.isEmpty() || EXEMPT.contains(RequestIdFilter.pathOf(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Limiter limit = limiter.orElseThrow();
        Limiter.Verdict verdict = limit.check(callerKey(request));
        String perMinute = String.valueOf(limit.perMinute());
        if (!verdict.allowed()) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("code", "too_many_requests");
            body.put(
                    "detail",
                    "more than " + perMinute + " calls in a minute, try again in " + verdict.resetsIn() + " seconds");
            response.setStatus(429);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(verdict.resetsIn()));
            response.setHeader("X-RateLimit-Limit", perMinute);
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), body);
            return;
        }
        // Written before the route runs, because a body that has started going out can no longer
        // take a header.
        response.setHeader("X-RateLimit-Limit", perMinute);
        response.setHeader("X-RateLimit-Remaining", String.valueOf(verdict.left()));
        chain.doFilter(request, response);
    }
}
