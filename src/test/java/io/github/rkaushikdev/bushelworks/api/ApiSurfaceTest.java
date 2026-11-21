package io.github.rkaushikdev.bushelworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.security.Security;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * The published surface, and the two ways it goes wrong quietly.
 *
 * <p>The first is a route that changes shape. The second is a route that is shadowed by one beside
 * it, which is worse: the path still exists, the client still calls it, and the answer comes from
 * somewhere else.
 *
 * <p>The surface written down is {@code routes.txt}, with the paths that answer without a key in
 * {@code open-paths.txt}. It is compared with the whole application as {@link BushelworksApplication}
 * starts it, serving on a port of its own, so whatever the application mounts beside the house's own
 * routes shows up here too.
 */
class ApiSurfaceTest {

    /** Every route the application answers on, written down, in the order it was written. */
    static final List<Route> ROUTES =
            written("routes.txt").stream().map(Route::of).toList();

    /** The paths that answer without a key. */
    static final List<String> OPEN_PATHS = written("open-paths.txt");

    private static final Set<String> KEYS = Set.of("k1:read:look", "k2:scale:weigh", "k3:office:office");

    /** What a path parameter is filled with to make a path that can be asked for. */
    private static final String SAMPLE = "ZZZ-1";

    /**
     * One route: a method and the path it answers on, parameters in braces.
     *
     * @param method {@code GET} or {@code POST}; {@code *} for a mapping that names no method and so
     *     answers every one
     * @param path the path pattern
     */
    record Route(String method, String path) {

        static final Comparator<Route> BY_PATH =
                Comparator.comparing(Route::path).thenComparing(Route::method);

