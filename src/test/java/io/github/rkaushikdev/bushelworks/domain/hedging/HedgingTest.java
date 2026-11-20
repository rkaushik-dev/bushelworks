package io.github.rkaushikdev.bushelworks.domain.hedging;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HedgingTest {

    static final FuturesMonth CZ4 = new FuturesMonth("CORN", "Z", 2024);
    static final FuturesMonth CH5 = new FuturesMonth("CORN", "H", 2025);
    static final LocalDate FILLED = LocalDate.of(2024, 10, 3);

    static Lot aLot(String reference, FuturesMonth month, Side side, int contracts, String price) {
        return new Lot(reference, month, side, contracts, d(price), FILLED);
    }

    static Lot aLot() {
        return aLot("F-0001", CZ4, Side.SHORT, 10, "4.5750");
    }

    @Test
    void aFillNeedsContractsAndAPrice() {
        assertThatThrownBy(() -> aLot("F-0001", CZ4, Side.SHORT, 0, "4.5750")).isInstanceOf(HedgeError.class);
        assertThatThrownBy(() -> aLot("F-0001", CZ4, Side.SHORT, 10, "0")).isInstanceOf(HedgeError.class);
    }

    @Test
    void aLotKnowsItsBushels() {
        assertThat(aLot().bushels()).isEqualByComparingTo(d("50000"));
        assertThat(Lot.CONTRACT_BUSHELS).isEqualByComparingTo(d("5000"));
    }

    @Test
    void aShortCountsNegative() {
        assertThat(aLot().signedContracts()).isEqualTo(-10);
        assertThat(aLot("F-0001", CZ4, Side.LONG, 10, "4.5750").signedContracts())
                .isEqualTo(10);
    }

    @Test
    void anEmptyAccountIsFlat() {
        Account account = new Account();
        assertThat(account.netContracts()).isZero();
        assertThat(account.byMonth()).isEmpty();
        assertThat(account.isShort()).isFalse();
    }

    @Test
    void theAccountNetsByMonth() {
        Account account = new Account();
        account.add(aLot());
        account.add(aLot("F-0002", CH5, Side.SHORT, 4, "4.5750"));
        assertThat(account.byMonth()).isEqualTo(Map.of(CZ4, -10, CH5, -4));
        assertThat(account.netContracts()).isEqualTo(-14);
        assertThat(account.netBushels()).isEqualByComparingTo(d("-70000"));
        assertThat(account.isShort()).isTrue();
    }

    @Test
    void aMonthThatFlattensDropsOff() {
        Account account = new Account();
        account.add(aLot());
        account.add(aLot("F-0002", CZ4, Side.LONG, 10, "4.5750"));
        assertThat(account.byMonth()).isEmpty();
        assertThat(account.netContracts()).isZero();
    }

    @Test
    void theSameReferenceTwiceIsAConflict() {
        Account account = new Account();
        account.add(aLot());
        assertThatThrownBy(() -> account.add(aLot())).isInstanceOf(ConflictError.class);
    }

    @Test
    void theAveragePriceIsWeightedByContracts() {
        Account account = new Account();
        account.add(aLot("F-0001", CZ4, Side.SHORT, 10, "4.5000"));
        account.add(aLot("F-0002", CZ4, Side.SHORT, 30, "4.7000"));
        assertThat(account.averagePrice(CZ4)).isEqualTo(d("4.6500"));
    }

    @Test
    void aMonthWithNothingOnHasNoAverage() {
        assertThatThrownBy(() -> new Account().averagePrice(CZ4)).isInstanceOf(HedgeError.class);
    }

    @Test
    void aShortGainsWhenTheMarketFalls() {
        Account account = new Account();
        account.add(aLot());
        assertThat(account.openValue(Map.of(CZ4, d("4.4750")))).isEqualTo(d("5000.00"));
    }

    @Test
    void aShortLosesWhenTheMarketRises() {
        Account account = new Account();
        account.add(aLot());
        assertThat(account.openValue(Map.of(CZ4, d("4.6750")))).isEqualTo(d("-5000.00"));
    }

    @Test
    void aMonthWithNoSettlementPriceIsRefused() {
        Account account = new Account();
        account.add(aLot());
        assertThatThrownBy(() -> account.openValue(Map.of())).isInstanceOf(HedgeError.class);
    }

    @Test
    void aFlatAccountIsWorthNothing() {
        assertThat(new Account().openValue(Map.<FuturesMonth, BigDecimal>of())).isEqualTo(d("0.00"));
    }

    @Test
    void contractsAreWholeAndRoundedTowardZero() {
        assertThat(Hedging.contractsFor(d("50000"))).isEqualTo(10);
        assertThat(Hedging.contractsFor(d("52400"))).isEqualTo(10);
        assertThat(Hedging.contractsFor(d("-52400"))).isEqualTo(-10);
    }

    @Test
    void theHedgeGapSaysHowManyMoreToSell() {
        Account account = new Account();
        account.add(aLot("F-0001", CZ4, Side.SHORT, 5, "4.5750"));
        assertThat(Hedging.hedgeGap(d("50000"), account)).isEqualTo(-5);
    }

    @Test
    void aFullyHedgedPositionHasNoGap() {
        Account account = new Account();
        account.add(aLot());
        assertThat(Hedging.hedgeGap(d("50000"), account)).isZero();
    }

    @Test
    void anOverHedgedPositionSaysToBuyBack() {
        Account account = new Account();
        account.add(aLot("F-0001", CZ4, Side.SHORT, 12, "4.5750"));
        assertThat(Hedging.hedgeGap(d("50000"), account)).isEqualTo(2);
    }

    @Test
    void rollingAMonthThatIsNotOnIsRefused() {
        assertThatThrownBy(() -> Hedging.roll(new Account(), CZ4, d("4.57"), d("4.70"), FILLED))
                .isInstanceOf(HedgeError.class);
    }

    @Test
    void theRollCostIsTheSpreadOverTheBushels() {
        Account account = new Account();
        account.add(aLot());
        assertThat(Hedging.rollCost(account, CZ4, d("4.5750"), d("4.7000"))).isEqualTo(d("-6250.00"));
    }

    @Test
    void rollingClosesTheNearMonthAndOpensTheFarOne() {
        Account account = new Account();
        account.add(aLot());
        List<Lot> lots = Hedging.roll(account, CZ4, d("4.5750"), d("4.7000"), LocalDate.of(2024, 11, 25));
        assertThat(lots.get(0).side()).isSameAs(Side.LONG);
        assertThat(lots.get(1).side()).isSameAs(Side.SHORT);
        assertThat(account.byMonth()).isEqualTo(Map.of(CH5, -10));
    }

    @Test
    void rollingALongPositionGoesTheOtherWay() {
        Account account = new Account();
        account.add(aLot("F-0001", CZ4, Side.LONG, 10, "4.5750"));
        List<Lot> lots = Hedging.roll(account, CZ4, d("4.5750"), d("4.7000"), LocalDate.of(2024, 11, 25));
        assertThat(lots.get(0).side()).isSameAs(Side.SHORT);
        assertThat(lots.get(1).side()).isSameAs(Side.LONG);
        assertThat(account.byMonth()).isEqualTo(Map.of(CH5, 10));
    }

    @Test
    void theMonthsOnAreInDeliveryOrder() {
        Account account = new Account();
        account.add(aLot("F-0001", CH5, Side.SHORT, 10, "4.5750"));
        account.add(aLot("F-0002", CZ4, Side.SHORT, 10, "4.5750"));
        assertThat(Hedging.monthsOn(account)).containsExactly(CZ4, CH5);
    }

    @Test
    void theRollCostOfAMonthThatIsNotOnIsRefused() {
        assertThatThrownBy(() -> Hedging.rollCost(new Account(), CZ4, d("4.57"), d("4.70")))
                .isInstanceOf(HedgeError.class);
    }
}
