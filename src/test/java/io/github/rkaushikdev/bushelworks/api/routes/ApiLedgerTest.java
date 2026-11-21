package io.github.rkaushikdev.bushelworks.api.routes;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.domain.ledger.Ledger;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.dayend.DayEnd;
import io.github.rkaushikdev.bushelworks.services.monthend.MonthEnd;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The ledger over HTTP.
 *
 * <p>The one that matters most is the last class. {@code /ledger/journal/source/...} sits under a
 * segment rather than at {@code /ledger/journal/{number}} because the second shape swallows it, and
 * this repository has shipped that bug twice. The tests ask the router which route actually matches
 * rather than trusting the order the declarations happen to be in.
 */
@RouteTest
class ApiLedgerTest {

    static final LocalDate HARVEST = LocalDate.of(2024, 10, 3);

    @Autowired
    private MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping routes;

    private Http client;

    /**
     * A house with the standard chart, 2024 filed with January to September closed, and one
     * settlement of two corn loads written and posted at the harvest day's close.
     */
    @BeforeEach
    void books() {
        Database database = TestDatabase.fresh();
        RuleBook rules = HouseFixtures.rules();
        ScaleTicket cornTicket = HouseFixtures.cornTicket();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.ledger().fileChart(Ledger.standardChart());
            MonthEnd.fileYear(uow, 2024);
            for (int month = 1; month < 10; month++) {
                MonthEnd.closePeriod(uow, String.format("2024-%02d", month), "office", LocalDate.of(2024, 10, 1));
            }
            for (String number : List.of("A-10441", "A-10442")) {
                ScaleHouse.recordLoad(
                        uow, cornTicket.toBuilder().ticketNumber(number).build(), "MERRICK", rules);
            }
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, HARVEST, d("4.35"));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            DayEnd.runClose(uow, HARVEST);
            uow.commit();
        }
        client = new Http(mvc);
    }

    private Map<String, JsonNode> accountsByCode() {
        Map<String, JsonNode> rows = new HashMap<>();
        client.get("/ledger/accounts")
                .json()
                .forEach(row -> rows.put(row.get("code").asText(), row));
        return rows;
    }

    private static Set<String> codes(JsonNode rows) {
        Set<String> codes = new HashSet<>();
        rows.forEach(row -> codes.add(row.get("code").asText()));
        return codes;
    }

    private static List<String> codesInOrder(JsonNode rows) {
        List<String> codes = new ArrayList<>();
        rows.forEach(row -> codes.add(row.get("code").asText()));
        return codes;
    }

    private JsonNode october() {
        return client.get("/ledger/journal", "opens", "2024-10-01", "closes", "2024-10-31")
                .json();
    }

    private JsonNode periods(Object... query) {
        return client.get("/ledger/periods", query).json();
    }

    private Http.Answer closeOctober() {
        return client.postJson("/ledger/periods/2024-10/close", object("closed_by", "office", "on", "2024-11-06"));
    }

    @Nested
    class Chart {

        @Test
        void theChartComesBackInCodeOrder() {
            Http.Answer found = client.get("/ledger/accounts");
            assertThat(found.status()).isEqualTo(200);
            List<String> codes = codesInOrder(found.json());
            assertThat(codes).isSorted().contains("1210");
        }

        @Test
        void aHeadingSaysItIsOne() {
            Map<String, JsonNode> rows = accountsByCode();
            assertThat(rows.get("1200").get("is_heading").asBoolean()).isTrue();
            assertThat(rows.get("1210").get("is_heading").asBoolean()).isFalse();
            assertThat(rows.get("1210").get("is_heading").isBoolean()).isTrue();
            assertThat(rows.get("1210").get("parent").asText()).isEqualTo("1200");
        }

        @Test
        void theTypeComesBackAsItsWord() {
            Map<String, JsonNode> rows = accountsByCode();
            assertThat(rows.get("4100").get("type").asText()).isEqualTo("revenue");
            assertThat(rows.get("2100").get("type").asText()).isEqualTo("liability");
        }
    }

    @Nested
    class TrialBalance {

        @Test
        void itBalances() {
            Http.Answer found = client.get("/ledger/trial-balance", "through", "2024-10-31");
            assertThat(found.status()).isEqualTo(200);
            JsonNode body = found.json();
            assertThat(body.get("balances").asBoolean()).isTrue();
            assertThat(body.get("debits")).isEqualTo(body.get("credits"));
            assertThat(body.get("debits").asText()).isNotEqualTo("0.00");
        }

        @Test
        void theAmountsComeBackAsStrings() {
            JsonNode body =
                    client.get("/ledger/trial-balance", "through", "2024-10-31").json();
            assertThat(body.get("debits").isTextual()).isTrue();
            assertThat(body.get("rows").get(0).get("balance").isTextual()).isTrue();
        }

        @Test
        void emptyAccountsAreLeftOutByDefault() {
            JsonNode body =
                    client.get("/ledger/trial-balance", "through", "2024-10-31").json();
            Set<String> codes = codes(body.get("rows"));
            assertThat(codes).doesNotContain("4500").contains("2100");
        }

        @Test
        void theAuditorCanAskForAllOfThem() {
            JsonNode body = client.get("/ledger/trial-balance", "through", "2024-10-31", "include_empty", "true")
                    .json();
            assertThat(codes(body.get("rows"))).contains("4500");
        }

        @Test
        void aSinceDateMakesItAPeriod() {
            JsonNode body = client.get("/ledger/trial-balance", "through", "2024-10-31", "since", "2024-11-01")
                    .json();
            assertThat(body.get("rows").isArray()).isTrue();
            assertThat(body.get("rows")).isEmpty();
        }

        @Test
        void aDayBeforeAnythingPostedIsEmpty() {
            JsonNode body =
                    client.get("/ledger/trial-balance", "through", "2024-09-30").json();
            assertThat(body.get("rows")).isEmpty();
            assertThat(body.get("balances").asBoolean()).isTrue();
        }
    }

    @Nested
    class Journal {

        @Test
        void aSpanComesBack() {
            Http.Answer found = client.get("/ledger/journal", "opens", "2024-10-01", "closes", "2024-10-31");
            assertThat(found.status()).isEqualTo(200);
            JsonNode body = found.json();
            assertThat(body).hasSize(1);
            assertThat(body.get(0).get("balances").asBoolean()).isTrue();
            assertThat(body.get(0).get("source").asText()).isEqualTo("settlement");
        }

        @Test
        void theLegsComeWithIt() {
            JsonNode legs = october().get(0).get("legs");
            assertThat(legs.size()).isGreaterThanOrEqualTo(2);
            Set<String> sides = new HashSet<>();
            legs.forEach(leg -> sides.add(leg.get("side").asText()));
            assertThat(sides).containsExactlyInAnyOrder("debit", "credit");
        }

        @Test
        void oneEntryCanBeAskedForByNumber() {
            String number = october().get(0).get("number").asText();
            Http.Answer found = client.get("/ledger/journal/entries/" + number);
            assertThat(found.status()).isEqualTo(200);
            assertThat(found.json().get("number").asText()).isEqualTo(number);
        }

        @Test
        void anEntryNobodyPostedIsA404() {
            Http.Answer found = client.get("/ledger/journal/entries/J-NOPE");
            assertThat(found.status()).isEqualTo(404);
            assertThat(found.json().get("code").asText()).isEqualTo("journal_entry_not_found");
        }

        @Test
        void theTrailForADocumentComesBack() {
            Http.Answer found = client.get("/ledger/journal/source/settlement/S-2024-0001");
            assertThat(found.status()).isEqualTo(200);
            assertThat(found.json()).hasSize(1);
        }

        @Test
        void aDocumentWithNoEntriesIsAnEmptyList() {
            Http.Answer found = client.get("/ledger/journal/source/settlement/S-2024-9999");
            assertThat(found.status()).isEqualTo(200);
            assertThat(found.json().isArray()).isTrue();
            assertThat(found.json()).isEmpty();
        }

        @Test
        void theLimitIsHonoured() {
            Http.Answer found =
                    client.get("/ledger/journal", "opens", "2024-10-01", "closes", "2024-10-31", "limit", 1);
            assertThat(found.json()).hasSize(1);
        }
    }

    @Nested
    class Periods {

        @Test
        void everyMonthIsListed() {
            Http.Answer found = client.get("/ledger/periods");
            assertThat(found.status()).isEqualTo(200);
            assertThat(found.json()).hasSize(12);
        }

        @Test
        void theOpenOnesCanBeAskedFor() {
            assertThat(codesInOrder(periods("open_only", "true"))).containsExactly("2024-10", "2024-11", "2024-12");
        }

        @Test
        void aClosedMonthSaysWhoClosedIt() {
            JsonNode september = StreamSupport.stream(periods().spliterator(), false)
                    .filter(row -> row.get("code").asText().equals("2024-09"))
                    .findFirst()
                    .orElseThrow();
            assertThat(september.get("closed").asBoolean()).isTrue();
            assertThat(september.get("closed_by").asText()).isEqualTo("office");
        }

        @Test
        void reviewingAMonthWritesNothing() {
            Http.Answer found = client.get("/ledger/periods/2024-10");
            assertThat(found.status()).isEqualTo(200);
            JsonNode body = found.json();
            assertThat(body.get("is_clean").asBoolean()).isTrue();
            assertThat(body.get("settlements").asInt()).isEqualTo(1);
            assertThat(body.get("unposted").isArray()).isTrue();
            assertThat(body.get("unposted")).isEmpty();
            JsonNode october = periods().get(9);
            assertThat(october.get("code").asText()).isEqualTo("2024-10");
            assertThat(october.get("closed").isBoolean()).isTrue();
            assertThat(october.get("closed").asBoolean()).isFalse();
        }

        @Test
        void aMonthNobodyFiledIsA404() {
            assertThat(client.get("/ledger/periods/2029-04").status()).isEqualTo(404);
        }

        @Test
        void aMonthClosesOverHttp() {
            Http.Answer found = client.postJson(
                    "/ledger/periods/2024-10/close", object("closed_by", "R. Merrick", "on", "2024-11-06"));
            assertThat(found.status()).isEqualTo(200);
            assertThat(found.json().get("closed").asBoolean()).isTrue();
            assertThat(codesInOrder(periods("open_only", "true"))).doesNotContain("2024-10");
        }

        @Test
        void aMonthThatWillNotCloseIsAConflict() {
            Http.Answer found = client.postJson("/ledger/periods/2024-11/close", object("closed_by", "office"));
            assertThat(found.status()).isEqualTo(409);
        }

        @Test
        void forceGetsItClosed() {
            Http.Answer found =
                    client.postJson("/ledger/periods/2024-11/close", object("closed_by", "office", "force", true));
            assertThat(found.status()).isEqualTo(200);
            assertThat(found.json().get("closed").asBoolean()).isTrue();
            assertThat(found.json().get("is_clean").asBoolean()).isFalse();
            assertThat(found.json().get("is_clean").isBoolean()).isTrue();
        }

        @Test
        void aClosedMonthReopensWithAReason() {
            closeOctober();
            Http.Answer found =
                    client.postJson("/ledger/periods/2024-10/reopen", object("reason", "a load was keyed late"));
            assertThat(found.status()).isEqualTo(200);
            assertThat(found.json().get("closed").asBoolean()).isFalse();
            assertThat(found.json().get("closed").isBoolean()).isTrue();
        }

        @Test
        void reopeningWithoutAReasonIsRefused() {
            closeOctober();
            Http.Answer found = client.postJson("/ledger/periods/2024-10/reopen", object("reason", "  "));
            assertThat(found.status()).isEqualTo(422);
        }

        @Test
        void aYearCanBeFiled() {
            Http.Answer found = client.post("/ledger/years/2025");
            assertThat(found.status()).isEqualTo(200);
            assertThat(found.json()).hasSize(12);
        }

        @Test
        void filingTheSameYearTwiceIsAConflict() {
            client.post("/ledger/years/2025");
            assertThat(client.post("/ledger/years/2025").status()).isEqualTo(409);
        }
    }

    @Nested
    class NoWriteIntoTheJournal {

        private static final Set<RequestMethod> WRITES =
                Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

        /** Every route the service mounts under {@code prefix}, as method and path. */
        private Set<String> mounted(String prefix, Set<RequestMethod> methods) {
            Set<String> found = new HashSet<>();
            for (RequestMappingInfo route : routes.getHandlerMethods().keySet()) {
                for (String path : route.getPatternValues()) {
                    for (RequestMethod method : route.getMethodsCondition().getMethods()) {
                        if (path.startsWith(prefix) && methods.contains(method)) {
                            found.add(method + " " + path);
                        }
                    }
                }
            }
            return found;
        }

        @Test
        void thereIsNoRouteThatPostsAnEntry() {
            assertThat(mounted("/ledger/journal", WRITES)).isEmpty();
            assertThat(mounted("/ledger/journal", Set.of(RequestMethod.GET)))
                    .contains("GET /ledger/journal", "GET /ledger/journal/entries/{number}");
            assertThat(mounted("/ledger", WRITES)).contains("POST /ledger/periods/{code}/close");
        }

        @Test
        void postingToTheJournalIsNotAllowed() {
            assertThat(client.postJson("/ledger/journal", object()).status()).isEqualTo(405);
        }
    }

    @Nested
    class PathsDoNotShadowEachOther {

        @Test
        void theSourceTrailIsNotReadAsAnEntryNumber() {
            Http.Answer found = client.get("/ledger/journal/source/settlement/S-2024-0001");
            assertThat(found.status()).isEqualTo(200);
            assertThat(found.json().isArray()).isTrue();
        }

        @Test
        void theTrialBalanceIsNotReadAsAPeriod() {
            Http.Answer found = client.get("/ledger/trial-balance", "through", "2024-10-31");
            assertThat(found.status()).isEqualTo(200);
            assertThat(found.json().has("rows")).isTrue();
        }
    }
}
