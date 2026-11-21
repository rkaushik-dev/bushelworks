package io.github.rkaushikdev.bushelworks.api.routes;

import static io.github.rkaushikdev.bushelworks.api.Http.list;
import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static io.github.rkaushikdev.bushelworks.api.Http.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.servicetest.TestService;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

class ApiDeskTest {

    /** A load of corn at 15 percent moisture, numbered {@code number}. */
    static Map<String, Object> aTicket(String number) {
        return ApiTicketsTest.aBody(
                "ticket_number",
                number,
                "sample",
                list(
                        object("factor", "moisture", "value", "15.0"),
                        object("factor", "test_weight", "value", "56.5"),
                        object("factor", "bcfm", "value", "1.2"),
                        object("factor", "damage_total", "value", "1.0")));
    }

    /** One December corn contract sold at 4.3225 on 1 November. */
    static Map<String, Object> aFill(Object... overrides) {
        Map<String, Object> body = object(
                "reference", "F-1001",
                "commodity_code", "CORN",
                "month_code", "Z",
                "year", 2024,
                "side", "short",
                "contracts", 1,
                "price", "4.3225",
                "filled_on", "2024-11-01");
        return with(body, overrides);
    }

    static Map<String, Object> aSheet(Object... more) {
        return with(object("as_of", "2024-11-01"), more);
    }

