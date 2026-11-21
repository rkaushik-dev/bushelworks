package io.github.rkaushikdev.bushelworks.reports.fulfilment;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.reports.fulfilment.Fulfilment.atRisk;
import static io.github.rkaushikdev.bushelworks.reports.fulfilment.Fulfilment.lineFor;
import static io.github.rkaushikdev.bushelworks.reports.fulfilment.Fulfilment.outstandingTotal;
import static io.github.rkaushikdev.bushelworks.reports.fulfilment.Fulfilment.render;
import static io.github.rkaushikdev.bushelworks.reports.fulfilment.Fulfilment.shortList;
import static io.github.rkaushikdev.bushelworks.reports.fulfilment.Fulfilment.toCsv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FulfilmentTest {

    static final LocalDate AS_OF = LocalDate.of(2024, 10, 20);
    static final LocalDate WINDOW_CLOSES = LocalDate.of(2024, 11, 30);
    static final LocalDate ALREADY_CLOSED = LocalDate.of(2024, 10, 1);

    static PurchaseContract aContract() {
        return aContract("F-2024-118", WINDOW_CLOSES);
    }

    static PurchaseContract aContract(String number, LocalDate closes) {
        return PurchaseContract.builder()
                .number(number)
                .kind(ContractKind.FORWARD)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d("20000"))
                .window(new DeliveryWindow(LocalDate.of(2024, 9, 1), closes))
                .flatPrice(d("4.6200"))
                .build();
    }

    static FulfilmentLine aLine() {
        return aLine("0", "0");
    }

    static FulfilmentLine aLine(String applied) {
        return aLine(applied, "0");
    }

    static FulfilmentLine aLine(String applied, String weekly) {
        return lineFor(aContract(), d(applied), AS_OF, d(weekly));
    }

    static FulfilmentLine aLine(String number, String applied, LocalDate closes) {
        return lineFor(aContract(number, closes), d(applied), AS_OF, d("0"));
    }

    @Test
    void aNegativeAppliedIsRefused() {
        assertThatThrownBy(() -> lineFor(aContract(), d("-1"), AS_OF)).isInstanceOf(FulfilmentError.class);
    }

    @Test
    void theOutstandingIsMeasuredToTheToleranceFloor() {
        assertThat(aLine("10000").outstanding()).isEqualTo(d("9600.0000"));
    }

    @Test
    void aContractInsideToleranceIsFilled() {
        FulfilmentLine line = aLine("19600");
        assertThat(line.outstanding()).isEqualTo(d("0.0000"));
        assertThat(line.state()).isSameAs(FulfilmentState.FILLED);
        assertThat(line.needsACall()).isFalse();
    }

    @Test
    void anOverfilledContractIsStillFilled() {
        assertThat(aLine("21000").state()).isSameAs(FulfilmentState.FILLED);
    }

    @Test
    void theWeeksLeftComeOffTheCalendar() {
        FulfilmentLine line = aLine();
        assertThat(line.weeksLeft()).isGreaterThan(d("5")).isLessThan(d("7"));
    }

    @Test
    void aClosedWindowHasNoWeeksLeft() {
        assertThat(aLine("F-2024-118", "0", ALREADY_CLOSED).weeksLeft()).isEqualByComparingTo(d("0"));
    }

    @Test
    void aProducerHaulingFastEnoughIsOnTrack() {
        FulfilmentLine line = aLine("10000", "2000");
        assertThat(line.deliverable()).isGreaterThan(line.outstanding());
        assertThat(line.state()).isSameAs(FulfilmentState.ON_TRACK);
        assertThat(line.needsACall()).isFalse();
    }

    @Test
    void aProducerHaulingTooSlowlyIsAtRisk() {
        FulfilmentLine line = aLine("10000", "200");
        assertThat(line.state()).isSameAs(FulfilmentState.AT_RISK);
        assertThat(line.needsACall()).isTrue();
    }

    @Test
    void aProducerWhoHasNotStartedIsAtRisk() {
        assertThat(aLine("0").state()).isSameAs(FulfilmentState.AT_RISK);
    }

    @Test
    void aContractPastItsWindowWithBushelsOutIsShort() {
        FulfilmentLine line = aLine("F-2024-118", "10000", ALREADY_CLOSED);
        assertThat(line.state()).isSameAs(FulfilmentState.SHORT);
        assertThat(line.needsACall()).isTrue();
    }

    @Test
    void aContractPastItsWindowAndFilledIsNotShort() {
        assertThat(aLine("F-2024-118", "19600", ALREADY_CLOSED).state()).isSameAs(FulfilmentState.FILLED);
    }

    @Test
    void theSheetListsEveryContract() {
        List<FulfilmentLine> lines = List.of(aLine("19600"), aLine("F-2024-119", "0", WINDOW_CLOSES));
        assertThat(render(lines, AS_OF)).contains("F-2024-118", "F-2024-119", "2 contracts");
    }

    @Test
    void theSheetFitsItsWidth() {
        String text = render(List.of(aLine()), AS_OF);
        assertThat(Arrays.asList(text.split("\n", -1)))
                .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(90));
    }

    @Test
    void theSheetCountsTheCallsToMake() {
        List<FulfilmentLine> lines = List.of(aLine("19600"), aLine("F-2024-119", "0", WINDOW_CLOSES));
        assertThat(render(lines, AS_OF)).contains("1 need a call", "RING TODAY: F-2024-119");
    }

    @Test
    void aBookThatIsAllFilledRingsNobody() {
        String text = render(List.of(aLine("19600")), AS_OF);
        assertThat(text).contains("0 need a call");
        assertThat(text).doesNotContain("RING TODAY");
    }

    @Test
    void theCsvHasARowPerContract() {
        String text = toCsv(List.of(aLine(), aLine("F-2024-119", "0", WINDOW_CLOSES)));
        List<String> rows = List.of(text.strip().split("\n", -1));
        assertThat(rows.get(0)).startsWith("number,party_code");
        assertThat(rows).hasSize(3);
    }

    @Test
    void theCsvCarriesTheState() {
        assertThat(toCsv(List.of(aLine()))).contains("at_risk");
    }

    @Test
    void theAtRiskListIsTheOnesToRing() {
        List<FulfilmentLine> lines = List.of(aLine("19600"), aLine("F-2024-119", "0", WINDOW_CLOSES));
        assertThat(atRisk(lines)).extracting(FulfilmentLine::number).containsExactly("F-2024-119");
    }

    @Test
    void theShortListIsTheDefaults() {
        List<FulfilmentLine> lines = List.of(aLine(), aLine("F-2024-119", "0", ALREADY_CLOSED));
        assertThat(shortList(lines)).extracting(FulfilmentLine::number).containsExactly("F-2024-119");
    }

    @Test
    void theOutstandingTotalAddsTheBookUp() {
        List<FulfilmentLine> lines = List.of(aLine("10000"), aLine("F-2024-119", "10000", WINDOW_CLOSES));
        assertThat(outstandingTotal(lines)).isEqualTo(d("19200.0000"));
    }

    @Test
    void aFilledBookHasNothingOutstanding() {
        assertThat(outstandingTotal(List.of(aLine("19600")))).isEqualTo(d("0.0000"));
    }
}
