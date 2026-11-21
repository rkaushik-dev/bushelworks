package io.github.rkaushikdev.bushelworks.api.routes;

import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static io.github.rkaushikdev.bushelworks.api.Http.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@RouteTest
class ApiSalesTest {

    private static final String SHIPMENTS = "/sales/S-2024-0088/shipments";

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void freshHouse() {
        TestDatabase.fresh();
        client = new Http(mvc);
    }

    /** Ten thousand bushels of corn to the Salina terminal at a flat 4.78, shipping in November or December. */
    static Map<String, Object> aSale(Object... overrides) {
        Map<String, Object> body = object(
                "number", "S-2024-0088",
                "buyer", "Salina Terminal",
                "commodity_code", "CORN",
                "origin_code", "HOME",
                "written_on", "2024-09-05",
                "bushels", "10000",
                "window_opens", "2024-11-01",
                "window_closes", "2024-12-31",
                "flat_price", "4.7800",
                "freight_per_bushel", "0.1800");
        return with(body, overrides);
    }

    /** A car of {@code pounds} loaded out on 12 November as {@code CAR-1}. */
    private static Map<String, Object> aCar(String pounds) {
        return object("reference", "CAR-1", "pounds", pounds, "shipped_on", "2024-11-12");
    }

    private static Map<String, Object> theBuyersSettlement() {
        return object(
                "destination_pounds", "195328",
                "destination_grade", "2",
                "proceeds", "16730.00",
                "settled_on", "2024-11-26");
    }

    /** The sale on file. */
    private Http sold() {
        client.postJson("/sales", aSale());
        return client;
    }

    /** The sale on file with 3,500 bushels loaded against it. */
    private Http shipped() {
        sold().postJson(SHIPMENTS, aCar("196000"));
        return client;
    }

    @Test
    void writingASale() {
        Http.Answer response = client.postJson("/sales", aSale());
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("status").asText()).isEqualTo("open");
        assertThat(body.get("remaining").asText()).isEqualTo("10000");
        assertThat(body.get("shipments").isArray()).isTrue();
        assertThat(body.get("shipments").size()).isZero();
    }

    @Test
    void theSameSaleNumberTwiceIsA409() {
        assertThat(sold().postJson("/sales", aSale()).status()).isEqualTo(409);
    }

    @Test
    void aMissingSaleIsA404() {
        Http.Answer response = client.get("/sales/S-9999");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("sale_not_found");
    }

    @Test
    void aSaleOfNoBushelsIsRefusedAsA422() {
        Http.Answer response = client.postJson("/sales", aSale("bushels", "0"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("sale_error");
    }

    @Test
    void aSaleWithNoPriceIsRefusedAsA422() {
        Http.Answer response = client.postJson("/sales", aSale("flat_price", null));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("sale_error");
    }

    @Test
    void aWindowThatClosesFirstIsRefusedAsA422() {
        Http.Answer response =
                client.postJson("/sales", aSale("window_opens", "2024-12-31", "window_closes", "2024-11-01"));
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void listingOpenSales() {
        JsonNode body = sold().get("/sales", "commodity_code", "CORN").json();
        assertThat(body.get("total").asInt()).isEqualTo(1);
        assertThat(body.get("items").get(0).get("number").asText()).isEqualTo("S-2024-0088");
    }

    @Test
    void loadingOutMovesTheShippedTotal() {
        Http.Answer response = sold().postJson(SHIPMENTS, with(aCar("196000"), "carrier_id", "BNSF 480221"));
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("shipped").asText()).isEqualTo("3500.0000");
        assertThat(body.get("remaining").asText()).isEqualTo("6500.0000");
        assertThat(body.get("shipments").get(0).get("carrier_id").asText()).isEqualTo("BNSF 480221");
    }

    @Test
    void loadingOutsideTheWindowIsA422() {
        Http.Answer response = sold().postJson(SHIPMENTS, with(aCar("196000"), "shipped_on", "2025-03-01"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("shipping_error");
    }

    @Test
    void theSameReferenceTwiceIsA409() {
        assertThat(shipped().postJson(SHIPMENTS, aCar("196000")).status()).isEqualTo(409);
    }

    @Test
    void fillingASaleClosesIt() {
        sold().postJson(SHIPMENTS, aCar("560000"));
        assertThat(client.get("/sales/S-2024-0088").json().get("status").asText())
                .isEqualTo("filled");
    }

    @Test
    void postingTheBuyerSettlement() {
        Http.Answer response = shipped().postJson(SHIPMENTS + "/CAR-1/settlement", theBuyersSettlement());
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.get("destination_grade").asText()).isEqualTo("2");
        assertThat(body.get("transit_shrink").asText()).isEqualTo("12.0000");
    }

    @Test
    void settlingTwiceIsA409() {
        shipped().postJson(SHIPMENTS + "/CAR-1/settlement", theBuyersSettlement());
        Http.Answer response = client.postJson(SHIPMENTS + "/CAR-1/settlement", theBuyersSettlement());
        assertThat(response.status()).isEqualTo(409);
    }

    @Test
    void rejectingACarPutsTheBushelsBack() {
        Http.Answer response = shipped().post(SHIPMENTS + "/CAR-1/rejection", "reason", "16.8 moisture at destination");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("rejected").isBoolean()).isTrue();
        assertThat(response.json().get("rejected").booleanValue()).isTrue();
        assertThat(client.get("/sales/S-2024-0088").json().get("shipped").asText())
                .isEqualTo("0.0000");
    }

    @Test
    void aRejectionNeedsAReason() {
        Http.Answer response = shipped().post(SHIPMENTS + "/CAR-1/rejection", "reason", "");
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void theShortPositionComesOffTheOpenSales() {
        JsonNode body = sold().get("/sales/position/CORN", "on", "2024-11-12").json();
        assertThat(body.get("sold_forward").asText()).isEqualTo("10000.0000");
    }

    @Test
    void loadingReducesTheShortPosition() {
        JsonNode body =
                shipped().get("/sales/position/CORN", "on", "2024-11-12").json();
        assertThat(body.get("sold_forward").asText()).isEqualTo("6500.0000");
    }

    @Test
    void theSaleRoutesAreDocumented() {
        JsonNode paths = client.get("/openapi.json").json().get("paths");
        assertThat(paths.has("/sales")).isTrue();
        assertThat(paths.has("/sales/{number}/shipments")).isTrue();
    }
}
