package io.github.rkaushikdev.bushelworks.api.routes;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.api.Http.list;
import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static io.github.rkaushikdev.bushelworks.api.Http.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.api.schemas.Body;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.BinRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@RouteTest
class ApiBinsTest {

    @Autowired
    private MockMvc mvc;

    private Http client;

    /** A yard at HOME of two aerated bins: B-01 holding 40,000 bushels and B-02 holding 100,000. */
    @BeforeEach
    void freshYard() {
        Database database = TestDatabase.fresh();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            for (String[] bin : new String[][] {{"B-01", "40000"}, {"B-02", "100000"}}) {
                BinRow row = new BinRow();
                row.setBinId(bin[0]);
                row.setLocationCode("HOME");
                row.setCapacityBushels(d(bin[1]));
                row.setAerated(true);
                uow.session().persist(row);
            }
            uow.commit();
        }
        client = new Http(mvc);
    }

    static Map<String, Object> aLoad(Object... overrides) {
        Map<String, Object> body = object(
                "commodity_code", "CORN",
                "bushels", "10000",
                "sample",
                        list(
                                object("factor", "moisture", "value", "14.0"),
                                object("factor", "test_weight", "value", "56.0")));
        return with(body, overrides);
    }

    @Test
    void theYardListsItsBins() {
        JsonNode bins = client.get("/bins/HOME").json();
        List<String> ids = new ArrayList<>();
        bins.forEach(item -> ids.add(item.get("bin_id").asText()));
        assertThat(ids).containsExactly("B-01", "B-02");
        assertThat(bins.get(0).get("commodity_code").isNull()).isTrue();
    }

    @Test
    void aBinReadsBack() {
        JsonNode body = client.get("/bins/HOME/B-02").json();
        assertThat(body.get("capacity_bushels").asText()).isEqualTo("100000.0000");
        assertThat(body.get("headroom").asText()).isEqualTo("97000.0000");
    }

    @Test
    void aMissingBinIsA404() {
        Http.Answer response = client.get("/bins/HOME/B-99");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("bin_not_found");
    }

    @Test
    void puttingALoadAwayPicksABin() {
        Http.Answer response = client.postJson("/bins/HOME/loads", aLoad("bushels", "1000"));
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("bin_id").asText()).isEqualTo("B-01");
    }

    @Test
    void aLoadCanNameItsBin() {
        Http.Answer response = client.postJson("/bins/HOME/loads", aLoad("bin_id", "B-02"));
        assertThat(response.json().get("bin_id").asText()).isEqualTo("B-02");
        assertThat(response.json().get("bushels").asText()).isEqualTo("10000.0000");
    }

    @Test
    void theSampleBecomesTheBinBlend() {
        client.postJson("/bins/HOME/loads", aLoad("bin_id", "B-02"));
        JsonNode body = client.get("/bins/HOME/B-02").json();
        assertThat(body.get("moisture").asText()).isEqualTo("14.0000");
        assertThat(body.get("test_weight").asText()).isEqualTo("56.0000");
    }

    @Test
    void aSecondLoadBlends() {
        client.postJson("/bins/HOME/loads", aLoad("bin_id", "B-02"));
        client.postJson(
                "/bins/HOME/loads",
                aLoad(
                        "bin_id",
                        "B-02",
                        "sample",
                        list(
                                object("factor", "moisture", "value", "18.0"),
                                object("factor", "test_weight", "value", "54.0"))));
        JsonNode body = client.get("/bins/HOME/B-02").json();
        assertThat(body.get("moisture").asText()).isEqualTo("16.0000");
    }

    @Test
    void aLoadOfAnotherCommodityIsA409() {
        client.postJson("/bins/HOME/loads", aLoad("bin_id", "B-02"));
        Http.Answer response = client.postJson("/bins/HOME/loads", aLoad("bin_id", "B-02", "commodity_code", "SOYB"));
        assertThat(response.status()).isEqualTo(409);
    }

    @Test
    void aLoadTooBigForTheYardIsA422() {
        Http.Answer response = client.postJson("/bins/HOME/loads", aLoad("bushels", "500000"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("bin_yard_error");
    }

    @Test
    void drawingTakesGrainOut() {
        client.postJson("/bins/HOME/loads", aLoad("bin_id", "B-02"));
        Http.Answer response = client.post("/bins/HOME/B-02/draws", "bushels", "4000");
        assertThat(response.json().get("bushels").asText()).isEqualTo("6000.0000");
    }

    @Test
    void drawingMoreThanIsThereIsA422() {
        client.postJson("/bins/HOME/loads", aLoad("bin_id", "B-02"));
        Http.Answer response = client.post("/bins/HOME/B-02/draws", "bushels", "20000");
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void aReadingIsLogged() {
        Http.Answer response = client.postJson(
                "/bins/HOME/B-02/readings", object("taken_at", "2024-11-27T06:00:00Z", "temperature_c", "12.4"));
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("warm").asText()).isEqualTo("no");
    }

    @Test
    void aWarmReadingSaysSo() {
        Http.Answer response = client.postJson(
                "/bins/HOME/B-02/readings", object("taken_at", "2024-11-27T06:00:00Z", "temperature_c", "26.0"));
        assertThat(response.json().get("warm").asText()).isEqualTo("yes");
    }

    @Test
    void takingABinOutOfService() {
        Http.Answer response = client.post("/bins/HOME/B-01/out-of-service", "reason", "fan motor");
        assertThat(response.json().get("out_of_service").asBoolean()).isTrue();
        assertThat(response.json().get("out_of_service").isBoolean()).isTrue();
        assertThat(response.json().get("out_of_service_reason").asText()).isEqualTo("fan motor");
    }

    @Test
    void takingABinOutTwiceIsA409() {
        client.post("/bins/HOME/B-01/out-of-service", "reason", "fan motor");
        Http.Answer response = client.post("/bins/HOME/B-01/out-of-service", "reason", "again");
        assertThat(response.status()).isEqualTo(409);
    }

    @Test
    void theSheetPrints() {
        client.postJson("/bins/HOME/loads", aLoad("bin_id", "B-02"));
        Http.Answer response = client.get("/bins/HOME/report/sheet");
        assertThat(response.contentType()).startsWith("text/plain");
        assertThat(response.text()).contains("BIN SHEET  HOME");
    }

    @Test
    void theSheetExportsAsACsv() {
        Http.Answer response = client.get("/bins/HOME/report/export");
        assertThat(response.contentType()).startsWith("text/csv");
        assertThat(response.header("Content-Disposition")).contains("bins-HOME.csv");
    }

    @Test
    void theSummaryReportsTheYard() {
        client.postJson("/bins/HOME/loads", aLoad("bin_id", "B-02"));
        JsonNode body = client.get("/bins/HOME/report/summary").json();
        assertThat(body.get("bushels").asText()).isEqualTo("10000.0000");
        assertThat(body.get("room").asText()).isNotEqualTo("0");
    }

    @Test
    void aBinCalledSummaryWouldNotShadowTheReport() {
        assertThat(client.get("/bins/HOME/summary").status()).isEqualTo(404);
        assertThat(client.get("/bins/HOME/report/summary").status()).isEqualTo(200);
    }

    @Test
    void theSummaryNamesTheBinsRunningWarmSinceAMoment() {
        client.postJson(
                "/bins/HOME/B-02/readings", object("taken_at", "2024-11-27T06:00:00Z", "temperature_c", "26.0"));
        assertThat(client.get("/bins/HOME/report/summary", "since", "2024-11-27T05:00:00Z")
                        .json()
                        .get("warm")
                        .asText())
                .isEqualTo("B-02");
        assertThat(client.get("/bins/HOME/report/summary", "since", "2024-11-27T07:00:00Z")
                        .json()
                        .get("warm")
                        .asText())
                .isEmpty();
        Http.Answer refused = client.get("/bins/HOME/report/summary", "since", "yesterday");
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.json().get("detail").asText()).isEqualTo("since: " + Body.NOT_A_MOMENT);
    }
}
