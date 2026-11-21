package io.github.rkaushikdev.bushelworks;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.api.BushelworksApplication;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import io.github.rkaushikdev.bushelworks.services.warehousing.Warehousing;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The events the office greps for have to actually be emitted.
 *
 * <p>Every one of these is a line somebody has asked for at least once while working out where a load
 * went. The names are the contract; the fields can change.
 */
class LoggingWiringTest {

    private Database database;
    private RuleBook rules;
    private ScaleTicket cornTicket;

    @BeforeEach
    void freshHouse() {
        database = TestDatabase.fresh();
        rules = HouseFixtures.rules();
        cornTicket = HouseFixtures.cornTicket();
    }

    /** The lines logged while {@code ticket} was recorded for Merrick and committed. */
    private Capture recorded(ScaleTicket ticket) {
        try (Capture log = Capture.start();
                UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, ticket, "MERRICK", rules);
            uow.commit();
            return log;
        }
    }

    /** Eight thousand bushels of Merrick's corn in storage since 3 October. */
    private void stored() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.store(uow, "L-1", "MERRICK", "CORN", "HOME", d("8000"), LocalDate.of(2024, 10, 3));
            uow.commit();
        }
    }

    @Test
    void recordingALoadLogsIt() {
        assertThat(recorded(cornTicket).find("ticket.recorded"))
                .singleElement()
                .asString()
                .contains("ticket=A-10441", "party=MERRICK", "dry_bushels=897.6324", "grade=1");
    }

    @Test
    void aLoadWithNoContractLogsTheOpenBushels() {
        assertThat(recorded(cornTicket).find("ticket.recorded").get(0)).contains("contracts=-");
    }

    @Test
    void aSampleGradeLoadRaisesAWarning() {
        ScaleTicket bad = cornTicket.toBuilder()
                .sample(HouseFixtures.cornSampleWith(Factor.DAMAGE_TOTAL, "30.0"))
                .build();
        assertThat(recorded(bad).find("ticket.sample_grade"))
                .singleElement()
                .asString()
                .contains("damage_total");
    }

    @Test
    void aCleanLoadRaisesNoWarning() {
        assertThat(recorded(cornTicket).find("ticket.sample_grade")).isEmpty();
    }

    @Test
    void settlingLogsTheTotals() {
        recorded(cornTicket);
        List<String> lines;
        try (Capture log = Capture.start();
                UnitOfWork uow = UnitOfWork.open(database)) {
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, LocalDate.of(2024, 10, 20), d("4.35"));
            uow.commit();
            lines = log.find("settlement.written");
        }
        assertThat(lines).singleElement().asString().contains("number=S-2024-0001", "tickets=1", "net=");
    }

    @Test
    void theAccrualRunLogsWhatItBilled() {
        stored();
        List<String> lines;
        try (Capture log = Capture.start();
                UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.accrue(uow, rules, LocalDate.of(2024, 12, 14));
            uow.commit();
            lines = log.find("storage.accrued");
        }
        assertThat(lines).singleElement().asString().contains("lots=1", "recorded=yes");
    }

    @Test
    void aDryRunSaysItRecordedNothing() {
        stored();
        List<String> lines;
        try (Capture log = Capture.start();
                UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.accrue(uow, rules, LocalDate.of(2024, 12, 14), null, false);
            lines = log.find("storage.accrued");
        }
        assertThat(lines.get(0)).contains("recorded=no");
    }

    /**
     * One request to the whole application, serving on a port of its own. The application is closed
     * before the log is read, so the request has been answered and logged in full by then.
     */
    @Test
    void everyServedRequestIsLogged() throws Exception {
        Settings house = Settings.defaults().withDatabaseUrl(TestDatabase.URL);
        ConfigurableApplicationContext app = BushelworksApplication.run(house, database, "--server.port=0");
        List<String> lines;
        try (Capture log = Capture.start()) {
            try (app) {
                int port = ((WebServerApplicationContext) app).getWebServer().getPort();
                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
            }
            lines = log.find("request.served");
        }
        assertThat(lines).singleElement().asString().contains("path=/health", "status=200", "request_id=");
    }

    @Test
    void theLogLevelComesFromSettings() {
        assertThat(Settings.defaults().logLevel()).isEqualTo("INFO");
        assertThat(Settings.builder().logLevel("ERROR").build().logLevel()).isEqualTo("ERROR");
    }

    @Test
    void aNonsenseLogLevelIsRefused() {
        Settings.Builder chatty = Settings.builder().logLevel("CHATTY");
        assertThatThrownBy(chatty::build).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void theLogLevelIsReadFromTheEnvironment() {
        Map<String, String> environment = Map.of("BUSHELWORKS_LOG_LEVEL", "warning");
        assertThat(Settings.load(environment::get).logLevel()).isEqualTo("WARNING");
    }
}
