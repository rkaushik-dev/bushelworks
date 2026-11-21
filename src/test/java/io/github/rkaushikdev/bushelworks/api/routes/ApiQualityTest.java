package io.github.rkaushikdev.bushelworks.api.routes;

import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static io.github.rkaushikdev.bushelworks.api.Http.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.Json;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.servicetest.TestService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

class ApiQualityTest {

    /** The crop year the complaints are asked about. */
    private static final Object[] SPAN = {"opens", "2024-09-01", "closes", "2025-08-31"};

    /** Cargill's complaint about a wet car out of bin 3, raised six days after the delivery. */
    static Map<String, Object> aComplaint(Object... overrides) {
        Map<String, Object> body = object(
                "reference", "C-2024-018",
                "source", "buyer",
                "subject", "car ran 15.8 moisture against a 14.0 contract",
                "raised_on", "2024-10-09",
                "delivered_on", "2024-10-03",
                "party_code", "CARGILL",
                "bin_id", "BIN-3",
                "probe_id", "P-88213");
        return with(body, overrides);
    }

    private static Object[] spanWith(Object... more) {
        List<Object> query = new ArrayList<>(List.of(SPAN));
        query.addAll(List.of(more));
        return query.toArray();
    }

    private static List<String> references(JsonNode complaints) {
        List<String> found = new ArrayList<>();
        complaints.forEach(item -> found.add(item.get("reference").asText()));
        return found;
    }

    private static List<String> texts(JsonNode list) {
        List<String> found = new ArrayList<>();
        list.forEach(item -> found.add(item.asText()));
        return found;
    }

    /** A house that asks for no key, every caller an office caller. */
    @Nested
    @RouteTest
    class OpenHouse {

        @Autowired
        private MockMvc mvc;

        private Http client;

        @BeforeEach
        void freshHouse() {
            TestDatabase.fresh();
            client = new Http(mvc);
        }

        /** Cargill's complaint on file. */
        private Http filed() {
            client.postJson("/complaints", aComplaint());
            return client;
        }

        private Http.Answer close(Object... body) {
            return client.postJson("/complaints/C-2024-018/close", object(body));
        }

        @Test
        void aComplaintCanBeTaken() {
            Http.Answer response = client.postJson("/complaints", aComplaint());
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.json().get("outcome").asText()).isEqualTo("open");
        }

        @Test
        void aNewComplaintIsOpenAndDefensible() {
            JsonNode body = client.postJson("/complaints", aComplaint()).json();
            assertThat(body.get("is_open").asBoolean()).isTrue();
            assertThat(body.get("sample_is_available").asBoolean()).isTrue();
            assertThat(body.get("is_late").asBoolean()).isFalse();
        }

        @Test
        void theDaysSinceDeliveryComeBack() {
            assertThat(client.postJson("/complaints", aComplaint())
                            .json()
                            .get("days_after")
                            .asInt())
                    .isEqualTo(6);
        }

