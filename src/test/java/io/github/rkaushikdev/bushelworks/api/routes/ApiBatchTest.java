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
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A run of tickets the terminal held while the line was down.
 *
 * <p>One duplicate in the middle used to lose the other eight, and the driver of the eighth had
 * already gone home.
 */
@RouteTest
class ApiBatchTest {

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void freshHouse() {
        TestDatabase.fresh();
        client = new Http(mvc);
    }

    static Map<String, Object> aTicket(String number, Object... overrides) {
        return with(ApiTicketsTest.aBody("ticket_number", number), overrides);
    }

    private Http.Answer batch(Object... tickets) {
        return client.postJson("/tickets/batch", object("tickets", list(tickets)));
    }

    private static JsonNode firstRefused(JsonNode body) {
        return StreamSupport.stream(body.get("results").spliterator(), false)
                .filter(item -> !item.get("accepted").asBoolean())
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aCleanBatchIsAllAccepted() {
        Http.Answer response = batch(aTicket("A-1"), aTicket("A-2"), aTicket("A-3"));
        assertThat(response.status()).isEqualTo(207);
        JsonNode body = response.json();
        assertThat(body.get("accepted").asInt()).isEqualTo(3);
        assertThat(body.get("refused").asInt()).isZero();
    }

    @Test
    void everyTicketGetsItsOwnResult() {
        JsonNode body = batch(aTicket("A-1"), aTicket("A-2")).json();
        List<String> numbers = new ArrayList<>();
        body.get("results")
                .forEach(item -> numbers.add(item.get("ticket_number").asText()));
        assertThat(numbers).containsExactly("A-1", "A-2");
        body.get("results")
                .forEach(item -> assertThat(item.get("accepted").asBoolean()).isTrue());
    }

    @Test
    void anAcceptedTicketReportsItsBushels() {
        JsonNode body = batch(aTicket("A-1")).json();
        assertThat(body.get("results").get(0).get("dry_bushels").asText()).isEqualTo("897.6324");
    }

    @Test
    void theBatchIsActuallyWritten() {
        batch(aTicket("A-1"));
        assertThat(client.get("/tickets/HOME/A-1").status()).isEqualTo(200);
    }

    @Test
    void aDuplicateInTheMiddleDoesNotCostTheRest() {
        client.postJson("/tickets", aTicket("A-2"));
        JsonNode body = batch(aTicket("A-1"), aTicket("A-2"), aTicket("A-3")).json();
        assertThat(body.get("accepted").asInt()).isEqualTo(2);
        assertThat(body.get("refused").asInt()).isEqualTo(1);
        assertThat(client.get("/tickets/HOME/A-3").status()).isEqualTo(200);
    }

    @Test
    void theRefusedTicketSaysWhy() {
        client.postJson("/tickets", aTicket("A-2"));
        JsonNode refused = firstRefused(batch(aTicket("A-1"), aTicket("A-2")).json());
        assertThat(refused.get("ticket_number").asText()).isEqualTo("A-2");
        assertThat(refused.get("code").asText()).isEqualTo("conflict");
        assertThat(refused.get("detail").asText()).contains("already written");
    }

    @Test
    void aBatchCanBeToldToStopAtTheFirstRefusal() {
        client.postJson("/tickets", aTicket("A-2"));
        JsonNode body = client.postJson(
                        "/tickets/batch",
                        object(
                                "tickets",
                                list(aTicket("A-1"), aTicket("A-2"), aTicket("A-3")),
                                "stop_on_first_refusal",
                                true))
                .json();
        assertThat(body.get("accepted").asInt()).isEqualTo(1);
        assertThat(body.get("results")).hasSize(2);
        assertThat(client.get("/tickets/HOME/A-3").status()).isEqualTo(404);
    }

    @Test
    void theTicketsBeforeAStopAreStillWritten() {
        client.postJson("/tickets", aTicket("A-2"));
        client.postJson(
                "/tickets/batch",
                object("tickets", list(aTicket("A-1"), aTicket("A-2")), "stop_on_first_refusal", true));
        assertThat(client.get("/tickets/HOME/A-1").status()).isEqualTo(200);
    }

    @Test
    void aTicketTheHouseCannotGradeIsRefusedOnItsOwn() {
        JsonNode body = batch(aTicket("A-1"), aTicket("A-2", "commodity_code", "MILO"), aTicket("A-3"))
                .json();
        assertThat(body.get("accepted").asInt()).isEqualTo(2);
        assertThat(firstRefused(body).get("code").asText()).isEqualTo("intake_error");
    }

    @Test
    void anEmptyBatchIsA422() {
        assertThat(batch().status()).isEqualTo(422);
    }

    @Test
    void aBatchPastTheLimitIsA422() {
        Object[] tickets = new Object[201];
        for (int index = 0; index < tickets.length; index++) {
            tickets[index] = aTicket("A-" + index);
        }
        assertThat(batch(tickets).status()).isEqualTo(422);
    }

    @Test
    void aMalformedTicketInTheBatchRefusesTheWholeBody() {
        Http.Answer response = batch(aTicket("A-1"), aTicket("A-2", "scale_date", "x"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("invalid_request");
    }

    @Test
    void theBatchRouteIsDocumented() {
        JsonNode paths = client.get("/openapi.json").json().get("paths");
        assertThat(paths.has("/tickets/batch")).isTrue();
    }

    @Test
    void theBatchPathDoesNotShadowATicketLookup() {
        client.postJson("/tickets", aTicket("A-1"));
        assertThat(client.get("/tickets/HOME/A-1").status()).isEqualTo(200);
    }
}
