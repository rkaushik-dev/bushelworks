package io.github.rkaushikdev.bushelworks.reports.remittance;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.reports.remittance.Remittance.group;
import static io.github.rkaushikdev.bushelworks.reports.remittance.Remittance.quarterEnd;
import static io.github.rkaushikdev.bushelworks.reports.remittance.Remittance.quarterOf;
import static io.github.rkaushikdev.bushelworks.reports.remittance.Remittance.quarterStart;
import static io.github.rkaushikdev.bushelworks.reports.remittance.Remittance.render;
import static io.github.rkaushikdev.bushelworks.reports.remittance.Remittance.toCsv;
import static io.github.rkaushikdev.bushelworks.reports.remittance.Remittance.totals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.assessments.WithheldLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemittanceTest {

    static final WithheldLine KSCORN =
            new WithheldLine("KSCORN", "Kansas Corn Commission", "Kansas Department of Agriculture", d("89.76"), true);

    static final WithheldLine KSWHEAT = new WithheldLine(
            "KSWHEAT", "Kansas Wheat Commission", "Kansas Department of Agriculture", d("40.00"), false);

    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);

    /** The corn levy on statement S-2024-0417, the collection every other one is a variation of. */
    static Collected collected() {
        return collected("S-2024-0417", "MERRICK", "CORN", SETTLED, KSCORN);
    }

    static Collected collected(String number) {
        return collected(number, "MERRICK", "CORN", SETTLED, KSCORN);
    }

    static Collected collected(String number, String party, String commodity, LocalDate settledOn, WithheldLine line) {
        return new Collected(number, settledOn, party, commodity, d("8976.0000"), line);
    }

    static Collected wheat(String number) {
        return collected(number, "MERRICK", "HRW", SETTLED, KSWHEAT);
    }

    static CheckoffReturn aReturn() {
        return new CheckoffReturn(KSCORN.authority(), "KSCORN", "CORN", 2024, 4);
    }

    static CheckoffReturn aWheatReturn() {
        return new CheckoffReturn(KSWHEAT.authority(), "KSWHEAT", "HRW", 2024, 4);
    }

    @Test
    void quartersAreWorkedOutFromTheMonth() {
        assertThat(quarterOf(LocalDate.of(2024, 1, 5))).isEqualTo(1);
        assertThat(quarterOf(LocalDate.of(2024, 6, 30))).isEqualTo(2);
        assertThat(quarterOf(LocalDate.of(2024, 10, 20))).isEqualTo(4);
    }

    @Test
    void quarterBoundaries() {
        assertThat(quarterStart(2024, 4)).isEqualTo(LocalDate.of(2024, 10, 1));
        assertThat(quarterEnd(2024, 4)).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(quarterEnd(2024, 1)).isEqualTo(LocalDate.of(2024, 3, 31));
    }

    @Test
    void aBadQuarterIsRefused() {
        assertThatThrownBy(() -> quarterEnd(2024, 5)).isInstanceOf(RemittanceError.class);
        assertThatThrownBy(() -> quarterStart(2024, 0)).isInstanceOf(RemittanceError.class);
    }

    @Test
    void aNewReturnIsEmpty() {
        CheckoffReturn one = aReturn();
        assertThat(one.isEmpty()).isTrue();
        assertThat(one.withheld()).isEqualTo(d("0.00"));
        assertThat(one.period()).isEqualTo("2024 Q4");
    }

    @Test
    void anEntryLandsOnTheReturn() {
        CheckoffReturn one = aReturn();
        one.add(collected());
        assertThat(one.isEmpty()).isFalse();
        assertThat(one.bushels()).isEqualTo(d("8976.0000"));
        assertThat(one.withheld()).isEqualTo(d("89.76"));
    }

    @Test
    void aLevyFromAnotherCommissionIsRefused() {
        CheckoffReturn one = aReturn();
        Collected wrongLevy = collected("S-2024-0417", "MERRICK", "CORN", SETTLED, KSWHEAT);
        assertThatThrownBy(() -> one.add(wrongLevy)).isInstanceOf(RemittanceError.class);
    }

    @Test
    void aDifferentCommodityIsRefused() {
        CheckoffReturn one = aReturn();
        Collected wrongCrop = collected("S-2024-0417", "MERRICK", "HRW", SETTLED, KSCORN);
        assertThatThrownBy(() -> one.add(wrongCrop)).isInstanceOf(RemittanceError.class);
    }

    @Test
    void aSettlementFromAnotherQuarterIsRefused() {
        CheckoffReturn one = aReturn();
        Collected august = collected("S-2024-0417", "MERRICK", "CORN", LocalDate.of(2024, 8, 20), KSCORN);
        assertThatThrownBy(() -> one.add(august)).isInstanceOf(RemittanceError.class);
    }

    @Test
    void theReturnCoversItsOwnQuarter() {
        CheckoffReturn one = aReturn();
        assertThat(one.covers(LocalDate.of(2024, 10, 1))).isTrue();
        assertThat(one.covers(LocalDate.of(2024, 12, 31))).isTrue();
        assertThat(one.covers(LocalDate.of(2025, 1, 1))).isFalse();
    }

    @Test
    void producersAreListedOnceEach() {
        CheckoffReturn one = aReturn();
        one.add(collected());
        one.add(collected("S-2024-0418"));
        one.add(collected("S-2024-0419", "HOLLIS", "CORN", SETTLED, KSCORN));
        assertThat(one.producers()).containsExactly("HOLLIS", "MERRICK");
    }

    @Test
    void theRefundablePortionIsSeparate() {
        CheckoffReturn one = aReturn();
        one.add(collected());
        assertThat(one.refundable()).isEqualTo(d("89.76"));
    }

    @Test
    void aNonRefundableLevyHasNoRefundablePortion() {
        CheckoffReturn one = aWheatReturn();
        one.add(wheat("S-2024-0417"));
        assertThat(one.refundable()).isEqualTo(d("0.00"));
        assertThat(one.withheld()).isEqualTo(d("40.00"));
    }

    @Test
    void groupingSplitsByCommissionAndCommodity() {
        List<CheckoffReturn> returns = group(List.of(collected(), wheat("S-2")), 2024, 4);
        assertThat(returns).extracting(CheckoffReturn::code).containsExactly("KSCORN", "KSWHEAT");
    }

    @Test
    void groupingPutsTwoSettlementsOnOneReturn() {
        List<CheckoffReturn> returns = group(List.of(collected(), collected("S-2")), 2024, 4);
        assertThat(returns).hasSize(1);
        assertThat(returns.get(0).entries()).hasSize(2);
    }

    @Test
    void thePrintedReturnNamesTheCommissionAndThePeriod() {
        CheckoffReturn one = aReturn();
        one.add(collected());
        assertThat(render(one))
                .contains(
                        "CHECKOFF RETURN 2024 Q4",
                        "Kansas Department of Agriculture",
                        "S-2024-0417",
                        "1 settlements, 1 producers");
    }

    @Test
    void aRefundableReturnSaysSoAtTheBottom() {
        CheckoffReturn one = aReturn();
        one.add(collected());
        assertThat(render(one)).contains("of which refundable");
    }

    @Test
    void aNonRefundableReturnDoesNot() {
        CheckoffReturn one = aWheatReturn();
        one.add(wheat("S-2024-0417"));
        assertThat(render(one)).doesNotContain("of which refundable");
    }

    @Test
    void theCsvHasARowPerSettlement() {
        String text = toCsv(group(List.of(collected(), collected("S-2")), 2024, 4));
        List<String> rows = List.of(text.strip().split("\n", -1));
        assertThat(rows.get(0)).startsWith("authority,levy,commodity,period");
        assertThat(rows).hasSize(3);
    }

    @Test
    void theTotalsSayWhatIsOwedToEachCommission() {
        List<Collected> entries = List.of(collected(), collected("S-2"), wheat("S-3"));
        Map<String, BigDecimal> owed = totals(group(entries, 2024, 4));
        assertThat(owed).isEqualTo(Map.of("KSCORN", d("179.52"), "KSWHEAT", d("40.00")));
    }
}