        @Test
        void aComplaintRaisedBeforeDeliveryIsRefused() {
            Http.Answer response = client.postJson("/complaints", aComplaint("raised_on", "2024-10-01"));
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void theSameReferenceTwiceIsAConflict() {
            assertThat(filed().postJson("/complaints", aComplaint()).status()).isEqualTo(409);
        }

        @Test
        void aComplaintWithNoSubjectIsRefused() {
            assertThat(client.postJson("/complaints", aComplaint("subject", "")).status())
                    .isEqualTo(422);
        }

        @Test
        void aSourceNobodyKnowsIsRefused() {
            Http.Answer response = client.postJson("/complaints", aComplaint("source", "postman"));
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void aNamedTicketHasToExist() {
            Http.Answer response = client.postJson("/complaints", aComplaint("ticket_number", "A-99999"));
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void aNamedTicketThatExistsIsTaken() {
            client.postJson("/tickets", ApiTicketsTest.aBody());
            Http.Answer response = client.postJson("/complaints", aComplaint("ticket_number", "A-10441"));
            assertThat(response.status()).isEqualTo(201);
        }

        @Test
        void aTicketWeighedAfterTheDeliveryIsRefused() {
            client.postJson("/tickets", ApiTicketsTest.aBody("scale_date", "2024-11-20"));
            Http.Answer response = client.postJson("/complaints", aComplaint("ticket_number", "A-10441"));
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void aVoidedTicketIsNotATicket() {
            client.postJson("/tickets", ApiTicketsTest.aBody());
            client.post("/tickets/HOME/A-10441/void", "reason", "keyed twice");
            Http.Answer response = client.postJson("/complaints", aComplaint("ticket_number", "A-10441"));
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void aFiledComplaintCanBeReadBack() {
            assertThat(filed().get("/complaints/C-2024-018")
                            .json()
                            .get("party_code")
                            .asText())
                    .isEqualTo("CARGILL");
        }

        @Test
        void oneThatIsNotThereIsA404() {
            assertThat(client.get("/complaints/C-NOPE").status()).isEqualTo(404);
        }

        @Test
        void theOpenListCarriesTheNewOne() {
            assertThat(references(filed().get("/complaints").json())).containsExactly("C-2024-018");
        }

        @Test
        void theOpenListCanBeNarrowedToABin() {
            filed().postJson("/complaints", aComplaint("reference", "C-2", "bin_id", "BIN-7"));
            JsonNode body = client.get("/complaints", "bin_id", "BIN-7").json();
            assertThat(references(body)).containsExactly("C-2");
        }

        @Test
        void aComplaintWithNoSampleIsIndefensible() {
            client.postJson("/complaints", aComplaint("probe_id", null));
            JsonNode body = client.get("/complaints/reports/indefensible", SPAN).json();
            assertThat(references(body)).containsExactly("C-2024-018");
        }

        @Test
        void aComplaintPastRetentionIsIndefensible() {
            client.postJson("/complaints", aComplaint("raised_on", "2024-12-01"));
            JsonNode body = client.get("/complaints/reports/indefensible", SPAN).json();
            assertThat(body.size()).isEqualTo(1);
        }

        @Test
        void aComplaintWithALiveSampleIsDefensible() {
            JsonNode body =
                    filed().get("/complaints/reports/indefensible", SPAN).json();
            assertThat(body.isArray()).isTrue();
            assertThat(body.size()).isZero();
        }

        @Test
        void anActionCanBeRecorded() {
            Http.Answer response = filed().postJson(
                            "/complaints/C-2024-018/actions",
                            object("taken_on", "2024-10-10", "taken_by", "dee", "what", "pulled the sample"));
            assertThat(response.status()).isEqualTo(201);
        }

        @Test
        void anActionOnAComplaintThatIsNotThereIsA404() {
            Http.Answer response = client.postJson(
                    "/complaints/C-NOPE/actions",
                    object("taken_on", "2024-10-10", "taken_by", "dee", "what", "nothing"));
            assertThat(response.status()).isEqualTo(404);
        }

        @Test
        void closingRecordsTheOutcome() {
            filed();
            JsonNode body = close("outcome", "upheld", "on", "2024-10-14", "credit", "412.50")
                    .json();
            assertThat(body.get("outcome").asText()).isEqualTo("upheld");
            assertThat(body.get("credit").asText()).isEqualTo("412.50");
            assertThat(body.get("is_open").asBoolean()).isFalse();
        }

        @Test
        void aClosedComplaintLeavesTheOpenList() {
            filed();
            close("outcome", "rejected", "on", "2024-10-14");
            JsonNode body = client.get("/complaints").json();
            assertThat(body.isArray()).isTrue();
            assertThat(body.size()).isZero();
        }

        @Test
        void closingTwiceIsAConflict() {
            filed();
            close("outcome", "rejected", "on", "2024-10-14");
            Http.Answer response = close("outcome", "upheld", "on", "2024-10-15");
            assertThat(response.status()).isEqualTo(409);
        }

        @Test
        void aRejectedComplaintCannotCarryACredit() {
            filed();
            Http.Answer response = close("outcome", "rejected", "on", "2024-10-14", "credit", "50.00");
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void closingBeforeItWasRaisedIsRefused() {
            filed();
            assertThat(close("outcome", "settled", "on", "2024-10-04").status()).isEqualTo(422);
        }

        @Test
        void aCreditSentAsAJsonNumberIsRefused() {
            filed();
            Http.Answer response = close("outcome", "upheld", "on", "2024-10-14", "credit", 412.5);
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void aBinComplainedAboutThreeTimesIsFlagged() {
            filed().postJson("/complaints", aComplaint("reference", "C-2"));
            client.postJson("/complaints", aComplaint("reference", "C-3"));
            JsonNode body = client.get("/complaints/reports/repeat-bins", SPAN).json();
            assertThat(texts(body)).containsExactly("BIN-3");
        }

        @Test
        void twoComplaintsAreNotAPattern() {
            filed().postJson("/complaints", aComplaint("reference", "C-2"));
            JsonNode body = client.get("/complaints/reports/repeat-bins", SPAN).json();
            assertThat(body.isArray()).isTrue();
            assertThat(body.size()).isZero();
        }

        @Test
        void theThresholdCanBeMoved() {
            filed().postJson("/complaints", aComplaint("reference", "C-2"));
            JsonNode body = client.get("/complaints/reports/repeat-bins", spanWith("threshold", 2))
                    .json();
            assertThat(texts(body)).containsExactly("BIN-3");
        }

        @Test
        void theSummaryCountsWhatCameIn() {
            JsonNode body = filed().get("/complaints/reports/summary", SPAN).json();
            assertThat(body.get("count").asInt()).isEqualTo(1);
            assertThat(body.get("open").asInt()).isEqualTo(1);
            assertThat(body.get("by_source")).isEqualTo(Json.tree(Map.of("buyer", 1)));
        }

        @Test
        void theSummaryCarriesTheUpheldRate() {
            filed();
            close("outcome", "upheld", "on", "2024-10-14", "credit", "412.50");
            JsonNode body = client.get("/complaints/reports/summary", SPAN).json();
            assertThat(body.get("upheld_rate").asText()).isEqualTo("100.0");
            assertThat(body.get("credits").asText()).isEqualTo("412.50");
        }

        @Test
        void theSummaryNamesTheWindow() {
            assertThat(filed().get("/complaints/reports/summary", SPAN)
                            .json()
                            .get("window_days")
                            .asInt())
                    .isEqualTo(30);
        }

        @Test
        void aQuietSpanSummarisesToNothing() {
            JsonNode body = client.get("/complaints/reports/summary", SPAN).json();
            assertThat(body.get("count").asInt()).isZero();
            assertThat(body.get("upheld_rate").asText()).isEqualTo("0.0");
        }
    }

    /** A house with a read key and an office key on file. */
    @Nested
    @RouteTest
    @TestPropertySource(properties = TestService.PREFIX + "BUSHELWORKS_API_KEYS=k1:read:look,k2:office:office")
    class LockedHouse {

        @Autowired
        private MockMvc mvc;

        @BeforeEach
        void freshHouse() {
            TestDatabase.fresh();
        }

        @Test
        void theRegisterIsOfficeOnly() {
            Http client = new Http(mvc);
            Http.Answer looking = client.as("look").get("/complaints");
            Http.Answer allowed = client.as("office").get("/complaints");
            assertThat(looking.status()).isEqualTo(403);
            assertThat(allowed.status()).isEqualTo(200);
        }
    }
}
