package io.github.rkaushikdev.bushelworks;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;
import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class LoggingTest {

    @AfterEach
    void putTheLoggerBack() {
        HouseLog.houseLogger().detachAndStopAllAppenders();
        HouseLog.houseLogger().setAdditive(true);
        HouseLog.setRequestId(null);
    }

    @Test
    void aDecimalKeepsItsTrailingZeroes() {
        assertThat(HouseLog.renderValue(d("4.3500"))).isEqualTo("4.3500");
        assertThat(HouseLog.renderValue(d("0"))).isEqualTo("0");
    }

    @Test
    void noneRendersAsADash() {
        assertThat(HouseLog.renderValue(null)).isEqualTo("-");
    }

    @Test
    void booleansRenderAsWords() {
        assertThat(HouseLog.renderValue(true)).isEqualTo("yes");
        assertThat(HouseLog.renderValue(false)).isEqualTo("no");
    }

    @Test
    void anEmptyStringIsVisible() {
        assertThat(HouseLog.renderValue("")).isEqualTo("\"\"");
    }

    @Test
    void aValueWithASpaceIsQuoted() {
        assertThat(HouseLog.renderValue("Merrick Farms")).isEqualTo("\"Merrick Farms\"");
    }

    @Test
    void aValueWithAnEqualsSignIsQuoted() {
        assertThat(HouseLog.renderValue("a=b")).isEqualTo("\"a=b\"");
    }

    @Test
    void aQuoteInsideAValueIsEscaped() {
        assertThat(HouseLog.renderValue("he said \"no\"")).isEqualTo("\"he said \\\"no\\\"\"");
    }

    @Test
    void aPlainValueIsLeftAlone() {
        assertThat(HouseLog.renderValue("MERRICK")).isEqualTo("MERRICK");
    }

    @Test
    void fieldsRenderInTheOrderTheyWereGiven() {
        String text = HouseLog.renderFields(field("ticket", "A-10441"), field("party", "MERRICK"));
        assertThat(text).isEqualTo("ticket=A-10441 party=MERRICK");
    }

    @Test
    void anEventLineCarriesItsFields() {
        Capture log = Capture.during(() -> HouseLog.event(
                HouseLog.getLogger("intake"),
                "ticket.recorded",
                field("ticket", "A-10441"),
                field("bushels", d("897.6324"))));
        assertThat(log.lines()).hasSize(1);
        assertThat(log.lines().get(0))
                .contains("ticket.recorded")
                .contains("ticket=A-10441")
                .contains("bushels=897.6324");
    }

    @Test
    void theLineStartsWithATimestampAndALevel() {
        Capture log = Capture.during(() -> HouseLog.event(HouseLog.getLogger(), "ticket.recorded"));
        String[] parts = log.lines().get(0).split(" ");
        assertThat(parts[0]).startsWith("20");
        assertThat(parts[1]).isEqualTo("info");
        assertThat(parts[2]).isEqualTo("ticket.recorded");
    }

    @Test
    void aWarningSaysSo() {
        Capture log = Capture.during(
                () -> HouseLog.warn(HouseLog.getLogger(), "contract.overfilled", field("contract", "F-1")));
        assertThat(log.lines().get(0)).contains(" warning ");
    }

    @Test
    void aFailureSaysSo() {
        Capture log = Capture.during(
                () -> HouseLog.failure(HouseLog.getLogger(), "settlement.refused", field("party", "MERRICK")));
        assertThat(log.lines().get(0)).contains(" error ");
    }

    @Test
    void theRequestIdRidesAlong() {
        HouseLog.setRequestId("abc123");
        try {
            Capture log = Capture.during(
                    () -> HouseLog.event(HouseLog.getLogger(), "ticket.recorded", field("ticket", "A-10441")));
            assertThat(log.lines().get(0)).contains("request_id=abc123");
            assertThat(HouseLog.currentRequestId()).contains("abc123");
        } finally {
            HouseLog.setRequestId(null);
        }
    }

    @Test
    void noRequestIdLeavesTheFieldOff() {
        HouseLog.setRequestId(null);
        Capture log = Capture.during(() -> HouseLog.event(HouseLog.getLogger(), "ticket.recorded"));
        assertThat(log.lines().get(0)).doesNotContain("request_id");
    }

    @Test
    void captureFindsLinesByEventName() {
        Capture log = Capture.during(() -> {
            HouseLog.event(HouseLog.getLogger(), "ticket.recorded", field("ticket", "A-1"));
            HouseLog.event(HouseLog.getLogger(), "settlement.written", field("number", "S-1"));
        });
        assertThat(log.find("ticket.recorded")).hasSize(1);
        assertThat(log.find("settlement.written")).hasSize(1);
        assertThat(log.find("nothing.happened")).isEqualTo(List.of());
    }

    @Test
    void configureReplacesItsOwnHandler() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        HouseLog.configure("INFO", stream);
        Logger logger = HouseLog.configure("INFO", stream);
        assertThat(HouseLog.handlerCount()).isEqualTo(1);
        HouseLog.event(logger, "ticket.recorded", field("ticket", "A-10441"));
        assertThat(stream.toString(StandardCharsets.UTF_8)).contains("ticket=A-10441");
    }

    @Test
    void configureHonoursTheLevel() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        Logger logger = HouseLog.configure("ERROR", stream);
        HouseLog.event(logger, "ticket.recorded");
        HouseLog.failure(logger, "settlement.refused");
        String output = stream.toString(StandardCharsets.UTF_8);
        assertThat(output).doesNotContain("ticket.recorded").contains("settlement.refused");
    }

    @Test
    void thePackageLoggerDoesNotPropagate() {
        HouseLog.configure("INFO", new ByteArrayOutputStream());
        assertThat(HouseLog.propagates()).isFalse();
    }

    @Test
    void childLoggersAreNamespaced() {
        assertThat(HouseLog.getLogger("intake").getName()).isEqualTo("bushelworks.intake");
        assertThat(HouseLog.getLogger().getName()).isEqualTo("bushelworks");
    }
}
