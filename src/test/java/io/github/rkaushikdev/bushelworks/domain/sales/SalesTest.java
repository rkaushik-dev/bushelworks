package io.github.rkaushikdev.bushelworks.domain.sales;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SalesTest {

    static final DeliveryWindow WINDOW = new DeliveryWindow(LocalDate.of(2024, 11, 1), LocalDate.of(2024, 12, 31));
    static final Specification SPEC = new Specification(d("15.0"), d("54.0"), d("5.0"), d("3.0"));

    static SaleContract.Builder aSale() {
        return SaleContract.builder()
                .number("S-2024-0088")
                .kind(SaleKind.FORWARD)
                .buyer("Salina Terminal")
                .destination(Destination.TERMINAL)
                .commodityCode("CORN")
                .originCode("HOME")
                .writtenOn(LocalDate.of(2024, 9, 5))
                .bushels(d("50000"))
                .window(WINDOW)
                .specification(SPEC)
                .flatPrice(d("4.7800"))
                .freightPerBushel(d("0.1800"));
    }

    static Map<Factor, BigDecimal> aSample() {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.MOISTURE, d("14.2"));
        sample.put(Factor.TEST_WEIGHT, d("56.0"));
        sample.put(Factor.DAMAGE_TOTAL, d("2.0"));
        sample.put(Factor.BCFM, d("1.5"));
        return sample;
    }

    static Map<Factor, BigDecimal> aSample(Factor factor, String value) {
        Map<Factor, BigDecimal> sample = aSample();
        sample.put(factor, d(value));
        return sample;
    }

    static LocalDate day(int month, int dayOfMonth) {
        return LocalDate.of(2024, month, dayOfMonth);
    }

    @Test
    void aCleanLoadMeetsTheSpecification() {
        assertThat(SPEC.accepts(aSample())).isTrue();
        assertThat(SPEC.failures(aSample())).isEmpty();
    }

    @Test
    void wetGrainBreaksTheMoistureClause() {
        assertThat(SPEC.failures(aSample(Factor.MOISTURE, "16.4"))).containsExactly("moisture");
    }

    @Test
    void lightGrainBreaksTheTestWeightClause() {
        assertThat(SPEC.failures(aSample(Factor.TEST_WEIGHT, "52.0"))).containsExactly("test weight");
    }

    @Test
    void twoClausesCanBreakAtOnce() {
        Map<Factor, BigDecimal> sample = aSample(Factor.MOISTURE, "18.0");
        sample.put(Factor.DAMAGE_TOTAL, d("7.0"));
        assertThat(SPEC.failures(sample)).containsExactly("moisture", "damage");
    }

    @Test
    void bcfmCountsAsForeignMaterial() {
        assertThat(SPEC.failures(aSample(Factor.BCFM, "4.5"))).contains("foreign material");
    }

    @Test
    void aFactorTheLoadWasNotTestedForCannotBreakAClause() {
        Map<Factor, BigDecimal> sample = aSample();
        sample.remove(Factor.MOISTURE);
        assertThat(SPEC.failures(sample)).doesNotContain("moisture");
    }

    @Test
    void aNonsenseSpecificationIsRefused() {
        assertThatThrownBy(() -> new Specification(d("0"), d("54"), d("5"), d("3")))
                .isInstanceOf(SaleError.class);
    }

    @Test
    void aForwardSaleIsPricedWhenWritten() {
        SaleContract sale = aSale().build();
        assertThat(sale.isPriced()).isTrue();
        assertThat(sale.unitPrice()).isEqualTo(d("4.7800"));
    }

    @Test
    void theNetPriceIsWhatIsLeftAfterFreight() {
        assertThat(aSale().build().netPrice()).isEqualTo(d("4.6000"));
    }

    @Test
    void aBasisSaleIsNotPricedUntilTheFuturesAreSet() {
        SaleContract sale = aSale().kind(SaleKind.BASIS)
                .flatPrice(null)
                .futuresMonth(new FuturesMonth("CORN", "Z", 2024))
                .basis(d("0.0800"))
                .build();
        assertThat(sale.isPriced()).isFalse();
        assertThatThrownBy(sale::unitPrice).isInstanceOf(SaleError.class);
        assertThat(sale.setFutures(d("4.7000")).unitPrice()).isEqualTo(d("4.7800"));
    }

    @Test
    void onlyABasisSaleSetsFuturesLater() {
        assertThatThrownBy(() -> aSale().build().setFutures(d("4.70"))).isInstanceOf(SaleError.class);
    }

    @Test
    void theFuturesCanOnlyBeSetOnce() {
        SaleContract sale = aSale().kind(SaleKind.BASIS)
                .flatPrice(null)
                .basis(d("0.08"))
                .build()
                .setFutures(d("4.70"));
        assertThatThrownBy(() -> sale.setFutures(d("4.75"))).isInstanceOf(SaleError.class);
    }

    @Test
    void aSaleWithNoPriceAtAllIsRefused() {
        assertThatThrownBy(() -> aSale().flatPrice(null).build()).isInstanceOf(SaleError.class);
    }

    @Test
    void aSaleOfNoBushelsIsRefused() {
        assertThatThrownBy(() -> aSale().bushels(d("0")).build()).isInstanceOf(SaleError.class);
    }

    @Test
    void negativeFreightIsRefused() {
        assertThatThrownBy(() -> aSale().freightPerBushel(d("-0.01")).build()).isInstanceOf(SaleError.class);
    }

    @Test
    void aNewSaleHasEverythingLeftToLoad() {
        assertThat(aSale().build().remaining()).isEqualTo(d("50000.0000"));
        assertThat(aSale().build().isComplete()).isFalse();
    }

    @Test
    void shippingReducesWhatIsLeft() {
        SaleContract sale = aSale().build().ship(d("20000"), day(11, 15));
        assertThat(sale.shipped()).isEqualTo(d("20000.0000"));
        assertThat(sale.remaining()).isEqualTo(d("30000.0000"));
    }

    @Test
    void aSaleIsCompleteAtTheFaceAmountNotTheTolerance() {
        SaleContract sale = aSale().build().ship(d("49000"), day(11, 15));
        assertThat(sale.isComplete()).isFalse();
        assertThat(sale.ship(d("1000"), day(11, 20)).isComplete()).isTrue();
    }

    @Test
    void theToleranceLetsTheLastCarRunHeavy() {
        SaleContract sale = aSale().build().ship(d("50000"), day(11, 15));
        assertThat(sale.canShip(d("900"), day(11, 20))).isTrue();
        assertThat(sale.canShip(d("1100"), day(11, 20))).isFalse();
    }

    @Test
    void shippingPastTheToleranceIsRefused() {
        SaleContract sale = aSale().build().ship(d("50000"), day(11, 15));
        assertThatThrownBy(() -> sale.ship(d("2000"), day(11, 20))).isInstanceOf(SaleError.class);
    }

    @Test
    void shippingOutsideTheWindowIsRefused() {
        assertThatThrownBy(() -> aSale().build().ship(d("1000"), LocalDate.of(2025, 1, 15)))
                .isInstanceOf(SaleError.class);
        assertThat(aSale().build().canShip(d("1000"), LocalDate.of(2025, 1, 15)))
                .isFalse();
    }

    @Test
    void shippingNothingIsRefused() {
        assertThatThrownBy(() -> aSale().build().ship(d("0"), day(11, 15))).isInstanceOf(SaleError.class);
    }

    @Test
    void theCarrierDefaultsToTruck() {
        assertThat(aSale().build().carrier()).isSameAs(Carrier.TRUCK);
        assertThat(aSale().carrier(Carrier.RAIL).build().carrier()).isSameAs(Carrier.RAIL);
    }
}
