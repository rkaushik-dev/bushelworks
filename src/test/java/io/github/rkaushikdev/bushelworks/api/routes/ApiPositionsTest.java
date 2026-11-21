package io.github.rkaushikdev.bushelworks.api.routes;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@RouteTest
class ApiPositionsTest {

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void freshHouse() {
        TestDatabase.fresh();
        client = new Http(mvc);
    }

    /** Two of Merrick's corn loads across the scale, and 4,000 bushels of his corn in storage. */
    private Http busy() {
        client.postJson("/tickets", ApiTicketsTest.aBody());
        client.postJson("/tickets", ApiTicketsTest.aBody("ticket_number", "A-10442"));
        client.postJson(
                "/storage/lots",
                object(
                        "lot_id", "L-1",
                        "party_code", "MERRICK",
                        "commodity_code", "CORN",
                        "location_code", "HOME",
                        "bushels", "4000",
                        "stored_on", "2024-10-05"));
        return client;
    }

    private JsonNode firstLine(Http busy, Object... query) {
        return busy.get("/positions/2024-11-01", query).json().get("lines").get(0);
    }

    @Test
    void anEmptyHouseHasNoPosition() {
        JsonNode body = client.get("/positions/2024-11-01").json();
        assertThat(body.get("lines")).isEmpty();
        assertThat(body.get("total_owned").asText()).isEqualTo("0.0000");
    }

    @Test
    void thePositionListsTheCommodityOnHand() {
        JsonNode body = busy().get("/positions/2024-11-01").json();
        assertThat(body.get("lines")).hasSize(1);
        assertThat(body.get("lines").get(0).get("commodity_code").asText()).isEqualTo("CORN");
        assertThat(body.get("lines").get(0).get("on_hand").asText()).isEqualTo("1865.7143");
    }

    @Test
    void storageIsShownSeparatelyFromWhatIsOwned() {
        JsonNode line = firstLine(busy());
        assertThat(line.get("storage_obligation").asText()).isEqualTo("4000.0000");
        assertThat(line.get("owned").asText()).isEqualTo("-2134.2857");
    }

    @Test
    void theHedgeCountRoundsTowardZero() {
        JsonNode line = firstLine(busy());
        assertThat(line.get("hedge_contracts").isInt()).isTrue();
        assertThat(line.get("hedge_contracts").asInt()).isZero();
    }

    @Test
    void aSmallerContractSizeGivesMoreLots() {
        JsonNode line = firstLine(busy(), "contract_size", "100");
        assertThat(line.get("hedge_contracts").asInt()).isNotZero();
    }

    @Test
    void oneCommodityCanBeAskedFor() {
        JsonNode body = busy().get("/positions/2024-11-01/commodity/CORN").json();
        assertThat(body.get("commodity_code").asText()).isEqualTo("CORN");
        assertThat(body.get("as_of").asText()).isEqualTo("2024-11-01");
    }

    @Test
    void theSaleSideComesOffTheSaleContracts() {
        JsonNode flat = busy().get("/positions/2024-11-01/commodity/CORN").json();
        assertThat(flat.get("sold_forward").asText()).isEqualTo("0.0000");
    }

    @Test
    void grainInTransitCanStillBePassedIn() {
        Http busy = busy();
        JsonNode plain = busy.get("/positions/2024-11-01/commodity/CORN").json();
        JsonNode moving = busy.get("/positions/2024-11-01/commodity/CORN", "in_transit", "1000")
                .json();
        assertThat(moving.get("in_transit").asText()).isEqualTo("1000");
        assertThat(new BigDecimal(moving.get("net_long").asText()))
                .isEqualByComparingTo(new BigDecimal(plain.get("net_long").asText()).add(d("1000")));
    }

    @Test
    void theBoardPrintsAsText() {
        Http.Answer response = busy().get("/positions/2024-11-01/board");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).startsWith("text/plain");
        assertThat(response.text()).contains("POSITION AS OF 2024-11-01");
    }

    @Test
    void theHedgeSheetPrintsAsText() {
        Http.Answer response = busy().get("/positions/2024-11-01/hedge-sheet");
        assertThat(response.text()).contains("HEDGE SHEET").contains("CONTRACTS");
    }

    @Test
    void theExportComesBackAsACsvAttachment() {
        Http.Answer response = busy().get("/positions/2024-11-01/export");
        assertThat(response.contentType()).startsWith("text/csv");
        assertThat(response.header("Content-Disposition")).contains("position-2024-11-01.csv");
        assertThat(response.text().split("\n", -1)[0]).startsWith("as_of,commodity_code");
    }

    @Test
    void theUnhedgedGapReportsTheWholePosition() {
        Http busy = busy();
        JsonNode body = busy.get("/positions/2024-11-01/unhedged").json();
        JsonNode line = firstLine(busy);
        assertThat(body.get("CORN").asText()).isEqualTo(line.get("net_long").asText());
    }

    @Test
    void thePositionRoutesAreDocumented() {
        JsonNode paths = client.get("/openapi.json").json().get("paths");
        assertThat(paths.has("/positions/{as_of}")).isTrue();
        assertThat(paths.has("/positions/{as_of}/hedge-sheet")).isTrue();
    }
}
