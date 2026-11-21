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
class ApiTicketsTest {

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void freshHouse() {
        TestDatabase.fresh();
        client = new Http(mvc);
    }

    static Map<String, Object> aBody(Object... overrides) {
        Map<String, Object> body = object(
                "ticket_number", "A-10441",
                "location_code", "HOME",
                "scale_date", "2024-10-03",
                "commodity_code", "CORN",
                "party_code", "MERRICK",
                "gross_pounds", "78420",
                "tare_pounds", "26180",
                "disposition", "cash_sale",
                "truck_id", "T-14",
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
    void recordingALoadReturnsTheGradedNumbers() {
        Http.Answer response = client.postJson("/tickets", aBody());
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("clean_bushels").asText()).isEqualTo("932.8571");
        assertThat(body.get("dry_bushels").asText()).isEqualTo("897.6324");
        assertThat(body.get("shrink_percent").asText()).isEqualTo("3.7760");
        assertThat(body.get("grade").get("grade").asText()).isEqualTo("1");
    }

    @Test
    void theLocationHeaderPointsAtTheTicket() {
        Http.Answer response = client.postJson("/tickets", aBody());
        assertThat(response.header("Location")).isEqualTo("/tickets/HOME/A-10441");
    }

    @Test
    void theTicketReadsBack() {
        client.postJson("/tickets", aBody());
        Http.Answer response = client.get("/tickets/HOME/A-10441");
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.get("net_pounds").asText()).isEqualTo("52240");
        assertThat(body.get("gross_pounds").asText()).isEqualTo("78420");
        assertThat(body.get("sample")).hasSize(4);
        assertThat(body.get("splits").get(0).get("party_code").asText()).isEqualTo("MERRICK");
    }

    @Test
    void aMissingTicketIsA404() {
        Http.Answer response = client.get("/tickets/HOME/A-99999");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("ticket_not_found");
    }

    @Test
    void theSameTicketTwiceIsA409() {
        client.postJson("/tickets", aBody());
        Http.Answer response = client.postJson("/tickets", aBody());
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("code").asText()).isEqualTo("conflict");
    }

    @Test
    void aTareAboveGrossIsA422() {
        Http.Answer response = client.postJson("/tickets", aBody("gross_pounds", "1000"));
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void aCommodityTheHouseDoesNotBuyIsRefused() {
        Http.Answer response = client.postJson(
                "/tickets",
                aBody(
                        "commodity_code",
                        "MILO",
                        "sample",
                        list(
                                object("factor", "moisture", "value", "13.0"),
                                object("factor", "test_weight", "value", "57.0"),
                                object("factor", "foreign_material", "value", "1.0"),
                                object("factor", "damage_total", "value", "1.0"))));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("intake_error");
    }

    @Test
    void aSampleMissingARequiredFactorIsRefused() {
        Http.Answer response =
                client.postJson("/tickets", aBody("sample", list(object("factor", "moisture", "value", "18.2"))));
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void regradingAStoredTicket() {
        client.postJson("/tickets", aBody());
        Http.Answer response = client.get("/tickets/HOME/A-10441/grade");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("dry_bushels").asText()).isEqualTo("897.6324");
    }

    @Test
    void voidingKeepsTheWeightsAndRecordsTheReason() {
        client.postJson("/tickets", aBody());
        Http.Answer response = client.post("/tickets/HOME/A-10441/void", "reason", "reweighed");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("voided").asBoolean()).isTrue();
        assertThat(response.json().get("net_pounds").asText()).isEqualTo("52240");
    }

    @Test
    void voidingTwiceIsA409() {
        client.postJson("/tickets", aBody());
        client.post("/tickets/HOME/A-10441/void", "reason", "reweighed");
        Http.Answer response = client.post("/tickets/HOME/A-10441/void", "reason", "again");
        assertThat(response.status()).isEqualTo(409);
    }

    @Test
    void aVoidNeedsAReason() {
        client.postJson("/tickets", aBody());
        Http.Answer response = client.post("/tickets/HOME/A-10441/void", "reason", "");
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void listingUnsettledTickets() {
        client.postJson("/tickets", aBody());
        client.postJson("/tickets", aBody("ticket_number", "A-10442"));
        JsonNode body = client.get("/tickets", "unsettled_only", true).json();
        assertThat(body.get("total").asInt()).isEqualTo(2);
        List<String> numbers = new ArrayList<>();
        body.get("items").forEach(item -> numbers.add(item.get("ticket_number").asText()));
        assertThat(numbers).containsExactly("A-10441", "A-10442");
    }

    @Test
    void aVoidedTicketDropsOffTheUnsettledList() {
        client.postJson("/tickets", aBody());
        client.postJson("/tickets", aBody("ticket_number", "A-10442"));
        client.post("/tickets/HOME/A-10442/void", "reason", "reweighed");
        Http.Answer response = client.get("/tickets", "unsettled_only", true);
        assertThat(response.json().get("total").asInt()).isEqualTo(1);
    }

    @Test
    void listingByParty() {
        client.postJson("/tickets", aBody());
        client.postJson("/tickets", aBody("ticket_number", "A-10442", "party_code", "HOLLIS"));
        Http.Answer response = client.get("/tickets", "party_code", "HOLLIS");
        assertThat(response.json().get("total").asInt()).isEqualTo(1);
    }

    @Test
    void thePageSizeIsClampedToTheHouseMaximum() {
        client.postJson("/tickets", aBody());
        Http.Answer response = client.get("/tickets", "limit", 100000);
        assertThat(response.json().get("limit").asInt()).isEqualTo(200);
    }

    @Test
    void aNegativeOffsetIsTreatedAsZero() {
        client.postJson("/tickets", aBody());
        Http.Answer response = client.get("/tickets", "offset", -5);
        assertThat(response.json().get("offset").asInt()).isZero();
    }

    @Test
    void theRoutesAreInTheOpenapiDocument() {
        JsonNode paths = client.get("/openapi.json").json().get("paths");
        assertThat(paths.has("/tickets")).isTrue();
        assertThat(paths.has("/tickets/{location_code}/{ticket_number}/void")).isTrue();
    }
}
