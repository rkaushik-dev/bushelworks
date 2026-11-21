package io.github.rkaushikdev.bushelworks.services.desk;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.hedging.Account;
import io.github.rkaushikdev.bushelworks.domain.hedging.HedgeError;
import io.github.rkaushikdev.bushelworks.domain.hedging.Lot;
import io.github.rkaushikdev.bushelworks.domain.hedging.Side;
import io.github.rkaushikdev.bushelworks.domain.position.PositionLine;
import io.github.rkaushikdev.bushelworks.domain.position.PositionReport;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The morning sheet: the position on one side, the broker's account on the other. */
class DeskTest {

    static final LocalDate AS_OF = LocalDate.of(2024, 12, 2);
    static final FuturesMonth CZ4 = new FuturesMonth("CORN", "Z", 2024);
    static final FuturesMonth CH5 = new FuturesMonth("CORN", "H", 2025);

    static Lot aLot(String reference, FuturesMonth month, Side side, int contracts, String price) {
        return new Lot(reference, month, side, contracts, d(price), LocalDate.of(2024, 11, 1));
    }

    /** One fill of December corn, sold at 4.3225. */
    static Account shortOf(int contracts) {
        Account account = new Account();
        account.add(aLot("F-1001", CZ4, Side.SHORT, contracts, "4.3225"));
        return account;
    }

    static PositionLine aLine() {
        return aLine("CORN", "120000");
    }

    static PositionLine aLine(String commodityCode, String onHand) {
        return line(commodityCode, onHand).build();
    }

    static PositionLine.Builder line(String commodityCode, String onHand) {
        return PositionLine.builder()
                .asOf(AS_OF)
                .commodityCode(commodityCode)
                .locationCode("HOME")
                .onHand(d(onHand));
    }

    static PositionReport aReport(PositionLine... lines) {
        PositionReport report = new PositionReport(AS_OF);
        for (PositionLine line : lines.length == 0 ? new PositionLine[] {aLine()} : lines) {
            report.add(line);
        }
        return report;
    }

    @Test
    void aFlatBookHasNoGap() {
        Gap gap = Desk.gapFor("CORN", d("50000"), shortOf(10));
        assertThat(gap.gapContracts()).isZero();
        assertThat(gap.isActionable()).isFalse();
    }

    @Test
    void anUnhedgedPositionWantsSelling() {
        Gap gap = Desk.gapFor("CORN", d("50000"), new Account());
        assertThat(gap.wantContracts()).isEqualTo(10);
        assertThat(gap.onContracts()).isZero();
        assertThat(gap.gapContracts()).isEqualTo(10);
        assertThat(gap.side()).isEqualTo(Side.SHORT);
    }

    @Test
    void theGapCarriesTheBushelsAsWellAsTheContracts() {
        assertThat(Desk.gapFor("CORN", d("50000"), new Account()).gapBushels()).isEqualTo(d("50000.0000"));
    }

    @Test
    void aShortAccountReadsAsHedgedBushels() {
        assertThat(Desk.gapFor("CORN", d("0"), shortOf(4)).hedgedBushels()).isEqualTo(d("20000.0000"));
    }

    @Test
    void aPartialHedgeLeavesTheRestToSell() {
        Gap gap = Desk.gapFor("CORN", d("50000"), shortOf(6));
        assertThat(gap.gapContracts()).isEqualTo(4);
        assertThat(gap.isActionable()).isTrue();
    }

    @Test
    void aHouseShortMoreThanItOwnsIsOverHedged() {
        Gap gap = Desk.gapFor("CORN", d("20000"), shortOf(10));
        assertThat(gap.gapContracts()).isEqualTo(-6);
        assertThat(gap.isOverHedged()).isTrue();
        assertThat(gap.side()).isEqualTo(Side.LONG);
    }

    @Test
    void aFlatBookIsNotOverHedged() {
        assertThat(Desk.gapFor("CORN", d("50000"), shortOf(10)).isOverHedged()).isFalse();
    }

    @Test
    void theWantRoundsTowardZero() {
        assertThat(Desk.gapFor("CORN", d("54999"), new Account()).wantContracts())
                .isEqualTo(10);
    }