    /** The sheet's line for one commodity. */
    static JsonNode lineFor(JsonNode sheet, String commodityCode) {
        return StreamSupport.stream(sheet.get("lines").spliterator(), false)
                .filter(line -> line.get("commodity_code").asText().equals(commodityCode))
                .findFirst()
                .orElseThrow();
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

        /** Twenty corn loads across the scale and nothing hedged. */
        private Http busy() {
            for (int index = 0; index < 20; index++) {
                client.postJson("/tickets", aTicket("A-" + (10441 + index)));
            }
            return client;
        }

        private JsonNode exposure(Map<String, Object> body) {
            return client.postJson("/desk/exposure", body).json();
        }

        private Http.Answer carry(String deferred, int months) {
            return client.get(
                    "/desk/carry", "near", "4.20", "deferred", deferred, "months", months, "storage_per_month", "0.05");
        }

        @Test
        void anEmptyHouseHasAnEmptySheet() {
            JsonNode body = client.postJson("/desk/sheet", aSheet()).json();
            assertThat(body.get("lines").isArray()).isTrue();
            assertThat(body.get("lines")).isEmpty();
            assertThat(body.get("total_gap_contracts").asInt()).isZero();
        }

        @Test
        void theSheetNamesTheDayAndTheSite() {
            JsonNode body = busy().postJson("/desk/sheet", aSheet("location_code", "HOME"))
                    .json();
            assertThat(body.get("as_of").asText()).isEqualTo("2024-11-01");
            assertThat(body.get("location_code").asText()).isEqualTo("HOME");
        }

        @Test
        void anUnhedgedHouseWantsToSell() {
            JsonNode corn = lineFor(busy().postJson("/desk/sheet", aSheet()).json(), "CORN");
            assertThat(corn.get("on_contracts").asInt()).isZero();
            assertThat(corn.get("gap_contracts").asInt()).isPositive();
            assertThat(corn.get("side").asText()).isEqualTo("short");
        }

        @Test
        void theFillsComeOffTheGap() {
            Http busy = busy();
            JsonNode plain = busy.postJson("/desk/sheet", aSheet()).json();
            JsonNode hedged =
                    busy.postJson("/desk/sheet", aSheet("fills", list(aFill()))).json();
            int before = plain.get("lines").get(0).get("gap_contracts").asInt();
            int after = hedged.get("lines").get(0).get("gap_contracts").asInt();
            assertThat(after).isEqualTo(before - 1);
        }

        @Test
        void aHedgeOnAnotherCommodityDoesNotHelp() {
            JsonNode body = busy().postJson(
                            "/desk/sheet", aSheet("fills", list(aFill("commodity_code", "SOYB", "month_code", "X"))))
                    .json();
            assertThat(lineFor(body, "CORN").get("on_contracts").asInt()).isZero();
        }

        @Test
        void overHedgingShowsOnTheLine() {
            JsonNode body = busy().postJson("/desk/sheet", aSheet("fills", list(aFill("contracts", 400))))
                    .json();
            JsonNode corn = lineFor(body, "CORN");
            assertThat(corn.get("is_over_hedged").asBoolean()).isTrue();
            assertThat(corn.get("side").asText()).isEqualTo("long");
        }

        @Test
        void quantitiesComeBackAsStrings() {
            JsonNode body = busy().postJson("/desk/sheet", aSheet()).json();
            assertThat(body.get("lines").get(0).get("net_long").isTextual()).isTrue();
        }

        @Test
        void theActionableRouteDropsTheQuietLines() {
            JsonNode lines = busy().postJson("/desk/sheet/actionable", aSheet()).json();
            assertThat(lines).isNotEmpty();
            lines.forEach(
                    line -> assertThat(line.get("is_actionable").asBoolean()).isTrue());
        }

        @Test
        void anEmptyHouseHasNothingActionable() {
            JsonNode lines = client.postJson("/desk/sheet/actionable", aSheet()).json();
            assertThat(lines.isArray()).isTrue();
            assertThat(lines).isEmpty();
        }

        @Test
        void theSheetCanBeLimitedToOneCommodity() {
            JsonNode body = busy().postJson("/desk/sheet", aSheet("commodities", list("SOYB")))
                    .json();
            body.get("lines")
                    .forEach(line ->
                            assertThat(line.get("commodity_code").asText()).isEqualTo("SOYB"));
            assertThat(StreamSupport.stream(body.get("lines").spliterator(), false)
                            .map(line -> line.get("commodity_code").asText()))
                    .doesNotContain("CORN");
        }

        @Test
        void aFillNeedsADeliveryMonthThatTrades() {
            Http.Answer response = client.postJson("/desk/sheet", aSheet("fills", list(aFill("month_code", "B"))));
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void aFillWithNoContractsIsRefused() {
            Http.Answer response = client.postJson("/desk/sheet", aSheet("fills", list(aFill("contracts", 0))));
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void aPriceSentAsAJsonNumberIsRefused() {
            Http.Answer response = client.postJson("/desk/sheet", aSheet("fills", list(aFill("price", 4.3225))));
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void theSameFillReferenceTwiceIsAConflict() {
            Http.Answer response = client.postJson("/desk/sheet", aSheet("fills", list(aFill(), aFill())));
            assertThat(response.status()).isEqualTo(409);
        }

        @Test
        void aShortGainsWhenTheMarketFalls() {
            JsonNode body = exposure(object("fills", list(aFill()), "settlements", object("CORNZ24", "4.2225")));
            assertThat(body.get("CORNZ24").asText()).isEqualTo("500.00");
        }

        @Test
        void aShortLosesWhenTheMarketRises() {
            JsonNode body = exposure(object("fills", list(aFill()), "settlements", object("CORNZ24", "4.4225")));
            assertThat(body.get("CORNZ24").asText()).isEqualTo("-500.00");
        }

        @Test
        void aMonthWithNoSettlementPriceIsRefused() {
            Http.Answer response =
                    client.postJson("/desk/exposure", object("fills", list(aFill()), "settlements", object()));
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void exposureWithNoFillsIsEmpty() {
            JsonNode body = exposure(object("fills", list()));
            assertThat(body.isObject()).isTrue();
            assertThat(body).isEmpty();
        }

        @Test
        void theBasisIsCashLessFutures() {
            JsonNode body = client.get("/desk/basis", "cash_bid", "4.10", "futures", "4.3225")
                    .json();
            assertThat(body.get("basis").asText()).isEqualTo("-0.2225");
        }

        @Test
        void aCarryThatBeatsStoragePaysToStore() {
            JsonNode body = carry("4.45", 3).json();
            assertThat(body.get("pays_to_store").asText()).isEqualTo("yes");
            assertThat(body.get("carry_per_bushel").asText()).isEqualTo("0.1000");
        }

        @Test
        void aCarryThatDoesNotCoverStorageSaysSo() {
            assertThat(carry("4.25", 3).json().get("pays_to_store").asText()).isEqualTo("no");
        }

        @Test
        void aCarryOverNoMonthsIsRefused() {
            assertThat(carry("4.45", 0).status()).isEqualTo(422);
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
        void theDeskIsOfficeOnly() {
            Http client = new Http(mvc);
            Http.Answer looking = client.as("look").postJson("/desk/sheet", aSheet());
            Http.Answer allowed = client.as("office").postJson("/desk/sheet", aSheet());
            assertThat(looking.status()).isEqualTo(403);
            assertThat(allowed.status()).isEqualTo(200);
        }
    }
}
