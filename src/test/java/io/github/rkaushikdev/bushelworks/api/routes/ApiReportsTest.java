package io.github.rkaushikdev.bushelworks.api.routes;

import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.servicetest.TestService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

class ApiReportsTest {

    /** The crop year opening in September 2024. */
    private static final Object[] CROP_YEAR = {"year", 2024};

    private static Object[] cropYearWith(Object... more) {
        List<Object> query = new ArrayList<>(List.of(CROP_YEAR));
        query.addAll(List.of(more));
        return query.toArray();
    }

    /**
     * Open an account for {@code partyCode}, weigh {@code count} loads of corn numbered from {@code
     * start}, and settle them on 20 October.
     */
    static Http.Answer deliverAndSettle(Http client, String partyCode, int count, int start) {
        String name = partyCode.charAt(0) + partyCode.substring(1).toLowerCase(Locale.ROOT) + " Farms";
        client.postJson("/parties", object("code", partyCode, "name", name));
        for (int index = 0; index < count; index++) {
            client.postJson(
                    "/tickets", ApiTicketsTest.aBody("ticket_number", "A-" + (start + index), "party_code", partyCode));
        }
        return client.postJson(
                "/settlements",
                object(
                        "party_code", partyCode,
                        "location_code", "HOME",
                        "settled_on", "2024-10-20",
                        "spot_price", "4.3500"));
    }