    @Test
    void aPositionUnderOneContractWantsNothing() {
        Gap gap = Desk.gapFor("CORN", d("4999"), new Account());
        assertThat(gap.wantContracts()).isZero();
        assertThat(gap.isActionable()).isFalse();
    }

    @Test
    void aLongLotReducesWhatIsOn() {
        Account account = shortOf(10);
        account.add(aLot("F-1002", CZ4, Side.LONG, 3, "4.3225"));
        assertThat(Desk.gapFor("CORN", d("50000"), account).onContracts()).isEqualTo(7);
    }

    @Test
    void theCostOfClosingAGapIsBushelsTimesPrice() {
        assertThat(Desk.gapFor("CORN", d("50000"), new Account()).cost(d("4.4000")))
                .isEqualTo(d("220000.00"));
    }

    @Test
    void theCostOfAnOverHedgeIsStillPositive() {
        assertThat(Desk.gapFor("CORN", d("0"), shortOf(2)).cost(d("4.00"))).isEqualTo(d("40000.00"));
    }

    @Test
    void theSheetHasALineForEachCommodity() {
        Sheet sheet = Desk.sheetFor(aReport(aLine(), aLine("SOYB", "40000")), Map.of());
        assertThat(sheet.gaps()).extracting(Gap::commodityCode).containsExactly("CORN", "SOYB");
    }

    @Test
    void aCommodityWithNoAccountStaysOnTheSheet() {
        assertThat(Desk.sheetFor(aReport(), Map.of()).forCommodity("CORN").onContracts())
                .isZero();
    }

    @Test
    void theSheetMatchesEachCommodityToItsOwnAccount() {
        Sheet sheet = Desk.sheetFor(aReport(aLine(), aLine("SOYB", "40000")), Map.of("CORN", shortOf(24)));
        assertThat(sheet.forCommodity("CORN").onContracts()).isEqualTo(24);
        assertThat(sheet.forCommodity("SOYB").onContracts()).isZero();
    }

    @Test
    void askingForACommodityThatIsNotOnTheSheetIsRefused() {
        Sheet sheet = Desk.sheetFor(aReport(), Map.of());
        assertThatThrownBy(() -> sheet.forCommodity("MILO"))
                .isInstanceOf(HedgeError.class)
                .hasMessage("MILO is not on this sheet");
    }

    @Test
    void theSheetKeepsTheDayAndTheSite() {
        Sheet sheet = Desk.sheetFor(aReport(), Map.of(), "LURAY");
        assertThat(sheet.asOf()).isEqualTo(AS_OF);
        assertThat(sheet.locationCode()).isEqualTo("LURAY");
    }

    @Test
    void onlyTheGapsWorthATelephoneCallAreActionable() {
        Sheet sheet = Desk.sheetFor(aReport(aLine(), aLine("SOYB", "4000")), Map.of());
        assertThat(sheet.actionable()).extracting(Gap::commodityCode).containsExactly("CORN");
    }

    @Test
    void theOverHedgedLinesArePickedOut() {
        Sheet sheet = Desk.sheetFor(aReport(aLine("CORN", "20000")), Map.of("CORN", shortOf(10)));
        assertThat(sheet.overHedged()).extracting(Gap::commodityCode).containsExactly("CORN");
    }

    @Test
    void aBalancedSheetHasNothingOverHedged() {
        assertThat(Desk.sheetFor(aReport(), Map.of("CORN", shortOf(24))).overHedged())
                .isEmpty();
    }

    @Test
    void theHeadlineNetsTheCommodities() {
        Sheet sheet = Desk.sheetFor(aReport(aLine("CORN", "50000"), aLine("SOYB", "0")), Map.of("SOYB", shortOf(3)));
        assertThat(sheet.totalGapContracts()).isEqualTo(7);
    }

    @Test
    void deferredPriceBushelsCountTowardTheHedge() {
        PositionLine deferred = line("CORN", "100000").deferredPrice(d("50000")).build();
        Sheet sheet = Desk.sheetFor(aReport(deferred), Map.of());
        assertThat(sheet.forCommodity("CORN").netLong()).isEqualByComparingTo(deferred.netLong());
    }

