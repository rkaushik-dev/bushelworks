package io.github.rkaushikdev.bushelworks.reports.patronage;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.reports.patronage.PatronageBoard.combine;
import static io.github.rkaushikdev.bushelworks.reports.patronage.PatronageBoard.render;
import static io.github.rkaushikdev.bushelworks.reports.patronage.PatronageBoard.toCsv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.github.rkaushikdev.bushelworks.TestDecimals;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PatronageTest {

    static final LocalDate OPENS = LocalDate.of(2024, 9, 1);
    static final LocalDate CLOSES = LocalDate.of(2025, 8, 31);

    static PatronageLine.Builder aLine() {
        return PatronageLine.builder()
                .partyCode("MERRICK")
                .name("Merrick Farms")
                .bushels(d("124000.00"))
                .gross(d("508400.00"))
                .charges(d("9920.00"))
                .loads(88);
    }

    static PatronageYear aYear(PatronageLine.Builder... lines) {
        return aYear("HOME", lines);
    }

    static PatronageYear aYear(String locationCode, PatronageLine.Builder... lines) {
        List<PatronageLine> built = lines.length == 0
                ? List.of(aLine().build())
                : List.of(lines).stream().map(PatronageLine.Builder::build).toList();
        return new PatronageYear(locationCode, OPENS, CLOSES, built);
    }

    static List<String> codes(List<PatronageLine> lines) {
        return lines.stream().map(PatronageLine::partyCode).toList();
    }

    @Test
    void negativeBushelsAreRefused() {
        assertThatThrownBy(() -> aLine().bushels(d("-1")).build()).isInstanceOf(PatronageError.class);
    }

    @Test
    void negativeLoadsAreRefused() {
        assertThatThrownBy(() -> aLine().loads(-1).build()).isInstanceOf(PatronageError.class);
    }

    @Test
    void aYearCannotCloseBeforeItOpens() {
        assertThatThrownBy(() -> new PatronageYear("HOME", CLOSES, OPENS))
                .isInstanceOf(PatronageError.class)
                .hasMessage("HOME: the year closes before it opens");
    }

    @Test
    void aProducerCannotAppearTwice() {
        assertThatThrownBy(() -> aYear(aLine(), aLine()))
                .isInstanceOf(PatronageError.class)
                .hasMessage("HOME: a producer appears twice");
    }

    @Test
    void theMarginIsWhatTheHouseKept() {
        assertThat(aLine().build().margin()).isEqualTo(d("9920.00"));
    }

    @Test
    void marginPerBushelCarriesFourPlaces() {
        assertThat(aLine().build().marginPerBushel()).isEqualTo(d("0.0800"));
    }

    @Test
    void aProducerWithNoBushelsHasNoMarginPerBushel() {
        assertThat(aLine().bushels(d("0")).build().marginPerBushel()).isEqualTo(d("0.0000"));
    }

    @Test
    void theAverageLoadIsBushelsOverLoads() {
        assertThat(aLine().bushels(d("8800")).loads(8).build().averageLoad()).isEqualTo(d("1100.0000"));
    }

    @Test
    void aProducerWithNoLoadsHasNoAverage() {
        assertThat(aLine().loads(0).build().averageLoad()).isEqualTo(d("0.00"));
    }

    @Test
    void aYearTotalsItsLines() {
        PatronageYear year = aYear(aLine(), aLine().partyCode("HOLLIS").bushels(d("40000")));
        assertThat(year.bushels()).isEqualTo(d("164000.0000"));
        assertThat(year.gross()).isEqualTo(d("1016800.00"));
        assertThat(year.margin()).isEqualTo(d("19840.00"));
    }

    @Test
    void theBushelRankingPutsTheBiggestFirst() {
        PatronageYear year = aYear(aLine().partyCode("HOLLIS").bushels(d("40000")), aLine());
        assertThat(codes(year.byBushels())).containsExactly("MERRICK", "HOLLIS");
    }

    @Test
    void aTieOnBushelsBreaksOnTheCode() {
        PatronageYear year = aYear(aLine().partyCode("ZANDER"), aLine().partyCode("ABBOTT"));
        assertThat(codes(year.byBushels())).containsExactly("ABBOTT", "ZANDER");
    }

    @Test
    void theMarginRankingCanDifferFromTheBushelRanking() {
        PatronageYear year =
                aYear(aLine(), aLine().partyCode("HOLLIS").bushels(d("60000")).charges(d("14400.00")));
        assertThat(year.byBushels().get(0).partyCode()).isEqualTo("MERRICK");
        assertThat(year.byMargin().get(0).partyCode()).isEqualTo("HOLLIS");
    }

    @Test
    void nonMembersAreLeftOffTheMemberList() {
        PatronageYear year = aYear(aLine(), aLine().partyCode("HOLLIS").isMember(false));
        assertThat(codes(year.members())).containsExactly("MERRICK");
    }

    @Test
    void aShareIsAPercentageOfTheYear() {
        PatronageYear year =
                aYear(aLine().bushels(d("75000")), aLine().partyCode("HOLLIS").bushels(d("25000")));
        assertThat(year.shareOfBushels("HOLLIS")).isEqualTo(d("25.0000"));
    }

    @Test
    void aShareOfAnEmptyYearIsNothing() {
        PatronageYear year = aYear(aLine().bushels(d("0")));
        assertThat(year.shareOfBushels("MERRICK")).isEqualTo(d("0.0000"));
    }

    @Test
    void askingForSomebodyWhoDidNotDeliverIsRefused() {
        assertThatThrownBy(() -> aYear().shareOfBushels("NOBODY"))
                .isInstanceOf(PatronageError.class)
                .hasMessage("NOBODY did not deliver in this year");
    }

    @Test
    void concentrationCountsTheBiggestHandful() {
        PatronageLine.Builder[] lines = IntStream.range(0, 20)
                .mapToObj(index ->
                        aLine().partyCode(String.format("P%02d", index)).bushels(d("10000")))
                .toArray(PatronageLine.Builder[]::new);
        assertThat(aYear(lines).concentration(10)).isEqualTo(d("50.0000"));
    }

    @Test
    void concentrationOfAnEmptyYearIsNothing() {
        assertThat(aYear(aLine().bushels(d("0"))).concentration()).isEqualTo(d("0.0000"));
    }

    @Test
    void aPoolSplitsOnMemberBushels() {
        PatronageYear year =
                aYear(aLine().bushels(d("75000")), aLine().partyCode("HOLLIS").bushels(d("25000")));
        Map<String, BigDecimal> split = year.allocations(d("40000.00"));
        assertThat(split).containsExactly(entry("MERRICK", d("30000.00")), entry("HOLLIS", d("10000.00")));
    }

    @Test
    void aPoolIgnoresNonMembers() {
        PatronageYear year = aYear(
                aLine().bushels(d("50000")),
                aLine().partyCode("HOLLIS").bushels(d("50000")).isMember(false));
        assertThat(year.allocations(d("1000.00"))).containsExactly(entry("MERRICK", d("1000.00")));
    }

    @Test
    void thePoolAlwaysAddsBackToItself() {
        PatronageYear year = aYear(
                aLine().bushels(d("33333")),
                aLine().partyCode("HOLLIS").bushels(d("33333")),
                aLine().partyCode("ABBOTT").bushels(d("33334")));
        Map<String, BigDecimal> split = year.allocations(d("1000.00"));
        assertThat(TestDecimals.sum(List.copyOf(split.values()))).isEqualTo(d("1000.00"));
    }

    @Test
    void theRoundingRemainderLandsOnTheBiggestMember() {
        PatronageYear year = aYear(
                aLine().bushels(d("33333")),
                aLine().partyCode("HOLLIS").bushels(d("33333")),
                aLine().partyCode("ABBOTT").bushels(d("33334")));
        Map<String, BigDecimal> split = year.allocations(d("1000.00"));
        assertThat(split.get("ABBOTT")).isGreaterThan(split.get("HOLLIS"));
    }

    @Test
    void aPoolWithNoMembersSplitsNowhere() {
        PatronageYear year = aYear(aLine().isMember(false));
        assertThat(year.allocations(d("1000.00"))).isEmpty();
    }

    @Test
    void aCentOverThePoolComesBackOffTheBiggestMember() {
        PatronageYear year = aYear(
                aLine().bushels(d("50000")),
                aLine().partyCode("HOLLIS").bushels(d("50000")),
                aLine().partyCode("ABBOTT").bushels(d("10")).isMember(false));
        // Each half rounds up to 500.01, a cent over the pool. The two tie on bushels, MERRICK wins
        // the tie on its code, and the cent comes back off MERRICK.
        assertThat(year.allocations(d("1000.01")))
                .containsExactly(entry("MERRICK", d("500.00")), entry("HOLLIS", d("500.01")));
    }

    @Test
    void aPoolCanBeAllocatedOnTheMarginEachMemberLeft() {
        PatronageYear year = aYear(
                aLine().bushels(d("300000")).charges(d("1000.00")),
                aLine().partyCode("HOLLIS").bushels(d("60000")).charges(d("3000.00")));
        assertThat(year.allocations(d("4000.00"), AllocationBasis.MARGIN))
                .containsExactly(entry("MERRICK", d("1000.00")), entry("HOLLIS", d("3000.00")));
        assertThat(year.allocations(d("4000.00"), AllocationBasis.BUSHELS))
                .containsExactly(entry("MERRICK", d("3333.33")), entry("HOLLIS", d("666.67")));
        assertThat(AllocationBasis.of("margin")).isSameAs(AllocationBasis.MARGIN);
    }

    @Test
    void aMemberWhoseMarginWentNegativeWeighsNothing() {
        PatronageYear year =
                aYear(aLine().charges(d("-200.00")), aLine().partyCode("HOLLIS").charges(d("500.00")));
        assertThat(year.allocations(d("100.00"), AllocationBasis.MARGIN))
                .containsExactly(entry("MERRICK", d("0.00")), entry("HOLLIS", d("100.00")));
    }

    @Test
    void aQualifiedRefundPaysAtLeastAFifthInCash() {
        PatronageYear year =
                aYear(aLine().bushels(d("75000")), aLine().partyCode("HOLLIS").bushels(d("25000")));
        List<PatronageRefund> refunds =
                year.refunds(d("40000.00"), AllocationBasis.BUSHELS, NoticeKind.QUALIFIED, d("20"));
        assertThat(refunds).extracting(PatronageRefund::cash).containsExactly(d("6000.00"), d("2000.00"));
        assertThat(refunds).extracting(PatronageRefund::retained).containsExactly(d("24000.00"), d("8000.00"));
        assertThatThrownBy(() -> year.refunds(d("40000.00"), AllocationBasis.BUSHELS, NoticeKind.QUALIFIED, d("15")))
                .isInstanceOf(PatronageError.class)
                .hasMessageContaining("at least 20%");
    }

    @Test
    void aNonqualifiedNoticeMayRetainEverything() {
        PatronageRefund refund = PatronageRefund.of("MERRICK", NoticeKind.NONQUALIFIED, d("1234.56"), d("0"));
        assertThat(refund.cash()).isEqualTo(d("0.00"));
        assertThat(refund.retained()).isEqualTo(d("1234.56"));
        assertThat(NoticeKind.of("nonqualified")).isSameAs(NoticeKind.NONQUALIFIED);
    }

    @Test
    void anOddCentOfCashGoesOnTheCheque() {
        PatronageRefund refund = PatronageRefund.of("MERRICK", NoticeKind.QUALIFIED, d("100.01"), d("20"));
        assertThat(refund.cash()).isEqualTo(d("20.01"));
        assertThat(refund.retained()).isEqualTo(d("80.00"));
        assertThatThrownBy(() -> PatronageRefund.of("MERRICK", NoticeKind.QUALIFIED, d("100"), d("101")))
                .isInstanceOf(PatronageError.class);
        assertThatThrownBy(
                        () -> new PatronageRefund("MERRICK", NoticeKind.QUALIFIED, d("100.00"), d("20.00"), d("79.00")))
                .isInstanceOf(PatronageError.class);
    }

    @Test
    void thePageFitsEightyColumns() {
        PatronageLine.Builder[] lines = IntStream.range(0, 12)
                .mapToObj(index -> aLine().partyCode(String.format("PRODUCER%02d", index))
                        .name("A Very Long Farm Name Indeed LLC")
                        .bushels(d("999999.99"))
                        .charges(d("199999.99")))
                .toArray(PatronageLine.Builder[]::new);
        assertThat(render(aYear(lines)).split("\n", -1))
                .allSatisfy(row -> assertThat(row.length()).isLessThanOrEqualTo(80));
    }

    @Test
    void thePageNamesTheHouseAndTheYear() {
        String page = render(aYear());
        assertThat(page).contains("HOME").contains("2024-09-01 to 2025-08-31");
    }

    @Test
    void thePageCanBeRankedOnMargin() {
        assertThat(render(aYear(), "margin")).contains("PATRONAGE BY MARGIN");
    }

    @Test
    void thePageRefusesAnOrderItDoesNotKnow() {
        assertThatThrownBy(() -> render(aYear(), "alphabetical"))
                .isInstanceOf(PatronageError.class)
                .hasMessage("cannot rank on alphabetical");
    }

    @Test
    void smallProducersCollapseIntoOneLine() {
        PatronageYear year =
                aYear(aLine(), aLine().partyCode("TINY").bushels(d("400")).charges(d("30.00")));
        String page = render(year);
        assertThat(page).contains("1 others").doesNotContain("TINY");
    }

    @Test
    void theLimitPushesTheRestIntoOthers() {
        PatronageLine.Builder[] lines = IntStream.range(0, 6)
                .mapToObj(index -> aLine().partyCode(String.format("P%02d", index))
                        .bushels(d("10000").add(BigDecimal.valueOf(index))))
                .toArray(PatronageLine.Builder[]::new);
        assertThat(render(aYear(lines), PatronageBoard.BY_BUSHELS, 3)).contains("3 others");
    }

    @Test
    void aNonMemberIsStarred() {
        assertThat(render(aYear(aLine().isMember(false)))).contains("MERRICK   *");
    }

    /** The whole page, laid out as the board pack prints it. */
    static PatronageYear aBoardYear() {
        return aYear(
                aLine(),
                aLine().partyCode("0041")
                        .name("Hollis and Daughters Grain Company")
                        .bushels(d("60000"))
                        .charges(d("14400.00"))
                        .isMember(false)
                        .loads(40),
                aLine().partyCode("TINY")
                        .name("Tiny, Small \"Plot\"")
                        .bushels(d("400"))
                        .charges(d("30.00"))
                        .loads(1),
                aLine().partyCode("ZANDER")
                        .name("Zander")
                        .bushels(d("60000"))
                        .charges(d("-120.55"))
                        .loads(3));
    }

    @Test
    void thePageIsLaidOutInItsColumns() {
        assertThat(render(aBoardYear()))
                .isEqualTo(String.join(
                        "\n",
                        "-".repeat(80),
                        "                              PATRONAGE BY BUSHELS",
                        "                         HOME  2024-09-01 to 2025-08-31",
                        "-".repeat(80),
                        "RNK PRODUCER     NAME                        BUSHELS         MARGIN   PER BU",
                        "-".repeat(80),
                        "  1 MERRICK     Merrick Farms            124,000.00      9,920.00   0.0800",
                        "  2 0041      * Hollis and Daughters G    60,000.00     14,400.00   0.2400",
                        "  3 ZANDER      Zander                    60,000.00      (120.55)  -0.0020",
                        "    1 others                                 400.00         30.00",
                        "-".repeat(80),
                        "    TOTAL                                244,400.00     24,229.45",
                        "    top ten hold 100.00 percent of bushels",
                        "    * not a member"));
    }

    @Test
    void aNegativeLimitLeavesThatManyOffTheEnd() {
        assertThat(render(aBoardYear(), "margin", 2))
                .contains("    2 others                              60,400.00       (90.55)");
        String page = render(aBoardYear(), null, -1);
        assertThat(page).contains("PATRONAGE BY BUSHELS").contains("  2 0041").doesNotContain("ZANDER");
        assertThat(aBoardYear().concentration(-1)).isEqualTo(d("99.8363"));
    }

    @Test
    void theCsvHasARowForEachProducer() {
        PatronageYear year = aYear(aLine(), aLine().partyCode("HOLLIS").bushels(d("40000")));
        String[] rows = toCsv(year).strip().split("\n");
        assertThat(rows).hasSize(3);
        assertThat(rows[0]).startsWith("party_code,");
    }

    @Test
    void theCsvCarriesTheShare() {
        PatronageYear year =
                aYear(aLine().bushels(d("75000")), aLine().partyCode("HOLLIS").bushels(d("25000")));
        assertThat(toCsv(year)).contains("25.0000");
    }

    @Test
    void theCsvKeepsALeadingZeroAndQuotesWhatNeedsIt() {
        assertThat(toCsv(aBoardYear()))
                .isEqualTo("party_code,name,member,loads,bushels,gross,margin,margin_per_bushel,share_percent\n"
                        + "MERRICK,Merrick Farms,yes,88,124000.00,508400.00,9920.00,0.0800,50.7365\n"
                        + "\"=\"\"0041\"\"\",Hollis and Daughters Grain Company,no,40,60000,508400.00,14400.00,0.2400,24.5499\n"
                        + "ZANDER,Zander,yes,3,60000,508400.00,-120.55,-0.0020,24.5499\n"
                        + "TINY,\"Tiny, Small \"\"Plot\"\"\",yes,1,400,508400.00,30.00,0.0750,0.1637\n");
    }

    @Test
    void combiningTwoHousesAddsAProducerUp() {
        PatronageYear first = aYear("HOME", aLine());
        PatronageYear second =
                aYear("LURAY", aLine().bushels(d("6000")).charges(d("500.00")).loads(4));
        PatronageYear rolled = combine(List.of(first, second));
        assertThat(rolled.locationCode()).isEqualTo("ALL");
        assertThat(rolled.bushels()).isEqualTo(d("130000.0000"));
        assertThat(rolled.lines().get(0).loads()).isEqualTo(92);
    }

    @Test
    void combiningKeepsAProducerWhoIsOnlyAtOneHouse() {
        PatronageYear first = aYear(aLine());
        PatronageYear second = aYear("LURAY", aLine().partyCode("HOLLIS"));
        assertThat(combine(List.of(first, second)).lines()).hasSize(2);
    }

    @Test
    void aProducerWhoIsAMemberAtOneHouseOnlyIsNotAMember() {
        PatronageYear first = aYear(aLine());
        PatronageYear second = aYear("LURAY", aLine().isMember(false));
        assertThat(combine(List.of(first, second)).members()).isEmpty();
    }

    @Test
    void theCombinedYearSpansBoth() {
        PatronageYear first = new PatronageYear("HOME", OPENS, LocalDate.of(2025, 3, 1));
        PatronageYear second = new PatronageYear("LURAY", LocalDate.of(2024, 7, 1), CLOSES);
        PatronageYear rolled = combine(List.of(first, second), "BOTH");
        assertThat(rolled.opens()).isEqualTo(LocalDate.of(2024, 7, 1));
        assertThat(rolled.closes()).isEqualTo(CLOSES);
        assertThat(rolled.locationCode()).isEqualTo("BOTH");
    }

    @Test
    void combiningNothingIsRefused() {
        assertThatThrownBy(() -> combine(List.of()))
                .isInstanceOf(PatronageError.class)
                .hasMessage("nothing to combine");
    }
}
