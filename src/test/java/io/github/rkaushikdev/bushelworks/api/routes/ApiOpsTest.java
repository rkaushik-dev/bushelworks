package io.github.rkaushikdev.bushelworks.api.routes;

import static io.github.rkaushikdev.bushelworks.api.Http.list;
import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static io.github.rkaushikdev.bushelworks.api.Http.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@RouteTest
class ApiOpsTest {

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

    /** Two loads across the scale. */
    Http busy() {
        client.postJson("/tickets", aTicket());
        client.postJson("/tickets", aTicket("ticket_number", "A-10442"));
        return client;
    }

    static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    static List<String> texts(JsonNode items, String field) {
        List<String> found = new ArrayList<>();
        items.forEach(item -> found.add(item.get(field).asText()));
        return found;
    }

    @Test
    void aDocumentWithNoHistoryComesBackEmpty() {
        assertThat(client.get("/ops/audit/documents/ticket/A-99999").json()).isEmpty();
    }

    @Test
    void aRecordedTicketHasATrail() {
        JsonNode entries = busy().get("/ops/audit/documents/ticket/A-10441").json();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("action").asText()).isEqualTo("recorded");
        assertThat(entries.get(0).get("document_kind").asText()).isEqualTo("ticket");
    }

    @Test
    void theTrailCarriesTheDetail() {
        JsonNode entry =
                busy().get("/ops/audit/documents/ticket/A-10441").json().get(0);
        assertThat(entry.get("detail").asText()).contains("grade 1");
    }

    @Test
    void theEntryCarriesWhenItWasRecordedInUtc() {
        JsonNode entry =
                busy().get("/ops/audit/documents/ticket/A-10441").json().get(0);
        OffsetDateTime recorded = OffsetDateTime.parse(entry.get("recorded_at").asText());
        assertThat(recorded.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(entry.get("recorded_at").asText()).endsWith("+00:00");
    }

    @Test
    void voidingAddsToTheTrail() {
        busy().post("/tickets/HOME/A-10441/void", "reason", "reweighed");
        JsonNode entries = client.get("/ops/audit/documents/ticket/A-10441").json();
        assertThat(texts(entries, "action")).containsExactly("recorded");
    }

    @Test
    void aProducerTrailListsNewestFirst() {
        JsonNode entries = busy().get("/ops/audit/party/MERRICK").json();
        assertThat(texts(entries, "document_number")).containsExactly("A-10442", "A-10441");
    }

    @Test
    void aProducerTrailCanBeLimited() {
        JsonNode entries = busy().get("/ops/audit/party/MERRICK", "limit", 1).json();
        assertThat(entries).hasSize(1);
    }

    @Test
    void aLimitOutOfRangeIsRefused() {
        assertThat(busy().get("/ops/audit/party/MERRICK", "limit", 0).status()).isEqualTo(422);
    }

    @Test
    void aProducerWithNoTrailComesBackEmpty() {
        assertThat(busy().get("/ops/audit/party/HOLLIS").json()).isEmpty();
    }

    @Test
    void theDayTrailFindsTodaysEntries() {
        JsonNode entries = busy().get("/ops/audit/day/" + today()).json();
        assertThat(entries).hasSize(2);
    }

    @Test
    void theDayTrailCanBeFilteredByAction() {
        JsonNode entries =
                busy().get("/ops/audit/day/" + today(), "action", "settled").json();
        assertThat(entries).isEmpty();
    }

    @Test
    void closingADayReportsWhatHappened() {
        JsonNode body = busy().post("/ops/close/2024-10-03").json();
        assertThat(body.get("tickets_written").asInt()).isEqualTo(2);
        assertThat(body.get("tickets_voided").asInt()).isZero();
        assertThat(body.get("bushels_in").asText()).isEqualTo("1865.7143");
    }

    @Test
    void aCleanCloseSaysSo() {
        JsonNode body = busy().post("/ops/close/2024-10-03").json();
        assertThat(body.get("is_clean").asBoolean()).isTrue();
        assertThat(body.get("discrepancies")).isEmpty();
    }

    @Test
    void aTightToleranceFindsAPositionMove() {
        JsonNode body = busy().post("/ops/close/2024-10-03", "position_tolerance", "100")
                .json();
        assertThat(body.get("is_clean").asBoolean()).isFalse();
        assertThat(texts(body.get("discrepancies"), "kind")).contains("position");
    }

    @Test
    void anEmptyDayClosesClean() {
        JsonNode body = client.post("/ops/close/2024-10-03").json();
        assertThat(body.get("is_clean").asBoolean()).isTrue();
        assertThat(body.get("tickets_written").asInt()).isZero();
    }

    @Test
    void theClosePrintsForTheDayBook() {
        Http.Answer response = busy().get("/ops/close/2024-10-03/sheet");
        assertThat(response.contentType()).startsWith("text/plain");
        assertThat(response.text()).contains("DAILY CLOSE 2024-10-03");
        assertThat(response.text()).contains("closed clean");
    }

    @Test
    void theOpsRoutesAreDocumented() {
        JsonNode paths = client.get("/openapi.json").json().get("paths");
        assertThat(paths.has("/ops/close/{day}")).isTrue();
        assertThat(paths.has("/ops/audit/party/{party_code}")).isTrue();
        assertThat(paths.has("/ops/audit/documents/{document_kind}/{document_number}"))
                .isTrue();
    }
}
