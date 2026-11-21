package io.github.rkaushikdev.bushelworks.api.routes;

import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static io.github.rkaushikdev.bushelworks.api.Http.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@RouteTest
class ApiContractsTest {

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void freshHouse() {
        TestDatabase.fresh();
        client = new Http(mvc);
    }

    static Map<String, Object> forward(Object... overrides) {
        Map<String, Object> body = object(
                "number", "F-2024-118",
                "kind", "forward",
                "party_code", "MERRICK",
                "commodity_code", "CORN",
                "location_code", "HOME",
                "written_on", "2024-05-14",
                "bushels", "20000",
                "window_opens", "2024-10-01",
                "window_closes", "2024-11-30",
                "flat_price", "4.6200");
        return with(body, overrides);
    }

    /** A hedge to arrive on December corn at 4.85, its basis still open. */
    static Map<String, Object> hta() {
        return forward(
                "number", "H-2024-042",
                "kind", "hta",
                "flat_price", null,
                "futures_month", "Z",
                "futures_year", 2024,
                "futures_price", "4.8500");
    }

    /** A basis contract on December corn at thirty under, its futures still open. */
    static Map<String, Object> basisContract() {
        return forward(
                "number", "B-2024-009",
                "kind", "basis",
                "flat_price", null,
                "futures_month", "Z",
                "futures_year", 2024,
                "basis", "-0.3000");
    }

    private static Map<String, Object> price(String value, String on) {
        return object("value", value, "on", on);
    }

    private static Map<String, Object> roll(String monthCode, int year, String spread) {
        return object("month_code", monthCode, "year", year, "spread", spread);
    }

    @Test
    void writingAForwardPricesItImmediately() {
        Http.Answer response = client.postJson("/contracts", forward());
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("is_fully_priced").asBoolean()).isTrue();
        assertThat(body.get("is_fully_priced").isBoolean()).isTrue();
        assertThat(body.get("unit_price").asText()).isEqualTo("4.6200");
        assertThat(body.get("minimum_bushels").asText()).isEqualTo("19600.0000");
        assertThat(body.get("maximum_bushels").asText()).isEqualTo("20400.0000");
    }

    @Test
    void aForwardWithNoPriceIsRefused() {
        Http.Answer response = client.postJson("/contracts", forward("flat_price", null));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("contract_error");
    }

    @Test
    void writingTheSameNumberTwiceIsA409() {
        client.postJson("/contracts", forward());
        assertThat(client.postJson("/contracts", forward()).status()).isEqualTo(409);
    }

    @Test
    void aMissingContractIsA404() {
        Http.Answer response = client.get("/contracts/F-9999");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("contract_not_found");
    }

    @Test
    void anHtaIsUnpricedUntilTheBasisIsSet() {
        client.postJson("/contracts", hta());
        JsonNode body = client.get("/contracts/H-2024-042").json();
        assertThat(body.get("is_fully_priced").asBoolean()).isFalse();
        assertThat(body.get("is_fully_priced").isBoolean()).isTrue();
        assertThat(body.get("unit_price").isNull()).isTrue();
        assertThat(body.get("futures_symbol").asText()).isEqualTo("CORNZ24");
    }

    @Test
    void settingTheBasisPricesTheHta() {
        client.postJson("/contracts", hta());
        Http.Answer response = client.postJson("/contracts/H-2024-042/basis", price("-0.2500", "2024-10-15"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("unit_price").asText()).isEqualTo("4.6000");
    }

    @Test
    void theBasisCanOnlyBeSetOnce() {
        client.postJson("/contracts", hta());
        client.postJson("/contracts/H-2024-042/basis", price("-0.25", "2024-10-15"));
        Http.Answer response = client.postJson("/contracts/H-2024-042/basis", price("-0.15", "2024-10-20"));
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void settingABasisOnAForwardIsRefused() {
        client.postJson("/contracts", forward());
        Http.Answer response = client.postJson("/contracts/F-2024-118/basis", price("-0.25", "2024-10-15"));
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void settingFuturesPricesABasisContract() {
        client.postJson("/contracts", basisContract());
        Http.Answer response = client.postJson("/contracts/B-2024-009/futures", price("4.7000", "2024-11-01"));
        assertThat(response.json().get("unit_price").asText()).isEqualTo("4.4000");
    }

    @Test
    void rollingForwardAddsTheSpread() {
        client.postJson("/contracts", hta());
        Http.Answer response = client.postJson("/contracts/H-2024-042/roll", roll("H", 2025, "0.1200"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("futures_symbol").asText()).isEqualTo("CORNH25");
        assertThat(response.json().get("futures_price").asText()).isEqualTo("4.9700");
    }

    @Test
    void rollingBackwardIsRefused() {
        client.postJson("/contracts", hta());
        client.postJson("/contracts/H-2024-042/roll", roll("H", 2025, "0.12"));
        Http.Answer response = client.postJson("/contracts/H-2024-042/roll", roll("Z", 2024, "-0.12"));
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void rollingToAMonthTheCommodityDoesNotTrade() {
        client.postJson("/contracts", hta());
        Http.Answer response = client.postJson("/contracts/H-2024-042/roll", roll("F", 2025, "0.12"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("pricing_error");
    }

    @Test
    void cancellingAContract() {
        client.postJson("/contracts", forward());
        Http.Answer response = client.post("/contracts/F-2024-118/cancel");
        assertThat(response.json().get("status").asText()).isEqualTo("cancelled");
    }

    @Test
    void cancellingTwiceIsRefused() {
        client.postJson("/contracts", forward());
        client.post("/contracts/F-2024-118/cancel");
        assertThat(client.post("/contracts/F-2024-118/cancel").status()).isEqualTo(422);
    }

    @Test
    void listingOpenContractsForADay() {
        client.postJson("/contracts", forward());
        client.postJson(
                "/contracts",
                forward("number", "F-2024-119", "window_opens", "2025-01-01", "window_closes", "2025-03-31"));
        JsonNode body = client.get("/contracts", "party_code", "MERRICK", "commodity_code", "CORN", "on", "2024-10-15")
                .json();
        assertThat(body.get("total").asInt()).isEqualTo(1);
        assertThat(body.get("items").get(0).get("number").asText()).isEqualTo("F-2024-118");
    }

    @Test
    void aCancelledContractIsNotListed() {
        client.postJson("/contracts", forward());
        client.post("/contracts/F-2024-118/cancel");
        JsonNode body = client.get("/contracts", "party_code", "MERRICK", "commodity_code", "CORN", "on", "2024-10-15")
                .json();
        assertThat(body.get("total").asInt()).isZero();
    }

    @Test
    void appliedBushelsStartAtZero() {
        client.postJson("/contracts", forward());
        assertThat(client.get("/contracts/F-2024-118")
                        .json()
                        .get("applied_bushels")
                        .asText())
                .isEqualTo("0");
    }
}
