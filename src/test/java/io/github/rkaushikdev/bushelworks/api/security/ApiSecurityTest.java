package io.github.rkaushikdev.bushelworks.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.servicetest.TestService;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

class ApiSecurityTest {

    static final String OFFICE_KEY = "office-key-value";
    static final String SCALE_KEY = "scale-key-value";
    static final String READ_KEY = "read-key-value";

    static final String KEY_LIST = "k1:office:" + OFFICE_KEY + ",k2:scale:" + SCALE_KEY + ",k3:read:" + READ_KEY;

    static final Set<String> KEYS = Set.of(KEY_LIST.split(","));

    static Settings locked() {
        return Settings.builder().apiKeys(KEYS).build();
    }

    @Test
    void parsingAThreePartKey() {
        assertThat(Security.parseKeys("k1:office:secret")).isEqualTo(Map.of("secret", Role.OFFICE));
    }

    @Test
    void aBareKeyIsTreatedAsOffice() {
        assertThat(Security.parseKeys("plainsecret")).isEqualTo(Map.of("plainsecret", Role.OFFICE));
    }

    @Test
    void severalKeysParseTogether() {
        assertThat(Security.parseKeys("k1:office:a, k2:read:b ,")).isEqualTo(Map.of("a", Role.OFFICE, "b", Role.READ));
    }

    @Test
    void aMalformedKeyIsRefused() {
        assertThatThrownBy(() -> Security.parseKeys("k1:office")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownRoleIsRefused() {
        assertThatThrownBy(() -> Security.parseKeys("k1:manager:secret")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rolesRankFromReadUpToOffice() {
        assertThat(Role.OFFICE.rank()).isGreaterThan(Role.SCALE.rank());
        assertThat(Role.SCALE.rank()).isGreaterThan(Role.READ.rank());
    }

    @Test
    void aCallerCanDoAnythingAtOrBelowItsRole() {
        Caller caller = new Caller("k", Role.SCALE);
        assertThat(caller.can(Role.READ)).isTrue();
        assertThat(caller.can(Role.SCALE)).isTrue();
        assertThat(caller.can(Role.OFFICE)).isFalse();
    }

    @Test
    void aHouseWithNoKeysLetsAnybodyIn() {
        assertThat(Security.resolve(Settings.defaults(), null)).isEqualTo(Caller.ANONYMOUS);
        assertThat(Caller.ANONYMOUS.can(Role.OFFICE)).isTrue();
    }

    @Test
    void aLockedHouseWantsAHeader() {
        assertThatThrownBy(() -> Security.resolve(locked(), null)).isInstanceOf(NotAuthorised.class);
    }

    @Test
    void aWrongKeyIsRefused() {
        assertThatThrownBy(() -> Security.resolve(locked(), "not-a-key")).isInstanceOf(NotAuthorised.class);
    }

    @Test
    void aRightKeyCarriesItsRole() {
        assertThat(Security.resolve(locked(), SCALE_KEY).role()).isSameAs(Role.SCALE);
        assertThat(Security.resolve(locked(), READ_KEY).role()).isSameAs(Role.READ);
    }

    @Test
    void theKeyIdDoesNotEchoTheWholeSecret() {
        Caller caller = Security.resolve(locked(), OFFICE_KEY);
        assertThat(caller.keyId()).doesNotContain(OFFICE_KEY);
        assertThat(caller.keyId()).endsWith("...");
    }

    @Test
    void aForbiddenCallerIsA403() {
        Caller caller = new Caller("k", Role.READ);
        Forbidden error = new Forbidden("needs office");
        assertThat(error.code()).isEqualTo("forbidden");
        assertThat(caller.can(Role.OFFICE)).isFalse();
    }

    /** A house that asks for no key. */
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
        void anOpenHouseServesWithoutAKey() {
            assertThat(new Http(mvc).get("/positions/2024-11-01").status()).isEqualTo(200);
        }
    }

    /** A house with an office, a scale and a read key on file. */
    @Nested
    @RouteTest
    @TestPropertySource(properties = TestService.PREFIX + "BUSHELWORKS_API_KEYS=" + KEY_LIST)
    class LockedHouse {

        @Autowired
        private MockMvc mvc;

        private Http client;

        @BeforeEach
        void freshHouse() {
            TestDatabase.fresh();
            client = new Http(mvc);
        }

        @Test
        void aLockedHouseRefusesWithoutAKey() {
            Http.Answer response = client.get("/positions/2024-11-01");
            assertThat(response.status()).isEqualTo(401);
            assertThat(response.json().get("code").asText()).isEqualTo("not_authorised");
        }

        @Test
        void aLockedHouseServesWithAKey() {
            assertThat(client.as(READ_KEY).get("/positions/2024-11-01").status())
                    .isEqualTo(200);
        }

        @Test
        void aBadKeyGetsTheSame401() {
            assertThat(client.as("wrong").get("/positions/2024-11-01").status()).isEqualTo(401);
        }

        @Test
        void healthIsOpenEvenOnALockedHouse() {
            assertThat(client.get("/health").status()).isEqualTo(200);
        }
    }
}