    private static JsonNode lineFor(JsonNode table, String partyCode) {
        return StreamSupport.stream(table.get("lines").spliterator(), false)
                .filter(line -> line.get("party_code").asText().equals(partyCode))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> partyCodes(JsonNode table) {
        List<String> codes = new ArrayList<>();
        table.get("lines").forEach(line -> codes.add(line.get("party_code").asText()));
        return codes;
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

        /** Merrick settled on three loads and Hollis on one. */
        private Http settled() {
            deliverAndSettle(client, "MERRICK", 3, 10441);
            deliverAndSettle(client, "HOLLIS", 1, 20441);
            return client;
        }

        private JsonNode patronage(Object... query) {
            return client.get("/reports/patronage", query).json();
        }

        @Test
        void aQuietHouseHasAnEmptyTable() {
            JsonNode body = patronage(CROP_YEAR);
            assertThat(body.get("lines").isArray()).isTrue();
            assertThat(body.get("lines").size()).isZero();
            assertThat(body.get("bushels").asText()).isEqualTo("0.0000");
        }

        @Test
        void theTableListsTheProducersWhoSettled() {
            settled();
            assertThat(partyCodes(patronage(CROP_YEAR))).containsExactlyInAnyOrder("MERRICK", "HOLLIS");
        }

        @Test
        void theTableIsBiggestFirst() {
            settled();
            assertThat(patronage(CROP_YEAR)
                            .get("lines")
                            .get(0)
                            .get("party_code")
                            .asText())
                    .isEqualTo("MERRICK");
        }

        @Test
        void theTableCarriesTheSpanOfTheCropYear() {
            settled();
            JsonNode body = patronage(CROP_YEAR);
            assertThat(body.get("opens").asText()).isEqualTo("2024-09-01");
            assertThat(body.get("closes").asText()).isEqualTo("2025-08-31");
        }

        @Test
        void aNamedSpanIsUsedInsteadOfACropYear() {
            settled();
            JsonNode body = patronage("opens", "2024-10-01", "closes", "2024-10-31");
            assertThat(body.get("opens").asText()).isEqualTo("2024-10-01");
            assertThat(body.get("lines").size()).isEqualTo(2);
        }

        @Test
        void aSpanThatMissesTheSettlementsIsEmpty() {
            settled();
            JsonNode body = patronage("opens", "2024-11-01", "closes", "2024-11-30");
            assertThat(body.get("lines").isArray()).isTrue();
            assertThat(body.get("lines").size()).isZero();
        }

        @Test
        void halfASpanIsRefused() {
            Http.Answer response = settled().get("/reports/patronage", "opens", "2024-10-01");
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void quantitiesComeBackAsStrings() {
            settled();
            JsonNode first = patronage(CROP_YEAR).get("lines").get(0);
            assertThat(first.get("bushels").isTextual()).isTrue();
            assertThat(first.get("margin_per_bushel").isTextual()).isTrue();
        }

        @Test
        void theSharesAddToAHundred() {
            settled();
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode line : patronage(CROP_YEAR).get("lines")) {
                total = total.add(new BigDecimal(line.get("share_percent").asText()));
            }
            assertThat(total).isCloseTo(new BigDecimal("100"), within(new BigDecimal("0.01")));
        }

        @Test
        void everybodyIsAMemberByDefault() {
            settled();
            assertThat(patronage(CROP_YEAR).get("lines")).isNotEmpty().allSatisfy(line -> assertThat(
                            line.get("is_member").asBoolean())
                    .isTrue());
        }

        @Test
        void aRollMarksTheOthersAsOutsiders() {
            settled();
            JsonNode outsider = lineFor(patronage(cropYearWith("member", "MERRICK")), "HOLLIS");
            assertThat(outsider.get("is_member").asBoolean()).isFalse();
        }

        @Test
        void anotherSiteHasNothingOnIt() {
            settled();
            JsonNode body = patronage(cropYearWith("location_code", "LURAY"));
            assertThat(body.get("lines").size()).isZero();
            assertThat(body.get("location_code").asText()).isEqualTo("LURAY");
        }

        @Test
        void thePrintedPageIsPlainText() {
            Http.Answer response = settled().get("/reports/patronage/table", CROP_YEAR);
            assertThat(response.contentType()).startsWith("text/plain");
            assertThat(response.text()).contains("PATRONAGE BY BUSHELS");
        }

        @Test
        void thePrintedPageFitsEightyColumns() {
            Http.Answer response = settled().get("/reports/patronage/table", CROP_YEAR);
            assertThat(response.text().lines()).isNotEmpty().allSatisfy(line -> assertThat(
                            line.codePointCount(0, line.length()))
                    .isLessThanOrEqualTo(80));
        }

        @Test
        void thePageCanBeRankedOnMargin() {
            Http.Answer response = settled().get("/reports/patronage/table", cropYearWith("order", "margin"));
            assertThat(response.text()).contains("PATRONAGE BY MARGIN");
        }

        @Test
        void anOrderNobodyKnowsIsRefused() {
            Http.Answer response = settled().get("/reports/patronage/table", cropYearWith("order", "sideways"));
            assertThat(response.status()).isEqualTo(422);
        }

        @Test
        void theExportComesBackAsCsv() {
            Http.Answer response = settled().get("/reports/patronage/export", CROP_YEAR);
            assertThat(response.contentType()).startsWith("text/csv");
            assertThat(response.text().lines().findFirst().orElseThrow()).startsWith("party_code,");
        }

        @Test
        void theExportIsNamedAfterTheYear() {
            Http.Answer response = settled().get("/reports/patronage/export", CROP_YEAR);
            assertThat(response.header("Content-Disposition")).contains("patronage-2024.csv");
        }

        @Test
        void theExportHasARowForEachProducer() {
            Http.Answer response = settled().get("/reports/patronage/export", CROP_YEAR);
            assertThat(response.text().strip().lines()).hasSize(3);
        }

        @Test
        void anAllocationSplitsThePool() {
            JsonNode body = settled()
                    .get("/reports/patronage/allocation", cropYearWith("pool", "10000.00"))
                    .json();
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode share : body) {
                total = total.add(new BigDecimal(share.asText()));
            }
            assertThat(total).isCloseTo(new BigDecimal("10000.0"), within(new BigDecimal("0.01")));
        }

        @Test
        void aNonMemberGetsNothing() {
            JsonNode body = settled()
                    .get("/reports/patronage/allocation", cropYearWith("pool", "10000.00", "member", "MERRICK"))
                    .json();
            assertThat(body.properties()).extracting(entry -> entry.getKey()).containsExactly("MERRICK");
        }

        @Test
        void aShareUnderTheMinimumIsDropped() {
            JsonNode body = settled()
                    .get("/reports/patronage/allocation", cropYearWith("pool", "10.00", "minimum", "9.00"))
                    .json();
            assertThat(body.has("HOLLIS")).isFalse();
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
        void theBoardReportsAreOfficeOnly() {
            Http client = new Http(mvc);
            Http.Answer looking = client.as("look").get("/reports/patronage", CROP_YEAR);
            Http.Answer allowed = client.as("office").get("/reports/patronage", CROP_YEAR);
            assertThat(looking.status()).isEqualTo(403);
            assertThat(allowed.status()).isEqualTo(200);
        }
    }
}
