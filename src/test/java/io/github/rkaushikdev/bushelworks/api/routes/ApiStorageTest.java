package io.github.rkaushikdev.bushelworks.api.routes;

import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static io.github.rkaushikdev.bushelworks.api.Http.with;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@RouteTest
class ApiStorageTest {

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
                "lot_id", "L-2024-0031",
                "party_code", "MERRICK",
                "commodity_code", "CORN",
                "location_code", "HOME",
                "bushels", "8000",
                "stored_on", "2024-10-03");
        return with(body, overrides);
    }

    /** Two of Merrick's corn lots: 8,000 bushels stored on 3 October and 4,000 on 20 October. */
    private Http stocked() {
        client.postJson("/storage/lots", aLot());
        client.postJson("/storage/lots", aLot("lot_id", "L-2024-0032", "bushels", "4000", "stored_on", "2024-10-20"));
        return client;
    }

    private static Map<String, Object> aReceipt() {
        return object(
                "receipt_number", "WR-004417",
                "lot_id", "L-2024-0031",
                "grade", "2",
                "issued_on", "2024-10-04");
    }

    private static Map<String, Object> aWithdrawal(String bushels) {
        return object(
                "party_code", "MERRICK",
                "commodity_code", "CORN",
                "bushels", bushels,
                "withdrawn_on", "2024-11-01");
    }

    @Test
    void openingALot() {
        Http.Answer response = client.postJson("/storage/lots", aLot());
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("open_bushels").asText()).isEqualTo("8000.0000");
        assertThat(body.get("accrued_to_date").asText()).isEqualTo("0.00");
    }

    @Test
    void openingTheSameLotTwiceIsA409() {
        assertThat(stocked().postJson("/storage/lots", aLot()).status()).isEqualTo(409);
    }

    @Test
    void readingALot() {
        JsonNode body = stocked().get("/storage/lots/L-2024-0031").json();
        assertThat(body.get("party_code").asText()).isEqualTo("MERRICK");
        assertThat(body.get("billed_through").isNull()).isTrue();
    }

    @Test
    void aMissingLotIsA404() {
        Http.Answer response = client.get("/storage/lots/L-9999");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("lot_not_found");
    }

    @Test
    void listingOpenLots() {
        JsonNode body = stocked().get("/storage/lots", "party_code", "MERRICK").json();
        assertThat(body.get("total").asInt()).isEqualTo(2);
    }

    @Test
    void withdrawingDrawsTheOldestLotFirst() {
        Http.Answer response = stocked().postJson("/storage/withdrawals", aWithdrawal("9000"));
        assertThat(response.status()).isEqualTo(200);
        Map<String, String> touched = new HashMap<>();
        response.json()
                .forEach(item -> touched.put(
                        item.get("lot_id").asText(), item.get("open_bushels").asText()));
        assertThat(touched).containsEntry("L-2024-0031", "0.0000").containsEntry("L-2024-0032", "3000.0000");
    }

    @Test
    void withdrawingMoreThanIsThereIsA422() {
        Http.Answer response = stocked().postJson("/storage/withdrawals", aWithdrawal("20000"));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("code").asText()).isEqualTo("storage_service_error");
    }

    @Test
    void whatIsOnHand() {
        JsonNode body = stocked().get("/storage/on-hand/CORN").json();
        assertThat(body.get("bushels").asText()).isEqualTo("12000.0000");
    }

    @Test
    void aDryRunShowsTheNumbersWithoutBilling() {
        Http stocked = stocked();
        Http.Answer response = stocked.post("/storage/accruals", "through", "2024-12-14", "dry_run", true);
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.get("recorded").asBoolean()).isFalse();
        assertThat(body.get("recorded").isBoolean()).isTrue();
        assertThat(body.get("total").asText()).isNotEqualTo("0.00");
        assertThat(stocked.get("/storage/lots/L-2024-0031")
                        .json()
                        .get("billed_through")
                        .isNull())
                .isTrue();
    }

    @Test
    void aRealRunMovesTheBilledThrough() {
        Http stocked = stocked();
        stocked.post("/storage/accruals", "through", "2024-12-14");
        JsonNode body = stocked.get("/storage/lots/L-2024-0031").json();
        assertThat(body.get("billed_through").asText()).isEqualTo("2024-12-14");
        assertThat(body.get("accrued_to_date").asText()).isEqualTo("504.00");
    }

    @Test
    void runningTheAccrualTwiceChargesNothingAgain() {
        Http stocked = stocked();
        stocked.post("/storage/accruals", "through", "2024-12-14");
        JsonNode second =
                stocked.post("/storage/accruals", "through", "2024-12-14").json();
        assertThat(second.get("lines")).isEmpty();
        assertThat(second.get("total").asText()).isEqualTo("0.00");
    }

    @Test
    void writingAWarehouseReceipt() {
        Http.Answer response = stocked().postJson("/storage/receipts", aReceipt());
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("is_live").asBoolean()).isTrue();
        assertThat(body.get("is_live").isBoolean()).isTrue();
        assertThat(body.get("bushels").asText()).isEqualTo("8000.0000");
    }

    @Test
    void aLotCarriesOneLiveReceipt() {
        Http stocked = stocked();
        stocked.postJson("/storage/receipts", aReceipt());
        Map<String, Object> second = with(aReceipt(), "receipt_number", "WR-004418");
        assertThat(stocked.postJson("/storage/receipts", second).status()).isEqualTo(409);
    }

    @Test
    void cancellingAReceipt() {
        Http stocked = stocked();
        stocked.postJson("/storage/receipts", aReceipt());
        Http.Answer response = stocked.post("/storage/receipts/WR-004417/cancel", "on", "2025-03-01");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("is_live").asBoolean()).isFalse();
        assertThat(response.json().get("is_live").isBoolean()).isTrue();
    }

    @Test
    void cancellingTwiceIsA409() {
        Http stocked = stocked();
        stocked.postJson("/storage/receipts", aReceipt());
        stocked.post("/storage/receipts/WR-004417/cancel", "on", "2025-03-01");
        Http.Answer response = stocked.post("/storage/receipts/WR-004417/cancel", "on", "2025-03-02");
        assertThat(response.status()).isEqualTo(409);
    }

    @Test
    void theStorageRoutesAreDocumented() {
        JsonNode paths = client.get("/openapi.json").json().get("paths");
        assertThat(paths.has("/storage/lots")).isTrue();
        assertThat(paths.has("/storage/accruals")).isTrue();
        assertThat(paths.has("/storage/receipts")).isTrue();
    }
}
