package io.github.rkaushikdev.bushelworks.domain.pricing;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PricingTest {

    static final FuturesMonth CZ4 = new FuturesMonth("CORN", "Z", 2024);
    static final FuturesMonth CH5 = new FuturesMonth("CORN", "H", 2025);
    static final FuturesMonth CN4 = new FuturesMonth("CORN", "N", 2024);
    static final LocalDate QUOTED = LocalDate.of(2024, 10, 3);

    @Test
    void symbolUsesTheTwoDigitYear() {
        assertThat(CZ4.symbol()).isEqualTo("CORNZ24");
        assertThat(CH5.symbol()).isEqualTo("CORNH25");
    }

    @Test
    void monthsOrderByYearThenDeliveryMonth() {
        assertThat(FuturesMonth.order(List.of(CH5, CZ4, CN4))).containsExactly(CN4, CZ4, CH5);
        assertThat(CN4.isBefore(CZ4)).isTrue();
        assertThat(CH5.isBefore(CZ4)).isFalse();
    }

    @Test
    void decemberSortsAfterJulyDespiteTheLetters() {
        assertThat(CN4.compareTo(CZ4)).isNegative();
    }

    @Test
    void nextMonthWalksTheDeliveryCycle() {
        assertThat(CN4.next()).isEqualTo(new FuturesMonth("CORN", "U", 2024));
        assertThat(new FuturesMonth("CORN", "U", 2024).next()).isEqualTo(CZ4);
    }

    @Test
    void nextMonthRollsTheYearAtTheEndOfTheCycle() {
        assertThat(CZ4.next()).isEqualTo(new FuturesMonth("CORN", "H", 2025));
    }

    @Test
    void cornHasNoJanuaryContract() {
        assertThatThrownBy(() -> new FuturesMonth("CORN", "F", 2024)).isInstanceOf(PricingError.class);
    }

    @Test
    void soybeansDoHaveAJanuaryContract() {
        assertThat(new FuturesMonth("SOYB", "F", 2025).monthNumber()).isEqualTo(1);
    }

    @Test
    void anUnknownMonthLetterIsRefused() {
        assertThatThrownBy(() -> new FuturesMonth("CORN", "B", 2024)).isInstanceOf(PricingError.class);
    }

    @Test
    void firstNoticeIsTheFirstOfTheDeliveryMonth() {
        assertThat(CZ4.firstNotice()).isEqualTo(LocalDate.of(2024, 12, 1));
    }

    @Test
    void cashIsFuturesPlusBasis() {
        CashBid bid = new CashBid("CORN", CZ4, d("4.5750"), d("-0.2250"), QUOTED);
        assertThat(bid.cash()).isEqualTo(d("4.3500"));
    }

    @Test
    void aBidNeverGoesBelowZero() {
        CashBid bid = new CashBid("CORN", CZ4, d("4.0000"), d("-4.5000"), QUOTED);
        assertThat(bid.cash()).isEqualTo(d("0.0000"));
    }

    @Test
    void withBasisReturnsANewBid() {
        CashBid bid = new CashBid("CORN", CZ4, d("4.5750"), d("-0.2250"), QUOTED);
        CashBid wider = bid.withBasis(d("-0.4000"));
        assertThat(wider.cash()).isEqualTo(d("4.1750"));
        assertThat(bid.cash()).isEqualTo(d("4.3500"));
    }

    @Test
    void aBidMonthHasToMatchTheCommodity() {
        assertThatThrownBy(() -> new CashBid("SOYB", CZ4, d("11.00"), d("-0.30"), QUOTED))
                .isInstanceOf(PricingError.class);
    }

    @Test
    void futuresPriceMustBePositive() {
        assertThatThrownBy(() -> new CashBid("CORN", CZ4, d("0"), d("-0.30"), QUOTED))
                .isInstanceOf(PricingError.class);
    }

    @Test
    void anImplausibleBasisIsRefused() {
        assertThatThrownBy(() -> new Basis(CZ4, d("-9.00"), QUOTED)).isInstanceOf(PricingError.class);
    }

    @Test
    void pricedQuantityValuesItself() {
        PricedQuantity lot = new PricedQuantity(d("932.8571"), d("4.3500"));
        assertThat(lot.value()).isEqualTo(d("4057.93"));
    }

    @Test
    void aNegativeQuantityCannotBePriced() {
        assertThatThrownBy(() -> new PricedQuantity(d("-1"), d("4.35"))).isInstanceOf(PricingError.class);
    }

    @Test
    void blendWeightsByBushels() {
        List<PricedQuantity> lots =
                List.of(new PricedQuantity(d("1000"), d("4.2000")), new PricedQuantity(d("3000"), d("4.6000")));
        assertThat(PricedQuantity.blend(lots)).isEqualTo(d("4.5000"));
    }

    @Test
    void blendNeedsBushelsToWeightWith() {
        assertThatThrownBy(() -> PricedQuantity.blend(List.of())).isInstanceOf(PricingError.class);
    }
}
