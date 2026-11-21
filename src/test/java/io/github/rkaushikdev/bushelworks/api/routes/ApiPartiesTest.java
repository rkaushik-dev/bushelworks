package io.github.rkaushikdev.bushelworks.api.routes;

import static io.github.rkaushikdev.bushelworks.api.Http.list;
import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static io.github.rkaushikdev.bushelworks.api.Http.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@RouteTest
class ApiPartiesTest {

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void freshHouse() {
        TestDatabase.fresh();
        client = new Http(mvc);
    }

    static Map<String, Object> aParty(Object... overrides) {
        Map<String, Object> body = object(
                "code", "MERRICK",
                "name", "Merrick Farms LLC",
                "state_code", "KS",
                "opened_on", "2024-01-15");
        return with(body, overrides);
    }

    static Map<String, Object> anAgreement(Object... overrides) {
        Map<String, Object> body = object(
                "effective_from",
                "2024-03-01",
                "shares",
                list(
                        object("party_code", "BROCKMAN", "percent", "25", "role", "landlord"),
                        object("party_code", "MERRICK", "percent", "75", "role", "tenant")));
        return with(body, overrides);
    }

    /** Three producers on the file. */
    Http filed() {
        client.postJson("/parties", aParty());
        client.postJson("/parties", aParty("code", "BROCKMAN", "name", "Ellen Brockman"));
        client.postJson("/parties", aParty("code", "HOLLIS", "name", "Hollis Brothers"));
        return client;
    }

    static List<String> texts(JsonNode items, String field) {
        List<String> found = new ArrayList<>();
        items.forEach(item -> found.add(item.get(field).asText()));
        return found;
    }

    @Test
    void openingAnAccount() {
        Http.Answer response = client.postJson("/parties", aParty());
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("sort_name").asText()).isEqualTo("MERRICK FARMS LLC");
        assertThat(body.get("is_active").asBoolean()).isTrue();
        assertThat(body.get("checkoff_exempt").asBoolean()).isFalse();
    }

    @Test
    void theSameCodeTwiceIsA409() {
        assertThat(filed().postJson("/parties", aParty()).status()).isEqualTo(409);
    }

    @Test
    void aMissingPartyIsA404() {
        Http.Answer response = client.get("/parties/NOBODY");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("party_not_found");
    }

    @Test
    void aBlankCodeIsA422() {
        assertThat(client.postJson("/parties", aParty("code", "")).status()).isEqualTo(422);
    }

    @Test
    void listingTheActiveAccounts() {
        JsonNode body = filed().get("/parties").json();
        assertThat(body.get("total").asInt()).isEqualTo(3);
        assertThat(texts(body.get("items"), "code")).containsExactly("BROCKMAN", "HOLLIS", "MERRICK");
    }

    @Test
    void closingAnAccountTakesItOffTheList() {
        Http.Answer response = filed().post("/parties/HOLLIS/closure", "on", "2024-12-31");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("is_active").asBoolean()).isFalse();
        assertThat(client.get("/parties").json().get("total").asInt()).isEqualTo(2);
    }

    @Test
    void closingTwiceIsA409() {
        filed().post("/parties/HOLLIS/closure", "on", "2024-12-31");
        Http.Answer response = client.post("/parties/HOLLIS/closure", "on", "2024-12-31");
        assertThat(response.status()).isEqualTo(409);
    }

    @Test
    void markingAProducerExempt() {
        Http.Answer response = filed().post("/parties/MERRICK/checkoff-exemption");
        assertThat(response.json().get("checkoff_exempt").asBoolean()).isTrue();
        Http.Answer off = client.post("/parties/MERRICK/checkoff-exemption", "exempt", false);
        assertThat(off.json().get("checkoff_exempt").asBoolean()).isFalse();
    }

    @Test
    void filingAnAgreement() {
        Http.Answer response = filed().postJson("/parties/farms/HOME-PLACE/agreements", anAgreement());
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("is_balanced").asBoolean()).isTrue();
        assertThat(body.get("total_percent").asText()).isEqualTo("100");
    }

    @Test
    void anAgreementThatDoesNotAddUpIsA422() {
        Http.Answer response = filed().postJson(
                        "/parties/farms/HOME-PLACE/agreements",
                        anAgreement("shares", list(object("party_code", "MERRICK", "percent", "60"))));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("party_error");
    }

    @Test
    void anAgreementWithNoSharesIsA422() {
        Http.Answer response = filed().postJson("/parties/farms/HOME-PLACE/agreements", anAgreement("shares", list()));
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void anAgreementNamingAStrangerIsA404() {
        Http.Answer response = filed().postJson(
                        "/parties/farms/HOME-PLACE/agreements",
                        anAgreement(
                                "shares",
                                list(
                                        object("party_code", "NOBODY", "percent", "50"),
                                        object("party_code", "MERRICK", "percent", "50"))));
        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void theAgreementInForceReadsBack() {
        filed().postJson("/parties/farms/HOME-PLACE/agreements", anAgreement());
        Http.Answer response = client.get("/parties/farms/HOME-PLACE/agreement", "on", "2024-10-03");
        assertThat(response.status()).isEqualTo(200);
        assertThat(texts(response.json().get("shares"), "party_code")).containsExactlyInAnyOrder("BROCKMAN", "MERRICK");
    }

    @Test
    void nothingInForceBeforeTheAgreementIsA404() {
        filed().postJson("/parties/farms/HOME-PLACE/agreements", anAgreement());
        Http.Answer response = client.get("/parties/farms/HOME-PLACE/agreement", "on", "2024-01-05");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("no_split_agreement");
    }

    @Test
    void theFarmsAProducerSharesIn() {
        filed().postJson("/parties/farms/HOME-PLACE/agreements", anAgreement());
        client.postJson(
                "/parties/farms/NORTH-EIGHTY/agreements",
                anAgreement("shares", list(object("party_code", "MERRICK", "percent", "100"))));
        JsonNode body = client.get("/parties/MERRICK/farms").json();
        List<String> farms = new ArrayList<>();
        body.get("farms").forEach(farm -> farms.add(farm.asText()));
        assertThat(farms).containsExactly("HOME-PLACE", "NORTH-EIGHTY");
        assertThat(client.get("/parties/HOLLIS/farms").json().get("farms")).isEmpty();
    }

    @Test
    void thePartyRoutesAreDocumented() {
        JsonNode paths = client.get("/openapi.json").json().get("paths");
        assertThat(paths.has("/parties")).isTrue();
        assertThat(paths.has("/parties/farms/{farm_code}/agreements")).isTrue();
    }
}
