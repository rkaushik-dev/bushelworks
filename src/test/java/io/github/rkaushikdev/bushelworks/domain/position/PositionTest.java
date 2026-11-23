package io.github.rkaushikdev.bushelworks.domain.position;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.grainbank.FeedAccount;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PositionTest {

    static final LocalDate AS_OF = LocalDate.of(2024, 11, 1);

    static PositionLine.Builder line() {
        return PositionLine.builder()
                .asOf(AS_OF)
                .commodityCode("CORN")
                .locationCode("HOME")
                .onHand(d("1000000"))
                .storageObligation(d("400000"))
                .deferredPrice(d("150000"))
                .purchasedForward(d("250000"))
                .soldForward(d("600000"));
    }

    @Test
    void grainBankBushelsAreTheCattlemensNotTheHouses() {
        PositionLine banked = line().grainBank(d("30000")).build();
        assertThat(banked.owned()).isEqualTo(d("420000.0000"));
        assertThat(banked.netLong()).isEqualTo(d("220000.0000"));
        assertThat(banked.obligation()).isEqualTo(d("1180000.0000"));
        assertThatThrownBy(() -> line().grainBank(d("-1")).build())
                .isInstanceOf(PositionError.class)
                .hasMessageContaining("grain_bank");
    }

    @Test
    void twoSitesRollTheirGrainBanksUpTogether() {
        PositionLine home = line().grainBank(d("30000")).build();
        PositionLine luray = line().locationCode("LURAY").grainBank(d("5000")).build();
        assertThat(home.combine(luray).grainBank()).isEqualByComparingTo(d("35000"));
        assertThat(home.toBuilder().build()).isEqualTo(home);
    }

    @Test
    void theFeedAccountsOnFileFillTheGrainBankAsTheyStoodThatDay() {
        FeedAccount stroh = FeedAccount.open("STROH", "CORN", "HOME", LocalDate.of(2024, 10, 3))
                .deposit("A-1", d("12000"), LocalDate.of(2024, 10, 3))
                .deposit("A-2", d("3000"), LocalDate.of(2024, 11, 5));
        FeedAccount elsewhere = FeedAccount.open("HAAS", "CORN", "LURAY", LocalDate.of(2024, 10, 3))
                .deposit("L-1", d("8000"), LocalDate.of(2024, 10, 3));
        FeedAccount converted = FeedAccount.open("VOTH", "CORN", "HOME", LocalDate.of(2024, 9, 20))
                .deposit("A-0", d("6000"), LocalDate.of(2024, 9, 20))
                .convertToStorage(LocalDate.of(2024, 10, 15));
        FeedAccount milo = FeedAccount.open("STROH", "MILO", "HOME", LocalDate.of(2024, 10, 3))
                .deposit("A-3", d("900"), LocalDate.of(2024, 10, 3));
        List<FeedAccount> accounts = List.of(stroh, elsewhere, converted, milo);
        assertThat(Positions.withGrainBank(line().build(), accounts).grainBank())
                .isEqualTo(d("12000.0000"));
        assertThat(Positions.withGrainBank(line().locationCode("*").build(), accounts)
                        .grainBank())
                .isEqualTo(d("20000.0000"));
        PositionLine october = line().asOf(LocalDate.of(2024, 10, 10)).build();
        assertThat(Positions.withGrainBank(october, accounts).grainBank()).isEqualTo(d("18000.0000"));
    }

    @Test
    void ownedIsOnHandLessWhatBelongsToProducers() {
        assertThat(line().build().owned()).isEqualTo(d("450000.0000"));
    }

    @Test
    void netLongAddsDeferredPriceBackIn() {
        // 450000 owned + 150000 dp + 250000 bought - 600000 sold
        assertThat(line().build().netLong()).isEqualTo(d("250000.0000"));
    }

    @Test
    void aHouseCanBeShort() {
        PositionLine shortLine = line().soldForward(d("900000")).build();
        assertThat(shortLine.netLong()).isEqualTo(d("-50000.0000"));
        assertThat(shortLine.isShort()).isTrue();
    }

    @Test
    void inTransitCountsTowardTheLongSide() {
        assertThat(line().inTransit(d("40000")).build().netLong()).isEqualTo(d("290000.0000"));
    }

    @Test
    void obligationIsEverythingOwedToSomebody() {
        assertThat(line().build().obligation()).isEqualTo(d("1150000.0000"));
    }

    @Test
    void aNegativeComponentIsRefused() {
        assertThatThrownBy(() -> line().onHand(d("-1")).build()).isInstanceOf(PositionError.class);
    }

    @Test
    void combiningTwoLocationsAddsTheComponents() {
        PositionLine rolled = line().build()
                .combine(line().locationCode("LURAY").onHand(d("500000")).build());
        assertThat(rolled.locationCode()).isEqualTo("*");
        assertThat(rolled.onHand()).isEqualByComparingTo(d("1500000"));
    }

    @Test
    void combiningTheSameLocationKeepsTheCode() {
        assertThat(line().build().combine(line().build()).locationCode()).isEqualTo("HOME");
    }

    @Test
    void combiningDifferentCommoditiesIsRefused() {
        assertThatThrownBy(() ->
                        line().build().combine(line().commodityCode("SOYB").build()))
                .isInstanceOf(PositionError.class);
    }

    @Test
    void combiningDifferentDaysIsRefused() {
        assertThatThrownBy(() -> line().build()
                        .combine(line().asOf(LocalDate.of(2024, 11, 2)).build()))
                .isInstanceOf(PositionError.class);
    }

    @Test
    void aReportRollsUpByCommodity() {
        PositionReport report = new PositionReport(AS_OF);
        report.add(line().build());
        report.add(line().locationCode("LURAY").onHand(d("500000")).build());
        report.add(line().commodityCode("SOYB").onHand(d("200000")).build());
        assertThat(report.commodities()).containsExactly("CORN", "SOYB");
        assertThat(report.byCommodity("CORN").onHand()).isEqualByComparingTo(d("1500000"));
    }

    @Test
    void aReportRefusesALineFromAnotherDay() {
        PositionReport report = new PositionReport(AS_OF);
        assertThatThrownBy(
                        () -> report.add(line().asOf(LocalDate.of(2024, 11, 2)).build()))
                .isInstanceOf(PositionError.class);
    }

    @Test
    void askingForACommodityThatIsNotThere() {
        assertThatThrownBy(() -> new PositionReport(AS_OF).byCommodity("HRW")).isInstanceOf(PositionError.class);
    }

    @Test
    void theReportFindsTheShortLines() {
        PositionReport report = new PositionReport(AS_OF);
        report.add(line().build());
        report.add(line().commodityCode("SOYB").soldForward(d("2000000")).build());
        assertThat(report.shorts()).extracting(PositionLine::commodityCode).containsExactly("SOYB");
    }

    @Test
    void theReportTotalsWhatTheHouseOwns() {
        PositionReport report = new PositionReport(AS_OF);
        report.add(line().build());
        report.add(line().locationCode("LURAY").build());
        assertThat(report.totalOwned()).isEqualTo(d("900000.0000"));
    }

    @Test
    void theHedgeIsRoundedTowardZero() {
        assertThat(Positions.hedgeRequirement(line().build())).isEqualTo(50);
        assertThat(Positions.hedgeRequirement(
                        line().purchasedForward(d("252400")).build()))
                .isEqualTo(50);
    }

    @Test
    void aShortPositionHedgesTheOtherWay() {
        assertThat(Positions.hedgeRequirement(line().soldForward(d("900000")).build()))
                .isEqualTo(-10);
    }

    @Test
    void aZeroContractSizeIsRefused() {
        assertThatThrownBy(() -> Positions.hedgeRequirement(line().build(), d("0")))
                .isInstanceOf(PositionError.class);
    }

    @Test
    void aSmallBinVarianceIsWrittenOff() {
        assertThat(Positions.reconcile(line().build(), d("1002000"), d("0.5"))).isEqualTo(d("2000.0000"));
    }

    @Test
    void aWildBinVarianceIsRefused() {
        assertThatThrownBy(() -> Positions.reconcile(line().build(), d("1080000"), d("0.5")))
                .isInstanceOf(PositionError.class);
    }

    @Test
    void aNegativeToleranceIsRefused() {
        assertThatThrownBy(() -> Positions.reconcile(line().build(), d("1000000"), d("-1")))
                .isInstanceOf(PositionError.class);
    }

    @Test
    void rollingThePositionForwardKeepsTheNumbers() {
        List<PositionLine> rolled = Positions.rollForward(List.of(line().build()), LocalDate.of(2024, 11, 2));
        assertThat(rolled.get(0).asOf()).isEqualTo(LocalDate.of(2024, 11, 2));
        assertThat(rolled.get(0).netLong()).isEqualTo(line().build().netLong());
    }
}
