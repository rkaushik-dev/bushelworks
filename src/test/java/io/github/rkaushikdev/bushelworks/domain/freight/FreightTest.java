package io.github.rkaushikdev.bushelworks.domain.freight;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FreightTest {

    static final Lane SALINA = new Lane("HOME", "SALINA", d("62"));
    static final LocalDate EFFECTIVE = LocalDate.of(2024, 9, 1);
    static final BigDecimal BUSHELS = d("930.0000");
    static final BigDecimal POUNDS = d("52080");

    static FreightRate aRate() {
        return FreightRate.of("Kruse Trucking", SALINA, RateBasis.PER_BUSHEL, d("0.1800"), EFFECTIVE);
    }

    static Haul aHaul() {
        return new Haul("T-4417", aRate(), BUSHELS, POUNDS, LocalDate.of(2024, 11, 12));
    }

    @Test
    void aLaneNeedsADistance() {
        assertThatThrownBy(() -> new Lane("HOME", "SALINA", d("0"))).isInstanceOf(FreightError.class);
    }

    @Test
    void aLaneHasToGoSomewhere() {
        assertThatThrownBy(() -> new Lane("HOME", "HOME", d("10"))).isInstanceOf(FreightError.class);
    }

    @Test
    void aOneWayLaneBillsItsLoadedMiles() {
        assertThat(SALINA.billedMiles()).isEqualByComparingTo(d("62"));
        assertThat(SALINA.label()).isEqualTo("HOME-SALINA");
    }

    @Test
    void aRoundTripLaneBillsBothWays() {
        assertThat(new Lane("HOME", "SALINA", d("62"), true).billedMiles()).isEqualByComparingTo(d("124"));
    }

    @Test
    void aNegativeRateIsRefused() {
        assertThatThrownBy(() -> aRate().withRate(d("-0.01"))).isInstanceOf(FreightError.class);
    }

    @Test
    void anImplausibleFuelSurchargeIsRefused() {
        assertThatThrownBy(() -> aRate().withFuelSurchargePercent(d("90"))).isInstanceOf(FreightError.class);
    }

    @Test
    void aPerBushelRateExtendsOverBushels() {
        assertThat(aRate().lineHaul(BUSHELS, POUNDS)).isEqualTo(d("167.40"));
    }

    @Test
    void aPerMileRateUsesTheBilledMiles() {
        assertThat(aRate().withBasis(RateBasis.PER_MILE, d("2.9000")).lineHaul(BUSHELS, POUNDS))
                .isEqualTo(d("179.80"));
    }

    @Test
    void aPerHundredweightRateUsesTheWeight() {
        assertThat(aRate().withBasis(RateBasis.PER_HUNDREDWEIGHT, d("0.3200")).lineHaul(BUSHELS, POUNDS))
                .isEqualTo(d("166.66"));
    }

    @Test
    void aFlatRateIgnoresTheLoad() {
        FreightRate rate = aRate().withBasis(RateBasis.FLAT, d("200.00"));
        assertThat(rate.lineHaul(BUSHELS, POUNDS)).isEqualTo(d("200.00"));
        assertThat(rate.lineHaul(d("1"), d("1"))).isEqualTo(d("200.00"));
    }

    @Test
    void aNegativeLoadCannotBeRated() {
        assertThatThrownBy(() -> aRate().lineHaul(d("-1"), POUNDS)).isInstanceOf(FreightError.class);
    }

    @Test
    void noSurchargeMeansNoFuelLine() {
        assertThat(aRate().fuel(BUSHELS, POUNDS)).isEqualTo(d("0.00"));
    }

    @Test
    void theSurchargeIsAPercentageOfTheLineHaul() {
        FreightRate rate = aRate().withFuelSurchargePercent(d("18.5"));
        assertThat(rate.fuel(BUSHELS, POUNDS)).isEqualTo(d("30.97"));
        assertThat(rate.total(BUSHELS, POUNDS)).isEqualTo(d("198.37"));
    }

    @Test
    void theMinimumAppliesToASmallLoad() {
        assertThat(aRate().withMinimumCharge(d("250.00")).total(BUSHELS, POUNDS))
                .isEqualTo(d("250.00"));
    }

    @Test
    void theMinimumDoesNotCapABigLoad() {
        assertThat(aRate().withMinimumCharge(d("100.00")).total(BUSHELS, POUNDS))
                .isEqualTo(d("167.40"));
    }

    @Test
    void perBushelLetsTwoBasesBeCompared() {
        FreightRate perBushel = aRate();
        FreightRate perMile = aRate().withBasis(RateBasis.PER_MILE, d("2.9000"));
        assertThat(perBushel.perBushel(BUSHELS, POUNDS)).isLessThan(perMile.perBushel(BUSHELS, POUNDS));
    }

    @Test
    void comparingPerBushelNeedsARealLoad() {
        assertThatThrownBy(() -> aRate().perBushel(d("0"), POUNDS)).isInstanceOf(FreightError.class);
    }

    @Test
    void theCheapestQuoteIsFoundForARealLoad() {
        List<FreightRate> quotes = List.of(
                aRate(),
                aRate().withCarrier("Ostmeyer").withBasis(RateBasis.PER_MILE, d("2.9000")),
                aRate().withCarrier("Dible").withBasis(RateBasis.FLAT, d("150.00")));
        assertThat(Freight.cheapest(quotes, BUSHELS, POUNDS).carrier()).isEqualTo("Dible");
    }

    @Test
    void comparingNoQuotesIsRefused() {
        assertThatThrownBy(() -> Freight.cheapest(List.of(), BUSHELS, POUNDS)).isInstanceOf(FreightError.class);
    }

    @Test
    void aHaulCarriesItsOwnArithmetic() {
        Haul haul = aHaul();
        assertThat(haul.lineHaul()).isEqualTo(d("167.40"));
        assertThat(haul.fuel()).isEqualTo(d("0.00"));
        assertThat(haul.total()).isEqualTo(d("167.40"));
    }

    @Test
    void aNormalLoadIsNotFlaggedOverweight() {
        assertThat(aHaul().isOverweight()).isFalse();
    }

    @Test
    void aVeryHeavyLoadIsFlagged() {
        assertThat(new Haul("T-4417", aRate(), BUSHELS, d("62000"), LocalDate.of(2024, 11, 12)).isOverweight())
                .isTrue();
    }

    @Test
    void theInvoiceAddsTheHauls() {
        Haul second = new Haul("T-4418", aRate(), BUSHELS, POUNDS, LocalDate.of(2024, 11, 12));
        assertThat(Freight.invoiceTotal(List.of(aHaul(), second))).isEqualTo(d("334.80"));
    }

    @Test
    void reconcilingAMatchingInvoiceFindsNothing() {
        assertThat(Freight.reconcileInvoice(List.of(aHaul()), d("167.40"))).isEqualTo(d("0.00"));
    }

    @Test
    void reconcilingFindsAnOverbilledInvoice() {
        assertThat(Freight.reconcileInvoice(List.of(aHaul()), d("198.37"))).isEqualTo(d("30.97"));
    }

    @Test
    void freightDefaultsToTheHousePaying() {
        assertThat(aRate().paidBy()).isSameAs(PaidBy.HOUSE);
        assertThat(aRate().withPaidBy(PaidBy.PRODUCER).paidBy()).isSameAs(PaidBy.PRODUCER);
    }
}