        /** A route as {@code routes.txt} writes it: the method, a space, the path. */
        static Route of(String line) {
            String[] parts = line.split(" ", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("not a route: " + line);
            }
            return new Route(parts[0], parts[1]);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /** The lines of a resource beside this class that are neither blank nor a comment; never none. */
    static List<String> written(String name) {
        try (InputStream in = ApiSurfaceTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is not on the class path");
            }
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            if (lines.isEmpty()) {
                throw new IllegalStateException(name + " has nothing written in it");
            }
            return lines;
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * Every route the application mounts, sorted by path and then method. A route answers {@code HEAD}
     * and {@code OPTIONS} as well as its own method without saying so, and those two are left out.
     */
    static List<Route> surface(RequestMappingHandlerMapping routes) {
        List<Route> rows = new ArrayList<>();
        routes.getHandlerMethods().keySet().forEach(mapping -> {
            Set<RequestMethod> methods =
                    new HashSet<>(mapping.getMethodsCondition().getMethods());
            methods.removeAll(Set.of(RequestMethod.HEAD, RequestMethod.OPTIONS));
            for (String path : mapping.getPatternValues()) {
                if (methods.isEmpty()) {
                    rows.add(new Route("*", path));
                }
                methods.forEach(method -> rows.add(new Route(method.name(), path)));
            }
        });
        rows.sort(Route.BY_PATH);
        return rows;
    }

    /**
     * The path pattern that answers {@code method} on {@code path}, as the application's own route
     * lookup chooses it; null when nothing does.
     */
    static String answering(RequestMappingHandlerMapping routes, String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        ServletRequestPathUtils.parseAndCache(request);
        HandlerExecutionChain chain = routes.getHandler(request);
        return chain == null
                ? null
                : String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
    }

    /** {@code path} with every parameter filled in. */
    static String asked(String path) {
        return Arrays.stream(path.split("/", -1))
                .map(segment -> segment.startsWith("{") ? SAMPLE : segment)
                .collect(Collectors.joining("/"));
    }

    @Test
    void noRouteIsListedTwice() {
        assertThat(ROUTES).doesNotHaveDuplicates();
    }

    @Test
    void everyPathStartsAtTheRoot() {
        assertThat(ROUTES).allSatisfy(route -> assertThat(route.path()).startsWith("/"));
    }

    @Test
    void noPathEndsInASlash() {
        assertThat(ROUTES).filteredOn(route -> route.path().endsWith("/")).isEmpty();
    }

    @Test
    void theMethodsAreOnesTheHouseUses() {
        assertThat(ROUTES).extracting(Route::method).isSubsetOf("GET", "POST");
    }

    @Test
    void everyRouterPrefixAppearsOnTheSurface() {
        List<String> prefixes = List.of(
                "/tickets",
                "/contracts",
                "/settlements",
                "/storage",
                "/positions",
                "/sales",
                "/parties",
                "/ops",
                "/deferred",
                "/bins",
                "/desk",
                "/reports",
                "/complaints");
        for (String prefix : prefixes) {
            assertThat(ROUTES).as(prefix).anySatisfy(route -> assertThat(route.path())
                    .startsWith(prefix));
        }
    }

    /** The whole application, serving on a port of its own, its house settings given by {@link #settings}. */
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    abstract static class Serving {

        private ConfigurableApplicationContext app;
        private final HttpClient http = HttpClient.newHttpClient();

        abstract Settings settings();

        @BeforeAll
        void start() {
            app = BushelworksApplication.run(
                    settings().withDatabaseUrl(TestDatabase.URL), TestDatabase.fresh(), "--server.port=0");
        }

        @AfterAll
        void stop() {
            app.close();
        }

        RequestMappingHandlerMapping routes() {
            return app.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        }

        /** Ask for {@code path}, sending {@code key} as the API key unless it is null. */
        HttpResponse<String> get(String path, String key) {
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (key != null) {
                request.header(Security.HEADER, key);
            }
            try {
                return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while asking for " + path, interrupted);
            }
        }

        JsonNode document() throws IOException {
            HttpResponse<String> response = get("/openapi.json", null);
            assertThat(response.statusCode()).isEqualTo(200);
            return Json.mapper().readTree(response.body());
        }
    }

    /** A house that asks for no key. */
    @Nested
    class OpenHouse extends Serving {

        @Override
        Settings settings() {
            return Settings.defaults();
        }

        @Test
        void theSurfaceIsExactlyWhatWasWrittenDown() {
            assertThat(surface(routes()))
                    .containsExactlyElementsOf(
                            ROUTES.stream().sorted(Route.BY_PATH).toList());
        }

        @Test
        void nothingNewHasAppeared() {
            Set<Route> fresh = new HashSet<>(surface(routes()));
            ROUTES.forEach(fresh::remove);
            assertThat(fresh).isEmpty();
        }

        @Test
        void nothingHasGoneMissing() {
            Set<Route> missing = new HashSet<>(ROUTES);
            surface(routes()).forEach(missing::remove);
            assertThat(missing).isEmpty();
        }

        /**
         * The failure that has bitten twice: {@code /ops/audit/{document_kind}/{document_number}} used to
         * swallow {@code /ops/audit/party/X}, and {@code /bins/{location_code}/{bin_id}} swallowed {@code
         * /bins/HOME/summary}.
         *
         * <p>Each route is asked for with its parameters filled in, through the application's own route
         * lookup, and has to be the route that answers. A literal segment the lookup lets a parameter
         * beside it take is caught as the pair it is.
         */
        @Test
        void noLiteralSegmentIsShadowedByAParameter() throws Exception {
            RequestMappingHandlerMapping routes = routes();
            // The lookup reports the route that answered, not the path asked for: a word where a
            // parameter stands is taken by the parameter.
            assertThat(answering(routes, "GET", "/complaints/reports")).isEqualTo("/complaints/{reference}");
            for (Route route : surface(routes)) {
                String answered = answering(routes, route.method(), asked(route.path()));
                assertThat(answered)
                        .as("%s %s is shadowed by %s %s", route.method(), route.path(), route.method(), answered)
                        .isEqualTo(route.path());
            }
        }

        @Test
        void theSchemaDescribesEveryRoute() throws IOException {
            Set<Route> described = new HashSet<>();
            document().get("paths").properties().forEach(path -> path.getValue()
                    .fieldNames()
                    .forEachRemaining(
                            method -> described.add(new Route(method.toUpperCase(Locale.ROOT), path.getKey()))));
            List<Route> listed = ROUTES.stream()
                    .filter(route -> !OPEN_PATHS.contains(route.path()))
                    .toList();
            assertThat(listed).isNotEmpty();
            assertThat(described).containsAll(listed);
        }

        @Test
        void theSchemaNamesTheHouse() throws IOException {
            assertThat(document().get("info").get("title").asText()).isNotBlank();
        }
    }

    /** A house with a read key, a scale key and an office key on file. */
    @Nested
    class LockedHouse extends Serving {

        @Override
        Settings settings() {
            return Settings.builder().apiKeys(KEYS).build();
        }

        @Test
        void theOpenPathsAnswerWithoutAKey() {
            for (String path : OPEN_PATHS) {
                assertThat(get(path, null).statusCode()).as(path).isLessThan(400);
            }
        }

        @Test
        void everythingElseWantsAKey() {
            List<String> plain = ROUTES.stream()
                    .map(Route::path)
                    .filter(path -> !OPEN_PATHS.contains(path) && !path.contains("{"))
                    .toList();
            assertThat(plain).isNotEmpty();
            for (String path : plain) {
                assertThat(get(path, null).statusCode()).as(path).isIn(401, 403, 405);
            }
        }

        @Test
        void aReadKeyGetsIntoTheReadOnlyPlaces() {
            assertThat(get("/positions/2024-11-01", "look").statusCode()).isEqualTo(200);
        }

        @Test
        void aReadKeyIsKeptOutOfTheOffice() {
            for (String path : List.of("/complaints", "/contracts", "/tickets")) {
                assertThat(get(path, "look").statusCode()).as(path).isEqualTo(403);
            }
        }

        @Test
        void anOfficeKeyGetsInEverywhereAReadKeyDoes() {
            assertThat(get("/positions/2024-11-01", "office").statusCode()).isEqualTo(200);
        }

        @Test
        void anUnknownKeyGetsNowhere() {
            assertThat(get("/positions/2024-11-01", "guess").statusCode()).isEqualTo(401);
        }
    }
}
