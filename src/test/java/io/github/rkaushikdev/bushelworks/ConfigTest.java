package io.github.rkaushikdev.bushelworks;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigTest {

    private final Map<String, String> environment = new HashMap<>();

    private Settings load() {
        return Settings.load(environment::get);
    }

    @Test
    void defaultsWorkWithNothingSet() {
        Settings settings = load();
        assertThat(settings.isEmbedded()).isTrue();
        assertThat(settings.stateCode()).isEqualTo("KS");
        assertThat(settings.requiresApiKey()).isFalse();
    }

    @Test
    void theDatabaseUrlComesFromTheEnvironment() {
        environment.put("BUSHELWORKS_DATABASE_URL", "jdbc:postgresql://x/y");
        Settings settings = load();
        assertThat(settings.databaseUrl()).isEqualTo("jdbc:postgresql://x/y");
        assertThat(settings.isEmbedded()).isFalse();
    }

    @Test
    void aStateCodeIsUpperCased() {
        environment.put("BUSHELWORKS_STATE_CODE", "ne");
        assertThat(load().stateCode()).isEqualTo("NE");
    }

    @Test
    void aBadStateCodeIsRefused() {
        environment.put("BUSHELWORKS_STATE_CODE", "KANSAS");
        assertThatThrownBy(this::load).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void booleansAcceptTheUsualSpellings() {
        Map<String, Boolean> spellings = Map.of(
                "1", true, "true", true, "YES", true, "on", true, "0", false, "false", false, "no", false, "off",
                false);
        spellings.forEach((raw, expected) -> {
            environment.put("BUSHELWORKS_ECHO_SQL", raw);
            assertThat(load().echoSql()).as(raw).isEqualTo(expected);
        });
    }

    @Test
    void aNonsenseBooleanIsRefused() {
        environment.put("BUSHELWORKS_ECHO_SQL", "maybe");
        assertThatThrownBy(this::load).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aNonsenseNumberIsRefused() {
        environment.put("BUSHELWORKS_MAX_PAGE_SIZE", "lots");
        assertThatThrownBy(this::load).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aPageSizeBelowOneIsRefused() {
        assertThatThrownBy(() -> Settings.builder().maxPageSize(0).build()).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void apiKeysAreSplitAndTrimmed() {
        environment.put("BUSHELWORKS_API_KEYS", " one , two ,, three ");
        Settings settings = load();
        assertThat(settings.apiKeys()).isEqualTo(Set.of("one", "two", "three"));
        assertThat(settings.requiresApiKey()).isTrue();
    }

    @Test
    void theMinimumSettlementIsADecimal() {
        environment.put("BUSHELWORKS_MINIMUM_SETTLEMENT", "25.00");
        assertThat(load().minimumSettlement()).isEqualTo(d("25.00"));
    }

    @Test
    void aNegativeMinimumSettlementIsRefused() {
        assertThatThrownBy(() -> Settings.builder().minimumSettlement(d("-1")).build())
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void anEmptyDatabaseUrlIsRefused() {
        assertThatThrownBy(() -> Settings.builder().databaseUrl("").build()).isInstanceOf(ConfigurationError.class);
    }
}
