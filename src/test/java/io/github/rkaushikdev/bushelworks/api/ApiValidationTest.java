package io.github.rkaushikdev.bushelworks.api;

import static io.github.rkaushikdev.bushelworks.api.Http.list;
import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static io.github.rkaushikdev.bushelworks.api.Http.with;
import static io.github.rkaushikdev.bushelworks.api.Http.without;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A refused request body reads like every other refusal.
 *
 * <p>The scale house terminal shows {@code detail} to whoever is standing at the window, so it has to
 * be a sentence, and the machine readable breakdown goes somewhere else.
 */
@RouteTest
class ApiValidationTest {

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void freshHouse() {
        TestDatabase.fresh();
        client = new Http(mvc);
    }

    static Map<String, Object> aTicket(Object... overrides) {
        Map<String, Object> body = object(
                "ticket_number", "A-10441",
                "location_code", "HOME",
                "scale_date", "2024-10-03",
                "commodity_code", "CORN",
                "party_code", "MERRICK",
                "gross_pounds", "78420",
                "tare_pounds", "26180",
                "disposition", "cash_sale",
                "splits", list(object("party_code", "MERRICK", "percent", "100")),
                "sample",
                        list(
                                object("factor", "moisture", "value", "18.2"),
                                object("factor", "test_weight", "value", "56.5"),
                                object("factor", "bcfm", "value", "1.2"),
                                object("factor", "damage_total", "value", "1.0")));
        return with(body, overrides);
    }

    @Test
    void theBodyLocationPrefixIsDropped() {
        assertThat(Errors.fieldPath(List.of("body", "splits", 0, "percent"))).isEqualTo("splits.0.percent");
        assertThat(Errors.fieldPath(List.of("query", "on"))).isEqualTo("on");
        assertThat(Errors.fieldPath(List.of())).isEqualTo("body");
    }

    @Test
    void aLocationWithNoPrefixSurvives() {
        assertThat(Errors.fieldPath(List.of("splits", 1))).isEqualTo("splits.1");
    }

    @Test
    void oneBadFieldReadsAsASentence() {
        Http.Answer response = client.postJson("/tickets", aTicket("scale_date", "not-a-date"));
        assertThat(response.status()).isEqualTo(422);
        JsonNode body = response.json();
        assertThat(body.get("code").asText()).isEqualTo("invalid_request");
        assertThat(body.get("detail").asText()).startsWith("scale_date:");
    }

    @Test
    void theBreakdownNamesTheField() {
        JsonNode fields = client.postJson("/tickets", aTicket("scale_date", "not-a-date"))
                .json()
                .get("fields");
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).get("field").asText()).isEqualTo("scale_date");
        assertThat(fields.get(0).get("detail").asText()).isNotEmpty();
    }

    @Test
    void severalBadFieldsAreCounted() {
        JsonNode body = client.postJson("/tickets", aTicket("scale_date", "not-a-date", "ticket_number", ""))
                .json();
        assertThat(body.get("detail").asText()).contains("2 fields were refused");
        Set<String> named = new HashSet<>();
        body.get("fields").forEach(item -> named.add(item.get("field").asText()));
        assertThat(named).containsExactlyInAnyOrder("scale_date", "ticket_number");
    }

    @Test
    void aNestedFieldIsNamedByItsPath() {
        JsonNode fields = client.postJson(
                        "/tickets", aTicket("splits", list(object("party_code", "MERRICK", "percent", 100.5))))
                .json()
                .get("fields");
        List<String> named = fields.findValuesAsText("field");
        assertThat(named).anyMatch(field -> field.startsWith("splits.0"));
    }

    @Test
    void aMissingFieldIsNamed() {
        Http.Answer response = client.postJson("/tickets", without(aTicket(), "commodity_code"));
        assertThat(response.json().get("fields").get(0).get("field").asText()).isEqualTo("commodity_code");
    }

    @Test
    void theRefusalCarriesTheRequestId() {
        Http.Answer response = client.postJson("/tickets", aTicket("scale_date", "x"));
        assertThat(response.json().get("request_id").asText()).isEqualTo(response.header("x-request-id"));
    }

    @Test
    void aDomainRefusalStillHasNoFieldsKey() {
        client.postJson("/tickets", aTicket());
        Http.Answer response = client.postJson("/tickets", aTicket());
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().has("fields")).isFalse();
    }

    @Test
    void aBadQueryParameterIsRefusedTheSameWay() {
        Http.Answer response = client.get("/positions/not-a-date");
        assertThat(response.status()).isEqualTo(422);
        JsonNode body = response.json();
        assertThat(body.get("code").asText()).isEqualTo("invalid_request");
        assertThat(body.get("fields").get(0).get("field").asText()).isEqualTo("as_of");
    }

    @Test
    void aFloatQuantitySaysWhatToSendInstead() {
        Http.Answer response = client.postJson("/tickets", aTicket("gross_pounds", 78420.5));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("fields").get(0).get("detail").asText()).contains("strings");
    }
}
