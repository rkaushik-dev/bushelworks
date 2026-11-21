package io.github.rkaushikdev.bushelworks.api.routes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Http;
import io.github.rkaushikdev.bushelworks.api.RouteTest;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
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

    @Test
    void anEmptyHouseHasNoPosition() {
        JsonNode body = client.get("/positions/2024-11-01").json();
        assertThat(body.get("lines")).isEmpty();
        assertThat(body.get("total_owned").asText()).isEqualTo("0.0000");
    }

    @Test
    void thePositionRoutesAreDocumented() {
        JsonNode paths = client.get("/openapi.json").json().get("paths");
        assertThat(paths.has("/positions/{as_of}")).isTrue();
        assertThat(paths.has("/positions/{as_of}/hedge-sheet")).isTrue();
    }
}
