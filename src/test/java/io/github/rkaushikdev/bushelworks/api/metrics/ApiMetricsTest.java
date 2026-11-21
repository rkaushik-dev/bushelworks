package io.github.rkaushikdev.bushelworks.api.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.api.throttle.ThrottleFilter;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

class ApiMetricsTest {

    @Test
    void aNewCounterHasSeenNothing() {
        Metrics metrics = new Metrics();
        assertThat(metrics.requests()).isZero();
        assertThat(metrics.averageMs()).isZero();
        assertThat(metrics.busiestPath()).isEmpty();
    }

    @Test
    void recordingCountsTheRequest() {
        Metrics metrics = new Metrics();
        metrics.record("/tickets", 201, 12.0);
        assertThat(metrics.requests()).isEqualTo(1);
        assertThat(metrics.byStatus().get(201)).isEqualTo(1);
        assertThat(metrics.byPath().get("/tickets")).isEqualTo(1);
    }

    @Test
    void theAverageIsOverTheRequests() {
        Metrics metrics = new Metrics();
        metrics.record("/a", 200, 10.0);
        metrics.record("/a", 200, 20.0);
        assertThat(metrics.averageMs()).isEqualTo(15.0);
    }

    @Test
    void theSlowestRequestIsRemembered() {
        Metrics metrics = new Metrics();
        metrics.record("/a", 200, 10.0);
        metrics.record("/settlements", 201, 90.0);
        metrics.record("/b", 200, 20.0);
        assertThat(metrics.slowestMs()).isEqualTo(90.0);
        assertThat(metrics.slowestPath()).isEqualTo("/settlements");
    }

    @Test
    void theBusiestPathIsTheOneCalledMost() {
        Metrics metrics = new Metrics();
        for (int call = 0; call < 3; call++) {
            metrics.record("/tickets", 201, 5.0);
        }
        metrics.record("/positions", 200, 5.0);
        assertThat(metrics.busiestPath()).isEqualTo("/tickets");
    }

    @Test
    void serverErrorsAreCountedSeparately() {
        Metrics metrics = new Metrics();
        metrics.record("/a", 500, 5.0);
        metrics.record("/a", 200, 5.0);
        assertThat(metrics.errors()).isEqualTo(1);
        assertThat(metrics.refusals()).isZero();
    }

    @Test
    void refusalsAreNotErrors() {
        Metrics metrics = new Metrics();
        metrics.record("/a", 409, 5.0);
        metrics.record("/a", 422, 5.0);
        assertThat(metrics.refusals()).isEqualTo(2);
        assertThat(metrics.errors()).isZero();
    }

    @Test
    void theUptimeIsCountedInSeconds() {
        assertThat(new Metrics().uptimeSeconds()).isNotNegative();
    }

    @Test
    void metricsIsNeverRateLimited() {
        assertThat(ThrottleFilter.EXEMPT).contains("/metrics");
    }

    /** The counters as the route prints them, from a process that has served nothing else. */
    @Nested
    @RouteTest
    @DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
    class Served {

        @Autowired
        private MockMvc mvc;

        private Http client;

        @BeforeEach
        void freshHouse() {
            TestDatabase.fresh();
            client = new Http(mvc);
        }

        @Test
        void theEndpointReportsWhatHasHappened() {
            client.get("/health");
            JsonNode body = client.get("/metrics").json();
            assertThat(body.get("requests").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(body.has("by_status")).isTrue();
            assertThat(body.get("by_status").get("200").asInt()).isGreaterThanOrEqualTo(1);
        }

        @Test
        void theEndpointCountsItself() {
            int first = client.get("/metrics").json().get("requests").asInt();
            int second = client.get("/metrics").json().get("requests").asInt();
            assertThat(second).isGreaterThan(first);
        }

        @Test
        void aRefusedCallIsCountedAsARefusal() {
            client.get("/tickets/HOME/A-99999");
            JsonNode body = client.get("/metrics").json();
            assertThat(body.get("refusals").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(body.get("by_status").get("404").asInt()).isGreaterThanOrEqualTo(1);
        }

        @Test
        void theBusiestPathShowsUpInTheBody() {
            for (int call = 0; call < 3; call++) {
                client.get("/health");
            }
            JsonNode body = client.get("/metrics").json();
            assertThat(body.get("busiest_path").asText()).isEqualTo("/health");
        }

        @Test
        void theSlowestPathShowsUpInTheBody() {
            client.get("/health");
            JsonNode body = client.get("/metrics").json();
            assertThat(body.get("slowest_path").asText()).isNotEmpty();
            assertThat(body.get("slowest_ms").asDouble()).isNotNegative();
        }

        @Test
        void theMetricsRouteIsDocumented() {
            JsonNode paths = client.get("/openapi.json").json().get("paths");
            assertThat(paths.has("/metrics")).isTrue();
        }
    }
}
