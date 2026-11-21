package io.github.rkaushikdev.bushelworks.api.throttle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConfigurationError;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.servicetest.TestService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

class ApiThrottleTest {

    @Test
    void aLimitBelowOneIsRefused() {
        assertThatThrownBy(() -> new Limiter(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNegativeLimitIsRefusedInSettings() {
        assertThatThrownBy(() -> Settings.builder().rateLimitPerMinute(-1).build())
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void theLimitIsOffByDefault() {
        assertThat(Settings.defaults().rateLimitPerMinute()).isZero();
    }

    @Test
    void theLimitReadsFromTheEnvironment() {
        Map<String, String> environment = Map.of("BUSHELWORKS_RATE_LIMIT_PER_MINUTE", "120");
        assertThat(Settings.load(environment::get).rateLimitPerMinute()).isEqualTo(120);
    }

    @Test
    void aWindowExpiresAfterItsLength() {
        Window window = new Window(0.0);
        assertThat(window.expired(30.0)).isFalse();
        assertThat(window.expired(Window.WINDOW_SECONDS)).isTrue();
    }

    @Test
    void aWindowReportsHowLongIsLeft() {
        assertThat(new Window(0.0).remainingSeconds(20.0)).isEqualTo(40);
    }

    @Test
    void theLimiterCountsInsideTheWindow() {
        Limiter limiter = new Limiter(3);
        for (int expectedLeft : new int[] {2, 1, 0}) {
            Limiter.Verdict verdict = limiter.check("a", 0.0);
            assertThat(verdict.allowed()).isTrue();
            assertThat(verdict.left()).isEqualTo(expectedLeft);
        }
    }

    @Test
    void theLimiterRefusesPastTheLimit() {
        Limiter limiter = new Limiter(2);
        limiter.check("a", 0.0);
        limiter.check("a", 0.0);
        Limiter.Verdict verdict = limiter.check("a", 0.0);
        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.left()).isZero();
        assertThat(verdict.resetsIn()).isEqualTo(60);
    }

    @Test
    void aNewWindowStartsTheCountAgain() {
        Limiter limiter = new Limiter(1);
        assertThat(limiter.check("a", 0.0).allowed()).isTrue();
        assertThat(limiter.check("a", 10.0).allowed()).isFalse();
        assertThat(limiter.check("a", 70.0).allowed()).isTrue();
    }

    @Test
    void callersAreCountedSeparately() {
        Limiter limiter = new Limiter(1);
        assertThat(limiter.check("a", 0.0).allowed()).isTrue();
        assertThat(limiter.check("b", 0.0).allowed()).isTrue();
        assertThat(limiter.tracked()).isEqualTo(2);
    }

    @Test
    void aCallerCanBeForgotten() {
        Limiter limiter = new Limiter(1);
        limiter.check("a", 0.0);
        limiter.forget("a");
        assertThat(limiter.tracked()).isZero();
    }

    @Test
    void theExemptPathsAreTheOnesALoadBalancerCalls() {
        assertThat(ThrottleFilter.EXEMPT).contains("/health", "/ready");
    }

    /** A house with the limit off, as it ships. */
    @Nested
    @RouteTest
    class OpenHouse {

        @Autowired
        private MockMvc mvc;

        @BeforeEach
        void freshHouse() {
            TestDatabase.fresh();
        }

        @Test
        void anUnlimitedHouseAddsNoHeaders() {
            Http.Answer response = new Http(mvc).get("/positions/2024-11-01");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.hasHeader("X-RateLimit-Limit")).isFalse();
        }
    }

    /** A house allowing three calls a minute, its counters fresh for every test. */
    @Nested
    @RouteTest
    @TestPropertySource(properties = TestService.PREFIX + "BUSHELWORKS_RATE_LIMIT_PER_MINUTE=3")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
    class LimitedHouse {

        @Autowired
        private MockMvc mvc;

        private Http client;

        @BeforeEach
        void freshHouse() {
            TestDatabase.fresh();
            client = new Http(mvc);
        }

        @Test
        void aLimitedHouseReportsWhatIsLeft() {
            Http.Answer response = client.get("/positions/2024-11-01");
            assertThat(response.header("X-RateLimit-Limit")).isEqualTo("3");
            assertThat(response.header("X-RateLimit-Remaining")).isEqualTo("2");
        }

        @Test
        void goingPastTheLimitIsA429() {
            for (int call = 0; call < 3; call++) {
                client.get("/positions/2024-11-01");
            }
            Http.Answer response = client.get("/positions/2024-11-01");
            assertThat(response.status()).isEqualTo(429);
            assertThat(response.json().get("code").asText()).isEqualTo("too_many_requests");
            assertThat(response.header("Retry-After")).isNotEmpty();
        }

        @Test
        void theRefusalSaysWhatTheLimitIs() {
            Http.Answer response = null;
            for (int call = 0; call < 4; call++) {
                response = client.get("/positions/2024-11-01");
            }
            assertThat(response.json().get("detail").asText()).contains("more than 3 calls in a minute");
        }

        @Test
        void healthIsNeverLimited() {
            for (int call = 0; call < 10; call++) {
                assertThat(client.get("/health").status()).isEqualTo(200);
            }
        }
    }
}