    @Test
    void storageBushelsDoNotCountTowardTheHedge() {
        Sheet owned = Desk.sheetFor(aReport(aLine("CORN", "100000")), Map.of());
        Sheet owed = Desk.sheetFor(
                aReport(line("CORN", "100000").storageObligation(d("60000")).build()), Map.of());
        assertThat(owed.forCommodity("CORN").wantContracts())
                .isLessThan(owned.forCommodity("CORN").wantContracts());
    }

    @Test
    void basisIsCashLessFutures() {
        assertThat(Desk.basis(d("4.1000"), d("4.3225"))).isEqualTo(d("-0.2225"));
    }

    @Test
    void aCarryThatBeatsStorageIsPositive() {
        assertThat(Desk.carry(d("4.20"), d("4.45"), 3, d("0.05"))).isEqualTo(d("0.1000"));
    }

    @Test
    void aCarryThatDoesNotCoverStorageIsNegative() {
        assertThat(Desk.carry(d("4.20"), d("4.25"), 3, d("0.05"))).isNegative();
    }

    @Test
    void anInvertedMarketPaysNothingToHold() {
        assertThat(Desk.carry(d("4.50"), d("4.20"), 2, d("0.04"))).isNegative();
    }

    @Test
    void aCarryNeedsAtLeastOneMonth() {
        assertThatThrownBy(() -> Desk.carry(d("4.20"), d("4.45"), 0, d("0"))).isInstanceOf(HedgeError.class);
    }

    @Test
    void aShortGainsWhenTheMarketFalls() {
        Map<String, BigDecimal> exposure = Desk.monthExposure(shortOf(10), Map.of(CZ4, d("4.2225")));
        assertThat(exposure.get("CORNZ24")).isEqualTo(d("5000.00"));
    }

    @Test
    void aShortLosesWhenTheMarketRises() {
        assertThat(Desk.monthExposure(shortOf(10), Map.of(CZ4, d("4.4225"))).get("CORNZ24"))
                .isEqualTo(d("-5000.00"));
    }

    @Test
    void eachMonthIsPricedOnItsOwn() {
        Account account = shortOf(10);
        account.add(aLot("F-1002", CH5, Side.SHORT, 10, "4.5000"));
        Map<String, BigDecimal> exposure = Desk.monthExposure(account, Map.of(CZ4, d("4.3225"), CH5, d("4.4000")));
        assertThat(exposure.get("CORNZ24")).isEqualTo(d("0.00"));
        assertThat(exposure.get("CORNH25")).isEqualTo(d("5000.00"));
        assertThat(exposure.keySet()).containsExactly("CORNZ24", "CORNH25");
    }

    @Test
    void aMonthWithNoSettlementPriceIsRefused() {
        assertThatThrownBy(() -> Desk.monthExposure(shortOf(10), Map.of()))
                .isInstanceOf(HedgeError.class)
                .hasMessage("no settlement price for CORNZ24");
    }

    @Test
    void aFlattenedMonthDropsOffTheExposure() {
        Account account = shortOf(10);
        account.add(aLot("F-1002", CZ4, Side.LONG, 10, "4.3225"));
        assertThat(Desk.monthExposure(account, Map.of())).isEmpty();
    }

    @Test
    void aGapCanBeBuiltByHandForAWhatIf() {
        Gap gap = new Gap("CORN", d("75000"), d("50000"), 15, 10);
        assertThat(gap.gapContracts()).isEqualTo(5);
        assertThat(gap.isActionable()).isTrue();
    }

    @Test
    void theSheetCanBeBuiltOffTheBooks() {
        try (UnitOfWork uow = UnitOfWork.open(TestDatabase.fresh())) {
            assertThat(Desk.sheet(uow, AS_OF, Map.of()).gaps()).isEmpty();
            ScaleHouse.recordLoad(uow, HouseFixtures.cornTicket(), "MERRICK", HouseFixtures.rules());
            Sheet sheet = Desk.sheet(uow, AS_OF, Map.of("CORN", shortOf(1)), List.of("CORN"), "HOME");
            assertThat(sheet.locationCode()).isEqualTo("HOME");
            assertThat(sheet.gaps()).extracting(Gap::commodityCode).containsExactly("CORN");
            assertThat(sheet.forCommodity("CORN").onContracts()).isEqualTo(1);
        }
    }
}
