package io.github.rkaushikdev.bushelworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.Bushelworks;
import io.github.rkaushikdev.bushelworks.BushelworksError;
import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.LookupFailure;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.ValidationError;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.Migrations;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.servicetest.ProbeRoutes;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@RouteTest
@Import(ProbeRoutes.class)
class ApiAppTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Settings settings;

    private Http client;

    @BeforeEach
    void freshHouse() {
        TestDatabase.fresh();
        client = new Http(mvc);
    }

    /** {@code /ready} over a database of the test's own, with nothing else of the application around it. */
    private static JsonNode readyOver(Database database) {
        return new Http(MockMvcBuilders.standaloneSetup(new ServiceRoutes(database))
                        .build())
                .get("/ready")
                .json();
    }

    @Test
    void healthAnswersWithoutTouchingTheDatabase() {
        Http.Answer response = client.get("/health");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("status").asText()).isEqualTo("ok");
        assertThat(response.json().get("version").asText()).isNotEmpty();
    }

    @Test
    void readyOpensASession() {
        Http.Answer response = client.get("/ready");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("status").asText()).isEqualTo("ready");
    }

    @Test
    void aSchemaWithNoMigrationHistoryReportsUnmanaged() throws SQLException {
        try (Database database = Database.open("jdbc:h2:mem:ready-unmanaged")) {
            database.migrations().upgrade();
            try (Connection connection = database.dataSource().getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE \"" + Migrations.HISTORY_TABLE + "\"");
            }
            assertThat(readyOver(database).get("schema").asText()).isEqualTo("unmanaged");
        }
    }

    @Test
    void aMigratedSchemaReportsItsRevision(@TempDir Path directory) {
        try (Database database = Database.open("jdbc:h2:file:" + directory.resolve("ready"))) {
            database.migrations().upgrade();
            String revision = readyOver(database).get("schema").asText();
            assertThat(revision).isNotIn("unmanaged", "empty");
            assertThat(revision).isEqualTo(String.valueOf(database.migrations().version()));
        }
    }

    @Test
    void everyResponseCarriesARequestId() {
        Http.Answer response = client.get("/health");
        assertThat(response.header("x-request-id")).hasSize(16);
    }

    @Test
    void anIncomingRequestIdIsKept() {
        Http.Answer response = client.header("x-request-id", "abc123").get("/health");
        assertThat(response.header("x-request-id")).isEqualTo("abc123");
    }

    @Test
    void aValidationErrorIsA422WithItsCode() {
        Http.Answer response = client.get("/_test/validation");
        assertThat(response.status()).isEqualTo(422);
        JsonNode body = response.json();
        assertThat(body.get("code").asText()).isEqualTo("tare_exceeds_gross");
        assertThat(body.get("detail").asText()).isEqualTo("tare exceeds gross");
        assertThat(body.get("request_id").asText()).isEqualTo(response.header("x-request-id"));
    }

    @Test
    void aLookupFailureIsA404() {
        Http.Answer response = client.get("/_test/missing");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("code").asText()).isEqualTo("not_found");
    }

    @Test
    void aConflictIsA409() {
        Http.Answer response = client.get("/_test/conflict");
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("code").asText()).isEqualTo("conflict");
    }

    @Test
    void aConfigurationErrorIsA500() {
        Http.Answer response = client.get("/_test/misconfigured");
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.json().get("code").asText()).isEqualTo("configuration_error");
    }

    @Test
    void anUnclassifiedDomainErrorIsA400() {
        Http.Answer response = client.get("/_test/plain");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("code").asText()).isEqualTo("bushelworks_error");
    }

    @Test
    void theMostSpecificClassDecidesTheStatus() {
        assertThat(Errors.statusFor(new LookupFailure("x"))).isEqualTo(404);
        assertThat(Errors.statusFor(new ValidationError("x"))).isEqualTo(422);
        assertThat(Errors.statusFor(new ConflictError("x"))).isEqualTo(409);
        assertThat(Errors.statusFor(new BushelworksError("x"))).isEqualTo(400);
    }

    @Test
    void theOpenapiDocumentDescribesTheService() {
        JsonNode document = client.get("/openapi.json").json();
        assertThat(document.get("info").get("title").asText()).isEqualTo("bushelworks");
        assertThat(document.get("info").get("description").asText()).contains("country grain elevators");
    }

    @Test
    void theAppCarriesItsSettings() {
        assertThat(settings.stateCode()).isEqualTo("KS");
    }

    @Test
    void theFactoryServesOnAPortOfItsOwn() throws Exception {
        Settings house = Settings.defaults().withDatabaseUrl(TestDatabase.URL);
        try (ConfigurableApplicationContext app =
                BushelworksApplication.run(house, TestDatabase.fresh(), "--server.port=0")) {
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(Json.mapper().readTree(response.body()).get("version").asText())
                    .isEqualTo(Bushelworks.VERSION);
            assertThat(response.headers().firstValue("x-request-id")).isPresent();
        }
    }
}
