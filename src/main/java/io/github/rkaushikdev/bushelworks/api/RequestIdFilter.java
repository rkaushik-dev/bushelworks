package io.github.rkaushikdev.bushelworks.api;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.HouseLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Stamps every request so a log line and an error body can be tied together.
 *
 * <p>The scale house calls with a problem and reads the id off the screen. It is the only thing that
 * makes an error report actionable at harvest, when there are nine hundred requests an hour and they
 * all look the same. A caller that sends {@code x-request-id} keeps its own id; anybody else gets
 * sixteen hex digits.
 *
 * <p>The same id goes into the logging context, so every line a request writes carries it without
 * any of the routes having to pass it down. Each request is logged as {@code request.served} once it
 * is answered, or as {@code request.failed} when it ends in an exception nobody handled.
 *
 * <p>This is the outermost of the service's filters: the rate limit and the counters run inside it,
 * so a refusal for going too fast carries an id like any other answer.
 */
@Component
@Order(RequestIdFilter.ORDER)
public class RequestIdFilter extends OncePerRequestFilter {

    /** Where the filter chain runs this: after the container's own filters, before the service's others. */
    public static final int ORDER = 0;

    /** The header the id is read from and written to. */
    public static final String HEADER = "x-request-id";

    static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    private static final Logger LOG = HouseLog.getLogger("api");
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    /** The id of the request being served, when it went through this filter. */
    public static Optional<String> of(HttpServletRequest request) {
        return Optional.ofNullable((String) request.getAttribute(ATTRIBUTE));
    }

    /** The path a request asked for, decoded, as the logs and the counters record it. */
    public static String pathOf(HttpServletRequest request) {
        return PATHS.getPathWithinApplication(request);
    }

    /** Whole milliseconds since {@code started}, a {@link System#nanoTime()} reading, a half going to even. */
    public static long millisSince(long started) {
        return (long) Math.rint((System.nanoTime() - started) / 1_000_000.0);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming == null || incoming.isEmpty() ? fresh() : incoming;
        request.setAttribute(ATTRIBUTE, id);
        // Written before the route runs, because a body that has started going out can no longer
        // take a header.
        response.setHeader(HEADER, id);
        HouseLog.setRequestId(id);
        long started = System.nanoTime();
        String path = pathOf(request);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failed) {
            HouseLog.failure(
                    LOG,
                    "request.failed",
                    field("method", request.getMethod()),
                    field("path", path),
                    field("ms", millisSince(started)));
            throw failed;
        } finally {
            HouseLog.setRequestId(null);
        }
        HouseLog.event(
                LOG,
                "request.served",
                field("method", request.getMethod()),
                field("path", path),
                field("status", response.getStatus()),
                field("ms", millisSince(started)),
                field("request_id", id));
    }

    private static String fresh() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
