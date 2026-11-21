package io.github.rkaushikdev.bushelworks.api.routes;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.api.Http.list;
import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static io.github.rkaushikdev.bushelworks.api.Http.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@RouteTest
class ApiSettlementsTest {

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void freshHouse() {
        TestDatabase.fresh();
        client = new Http(mvc);
    }

    static Map<String, Object> aSettle(Object... overrides) {
        Map<String, Object> body = object(
                "party_code", "MERRICK",
                "location_code", "HOME",
                "settled_on", "2024-10-20",
                "spot_price", "4.3500");
        return with(body, overrides);
    }

    private static Map<String, Object> aPayment() {
        return object("cheque_number", "10442", "paid_on", "2024-10-21");
    }

    /** Merrick's corn load A-10441 across the scale and settled at spot. */
    private Http settled() {
        client.postJson("/tickets", ApiTicketsTest.aBody());
        client.postJson("/settlements", aSettle());
        return client;
    }

    private static List<String> texts(JsonNode items) {
        List<String> texts = new ArrayList<>();
        items.forEach(item -> texts.add(item.asText()));
        return texts;
    }

    @Test
    void settlingOneLoad() {
        client.postJson("/tickets", ApiTicketsTest.aBody());
        Http.Answer response = client.postJson("/settlements", aSettle());
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("number").asText()).isEqualTo("S-2024-0001");
        assertThat(body.get("gross").asText()).isEqualTo("3904.70");
        assertThat(texts(body.get("ticket_numbers"))).containsExactly("A-10441");
    }

    @Test
    void theLocationHeaderPointsAtTheStatement() {
        client.postJson("/tickets", ApiTicketsTest.aBody());
        Http.Answer response = client.postJson("/settlements", aSettle());
        assertThat(response.header("Location")).isEqualTo("/settlements/S-2024-0001");
    }

    @Test
    void settlingWithNothingWaitingIsA422() {
        Http.Answer response = client.postJson("/settlements", aSettle());
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("nothing_to_settle");
    }

    @Test
    void aSettledTicketWillNotSettleTwice() {
        assertThat(settled().postJson("/settlements", aSettle()).status()).isEqualTo(422);
    }

    @Test
    void theStatementReadsBackWithItsLines() {
        JsonNode body = settled().get("/settlements/S-2024-0001").json();
        List<String> kinds = new ArrayList<>();
        body.get("lines").forEach(line -> kinds.add(line.get("kind").asText()));
        assertThat(kinds.get(0)).isEqualTo("gross");
        assertThat(kinds).contains("drying", "assessment");
    }

    @Test
    void aMissingSettlementIsA404() {
        Http.Answer response = client.get("/settlements/S-9999");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("settlement_not_found");
    }

    @Test
    void thePrintableStatementComesBackAsText() {
        Http.Answer response = settled().get("/settlements/S-2024-0001/statement");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).startsWith("text/plain");
        assertThat(response.text()).contains("SETTLEMENT STATEMENT").contains("NET PAYABLE");
    }

    @Test
    void advancesComeOffTheNet() {
        client.postJson("/tickets", ApiTicketsTest.aBody());
        JsonNode plain = client.postJson("/settlements", aSettle()).json();
        client.postJson("/tickets", ApiTicketsTest.aBody("ticket_number", "A-10442"));
        JsonNode withAdvance = client.postJson(
                        "/settlements",
                        aSettle(
                                "advances",
                                list(object(
                                        "reference", "2024-0912",
                                        "dollars", "1000.00",
                                        "advanced_on", "2024-09-12"))))
                .json();
        assertThat(new BigDecimal(withAdvance.get("net").asText()))
                .isEqualByComparingTo(new BigDecimal(plain.get("net").asText()).subtract(d("1000.00")));
    }

    @Test
    void onlyTheNamedTicketsSettle() {
        client.postJson("/tickets", ApiTicketsTest.aBody());
        client.postJson("/tickets", ApiTicketsTest.aBody("ticket_number", "A-10442"));
        Http.Answer response = client.postJson("/settlements", aSettle("ticket_numbers", list("A-10442")));
        assertThat(texts(response.json().get("ticket_numbers"))).containsExactly("A-10442");
    }

    @Test
    void payingRecordsTheCheque() {
        Http.Answer response = settled().postJson("/settlements/S-2024-0001/payment", aPayment());
        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void payingTwiceIsA409() {
        Http settled = settled();
        settled.postJson("/settlements/S-2024-0001/payment", aPayment());
        Http.Answer response = settled.postJson("/settlements/S-2024-0001/payment", aPayment());
        assertThat(response.status()).isEqualTo(409);
    }

    @Test
    void listingUnpaidSettlements() {
        Http.Answer response = settled().get("/settlements", "unpaid_only", true);
        assertThat(response.json().get("total").asInt()).isEqualTo(1);
    }

    @Test
    void aPaidSettlementDropsOffTheUnpaidList() {
        Http settled = settled();
        settled.postJson("/settlements/S-2024-0001/payment", aPayment());
        Http.Answer response = settled.get("/settlements", "unpaid_only", true);
        assertThat(response.json().get("total").asInt()).isZero();
    }

    @Test
    void listingByDay() {
        Http settled = settled();
        assertThat(settled.get("/settlements", "on", "2024-10-20")
                        .json()
                        .get("total")
                        .asInt())
                .isEqualTo(1);
        assertThat(settled.get("/settlements", "on", "2024-10-21")
                        .json()
                        .get("total")
                        .asInt())
                .isZero();
    }

    @Test
    void theDailyRegisterPrints() {
        Http.Answer response = settled().get("/settlements/register/2024-10-20");
        assertThat(response.text())
                .contains("SETTLEMENT REGISTER")
                .contains("20 October 2024")
                .contains("1 statements");
    }

    @Test
    void aSpotPriceSentAsAFloatIsRefused() {
        client.postJson("/tickets", ApiTicketsTest.aBody());
        Http.Answer response = client.postJson("/settlements", aSettle("spot_price", 4.35));
        assertThat(response.status()).isEqualTo(422);
    }
}
