package io.github.rkaushikdev.bushelworks.api.security;

import static io.github.rkaushikdev.bushelworks.api.Http.object;
import static io.github.rkaushikdev.bushelworks.api.security.ApiSecurityTest.OFFICE_KEY;
import static io.github.rkaushikdev.bushelworks.api.security.ApiSecurityTest.READ_KEY;
import static io.github.rkaushikdev.bushelworks.api.security.ApiSecurityTest.SCALE_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.servicetest.TestService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Which key gets into which part of the house. */
@RouteTest
@TestPropertySource(properties = TestService.PREFIX + "BUSHELWORKS_API_KEYS=" + ApiSecurityTest.KEY_LIST)
class ApiRolesTest {

    @Autowired
    private MockMvc mvc;

    private Http client;

    @BeforeEach
    void freshHouse() {
        TestDatabase.fresh();
        client = new Http(mvc);
    }

    /** Merrick's load of wet corn, weighed on 3 October. */
    private static Map<String, Object> aTicket() {
        return object(
                "ticket_number", "A-10441",
                "location_code", "HOME",
                "scale_date", "2024-10-03",
                "commodity_code", "CORN",
                "party_code", "MERRICK",
                "gross_pounds", "78420",
                "tare_pounds", "26180",
                "disposition", "cash_sale",
                "splits", List.of(object("party_code", "MERRICK", "percent", "100")),
                "sample",
                        List.of(
                                object("factor", "moisture", "value", "18.2"),
                                object("factor", "test_weight", "value", "56.5"),
                                object("factor", "bcfm", "value", "1.2"),
                                object("factor", "damage_total", "value", "1.0")));
    }

    private static Map<String, Object> aSettlement() {
        return object(
                "party_code", "MERRICK",
                "location_code", "HOME",
                "settled_on", "2024-10-20",
                "spot_price", "4.3500");
    }

    @Test
    void theScaleKeyWritesTickets() {
        assertThat(client.as(SCALE_KEY).postJson("/tickets", aTicket()).status())
                .isEqualTo(201);
    }

    @Test
    void theOfficeKeyAlsoWritesTickets() {
        assertThat(client.as(OFFICE_KEY).postJson("/tickets", aTicket()).status())
                .isEqualTo(201);
    }

    @Test
    void theReadKeyCannotWriteATicket() {
        Http.Answer response = client.as(READ_KEY).postJson("/tickets", aTicket());
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().get("code").asText()).isEqualTo("forbidden");
    }

    @Test
    void theReadKeyCannotListTicketsEither() {
        assertThat(client.as(READ_KEY).get("/tickets").status()).isEqualTo(403);
    }

    @Test
    void theScaleKeyCannotSettle() {
        client.as(SCALE_KEY).postJson("/tickets", aTicket());
        Http.Answer response = client.as(SCALE_KEY).postJson("/settlements", aSettlement());
        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void theOfficeKeySettles() {
        client.as(SCALE_KEY).postJson("/tickets", aTicket());
        Http.Answer response = client.as(OFFICE_KEY).postJson("/settlements", aSettlement());
        assertThat(response.status()).isEqualTo(201);
    }

    @Test
    void theScaleKeyCannotWriteAContract() {
        Http.Answer response = client.as(SCALE_KEY)
                .postJson(
                        "/contracts",
                        object(
                                "number", "F-1",
                                "kind", "forward",
                                "party_code", "MERRICK",
                                "commodity_code", "CORN",
                                "location_code", "HOME",
                                "written_on", "2024-05-14",
                                "bushels", "20000",
                                "window_opens", "2024-10-01",
                                "window_closes", "2024-11-30",
                                "flat_price", "4.62"));
        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void theScaleKeyCannotRunTheAccrual() {
        Http.Answer response = client.as(SCALE_KEY).post("/storage/accruals", "through", "2024-12-14");
        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void everyKeyCanReadThePosition() {
        for (String key : List.of(READ_KEY, SCALE_KEY, OFFICE_KEY)) {
            assertThat(client.as(key).get("/positions/2024-11-01").status())
                    .as(key)
                    .isEqualTo(200);
        }
    }

    @Test
    void noKeyAtAllIsA401NotA403() {
        assertThat(client.get("/positions/2024-11-01").status()).isEqualTo(401);
        assertThat(client.postJson("/tickets", aTicket()).status()).isEqualTo(401);
    }

    @Test
    void theForbiddenBodySaysWhichRoleWasNeeded() {
        JsonNode body = client.as(READ_KEY).get("/tickets").json();
        assertThat(body.get("detail").asText()).contains("scale role", "read");
    }
}
