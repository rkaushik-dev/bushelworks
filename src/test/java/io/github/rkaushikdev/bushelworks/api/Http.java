package io.github.rkaushikdev.bushelworks.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.bushelworks.api.security.Security;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A small client over MockMvc for the API tests: send a request, read the status, the headers and
 * the body back. Query parameters go in as name and value pairs after the path:
 *
 * <pre>{@code
 * Http client = new Http(mvc);
 * client.postJson("/tickets", aTicket());
 * client.post("/tickets/HOME/A-10441/void", "reason", "reweighed").json().get("voided");
 * client.as(READ_KEY).get("/positions/2024-11-01").status();
 * }</pre>
 *
 * <p>Bodies are written as plain maps and lists, the way a client builds them, with {@link #object}
 * and {@link #with}.
 */
public final class Http {

    static final ObjectMapper JSON = Json.mapper();

    private final MockMvc mvc;
    private final Map<String, String> headers;

    public Http(MockMvc mvc) {
        this(mvc, Map.of());
    }

    private Http(MockMvc mvc, Map<String, String> headers) {
        this.mvc = mvc;
        this.headers = headers;
    }

    /** The same client, sending {@code key} as the API key on every request. */
    public Http as(String key) {
        return header(Security.HEADER, key);
    }

    /** The same client, sending one more header on every request. */
    public Http header(String name, String value) {
        Map<String, String> more = new LinkedHashMap<>(headers);
        more.put(name, value);
        return new Http(mvc, more);
    }

    public Answer get(String path, Object... query) {
        return send(withQuery(MockMvcRequestBuilders.get(path), query));
    }

    public Answer post(String path, Object... query) {
        return send(withQuery(MockMvcRequestBuilders.post(path), query));
    }

    public Answer postJson(String path, Object body, Object... query) {
        return send(withQuery(MockMvcRequestBuilders.post(path), query)
                .contentType(MediaType.APPLICATION_JSON)
                .content(write(body)));
    }

    /** Send a request built by hand, with this client's headers. */
    public Answer send(MockHttpServletRequestBuilder request) {
        headers.forEach(request::header);
        try {
            return new Answer(mvc.perform(request).andReturn());
        } catch (Exception failed) {
            throw new IllegalStateException("the request did not complete", failed);
        }
    }

    /** A JSON object from names and values in turn, in that order. */
    public static Map<String, Object> object(Object... pairs) {
        return with(Map.of(), pairs);
    }

    /** A copy of {@code base} with the names and values in turn put over it. */
    public static Map<String, Object> with(Map<String, Object> base, Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (int at = 0; at + 1 < pairs.length; at += 2) {
            out.put((String) pairs[at], pairs[at + 1]);
        }
        return out;
    }

    /** A copy of {@code base} without the names given. */
    public static Map<String, Object> without(Map<String, Object> base, String... names) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        Arrays.asList(names).forEach(out::remove);
        return out;
    }

    /** A JSON list; unlike {@code List.of}, it may be empty or hold anything. */
    public static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    static String write(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    private static MockHttpServletRequestBuilder withQuery(MockHttpServletRequestBuilder request, Object... query) {
        for (int at = 0; at + 1 < query.length; at += 2) {
            request.queryParam((String) query[at], String.valueOf(query[at + 1]));
        }
        return request;
    }

    /** What came back. */
    public record Answer(MvcResult result) {

        public int status() {
            return result.getResponse().getStatus();
        }

        public String text() {
            return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            try {
                return JSON.readTree(text());
            } catch (JsonProcessingException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }

        public String header(String name) {
            return result.getResponse().getHeader(name);
        }

        public boolean hasHeader(String name) {
            return result.getResponse().containsHeader(name);
        }

        public String contentType() {
            return header(HttpHeaders.CONTENT_TYPE);
        }
    }
}
