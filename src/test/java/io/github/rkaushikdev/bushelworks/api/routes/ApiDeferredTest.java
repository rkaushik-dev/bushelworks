package io.github.rkaushikdev.bushelworks.api.routes;

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
class ApiDeferredTest {

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void freshHouse() {
        TestDatabase.fresh();
        client = new Http(mvc);
    }

    static Map<String, Object> aLot(Object... overrides) {
        Map<String, Object> body = object(
                "lot_id", "D-1",
                "party_code", "MERRICK",
                "commodity_code", "CORN",
                "location_code", "HOME",
                "bushels", "10000",
                "delivered_on", "2024-10-03");
        return with(body, overrides);
    }

    /** Two of Merrick's unpriced corn lots: 10,000 bushels on 3 October and 5,000 on 12 November. */
    private Http delivered() {
        client.postJson("/deferred/lots", aLot());
        client.postJson("/deferred/lots", aLot("lot_id", "D-2", "bushels", "5000", "delivered_on", "2024-11-12"));
        return client;
    }

    private static Map<String, Object> pricing(String bushels, String unitPrice, String pricedOn) {
        return object("bushels", bushels, "unit_price", unitPrice, "priced_on", pricedOn);
    }

    private static List<String> lotIds(JsonNode lots) {
        List<String> ids = new ArrayList<>();
        lots.forEach(item -> ids.add(item.get("lot_id").asText()));
        return ids;
    }

    @Test
    void takingDeliveryOpensALot() {
        Http.Answer response = client.postJson("/deferred/lots", aLot());
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("open_bushels").asText()).isEqualTo("10000.0000");
        assertThat(body.get("delivered_on").asText()).isEqualTo("2024-10-03");
    }

    @Test
    void theSameLotTwiceIsA409() {
        assertThat(delivered().postJson("/deferred/lots", aLot()).status()).isEqualTo(409);
    }

    @Test
    void aLotOfNothingIsA422() {
        Http.Answer response = client.postJson("/deferred/lots", aLot("bushels", "0"));
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void listingTheUnpricedLots() {
        JsonNode body =
                delivered().get("/deferred/lots", "party_code", "MERRICK").json();
        assertThat(body.get("total").asInt()).isEqualTo(2);
        assertThat(lotIds(body.get("items"))).containsExactly("D-1", "D-2");
    }

    @Test
    void pricingALotReturnsTheGrossAndTheFee() {
        Http.Answer response =
                delivered().postJson("/deferred/lots/D-1/pricing", pricing("4000", "4.3500", "2024-11-02"));
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.get("gross").asText()).isEqualTo("17400.00");
        assertThat(body.get("service_fee").asText()).isEqualTo("120.00");
        assertThat(body.get("net").asText()).isEqualTo("17280.00");
    }

    @Test
    void pricingMoreThanIsOpenIsA422() {
        Http.Answer response =
                delivered().postJson("/deferred/lots/D-1/pricing", pricing("20000", "4.35", "2024-11-02"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("deferred_price_error");
    }

    @Test
    void pricingBeforeDeliveryIsA409() {
        Http.Answer response = delivered().postJson("/deferred/lots/D-1/pricing", pricing("100", "4.35", "2024-09-01"));
        assertThat(response.status()).isEqualTo(409);
    }

    @Test
    void pricingALotThatIsNotThereIsA404() {
        Http.Answer response = client.postJson("/deferred/lots/D-9/pricing", pricing("100", "4.35", "2024-11-02"));
        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void theProducerPositionAddsTheLotsUp() {
        JsonNode body = delivered()
                .get("/deferred/position/MERRICK/CORN", "as_of", "2024-11-12")
                .json();
        assertThat(body.get("bushels").asText()).isEqualTo("15000.0000");
        assertThat(body.get("oldest_delivery").asText()).isEqualTo("2024-10-03");
        assertThat(body.get("is_overdue").asBoolean()).isFalse();
        assertThat(body.get("is_overdue").isBoolean()).isTrue();
    }

    @Test
    void aProducerWithNothingUnpricedIsA404() {
        Http.Answer response = delivered().get("/deferred/position/HOLLIS/CORN", "as_of", "2024-11-12");
        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void thePositionNamesTheOverdueLots() {
        JsonNode body = delivered()
                .get("/deferred/position/MERRICK/CORN", "as_of", "2025-12-01")
                .json();
        assertThat(body.get("is_overdue").asBoolean()).isTrue();
        List<String> overdue = new ArrayList<>();
        body.get("overdue_lots").forEach(lot -> overdue.add(lot.asText()));
        assertThat(overdue).containsExactlyInAnyOrder("D-1", "D-2");
    }

    @Test
    void theHouseExposureIsTheUnpricedBushels() {
        JsonNode body = delivered()
                .get("/deferred/exposure/CORN", "as_of", "2024-11-12")
                .json();
        assertThat(body.get("bushels").asText()).isEqualTo("15000.0000");
        assertThat(body.get("total_unpriced").asText()).isEqualTo("15000.0000");
    }

    @Test
    void exposureIgnoresALaterDelivery() {
        JsonNode body = delivered()
                .get("/deferred/exposure/CORN", "as_of", "2024-10-15")
                .json();
        assertThat(body.get("bushels").asText()).isEqualTo("10000.0000");
    }

    @Test
    void nothingIsOverdueInsideTheTerm() {
        JsonNode lots =
                delivered().get("/deferred/overdue", "as_of", "2025-01-01").json();
        assertThat(lots.isArray()).isTrue();
        assertThat(lots).isEmpty();
    }

    @Test
    void theOverdueListNamesTheLots() {
        JsonNode lots =
                delivered().get("/deferred/overdue", "as_of", "2025-12-01").json();
        assertThat(lotIds(lots)).containsExactly("D-1", "D-2");
    }

    @Test
    void theDeferredRoutesAreDocumented() {
        JsonNode paths = client.get("/openapi.json").json().get("paths");
        assertThat(paths.has("/deferred/lots")).isTrue();
        assertThat(paths.has("/deferred/lots/{lot_id}/pricing")).isTrue();
    }
}
