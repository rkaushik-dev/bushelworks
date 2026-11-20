package io.github.rkaushikdev.bushelworks.domain.contracts;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ContractsTest {

    static final DeliveryWindow WINDOW = new DeliveryWindow(LocalDate.of(2024, 10, 1), LocalDate.of(2024, 11, 30));
    static final FuturesMonth CZ4 = new FuturesMonth("CORN", "Z", 2024);
    static final FuturesMonth CH5 = new FuturesMonth("CORN", "H", 2025);

    static PurchaseContract.Builder forward() {
        return PurchaseContract.builder()
                .number("F-2024-118")
                .kind(ContractKind.FORWARD)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d("20000"))
                .window(WINDOW)
                .flatPrice(d("4.6200"));
    }

    static PurchaseContract.Builder hta() {
        return PurchaseContract.builder()
                .number("H-2024-042")
                .kind(ContractKind.HTA)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d("10000"))
                .window(WINDOW)
                .futuresMonth(CZ4)
                .futuresPrice(d("4.8500"));
    }

    static PurchaseContract.Builder deferred(String number) {
        return PurchaseContract.builder()
                .number(number)
                .kind(ContractKind.DEFERRED)
                .partyId("HOLLIS")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 10, 5))
                .bushels(d("8000"))
                .window(WINDOW);
    }

    @Test
    void deliveryWindowSpansInclusiveDays() {
        assertThat(WINDOW.contains(LocalDate.of(2024, 10, 1))).isTrue();
        assertThat(WINDOW.contains(LocalDate.of(2024, 11, 30))).isTrue();
        assertThat(WINDOW.contains(LocalDate.of(2024, 9, 30))).isFalse();
        assertThat(WINDOW.days()).isEqualTo(61);
    }

    @Test
    void aWindowCannotCloseBeforeItOpens() {
        assertThatThrownBy(() -> new DeliveryWindow(LocalDate.of(2024, 11, 30), LocalDate.of(2024, 10, 1)))
                .isInstanceOf(ContractError.class);
    }

    @Test
    void aForwardIsPricedTheDayItIsWritten() {
        PurchaseContract contract = forward().build();
        assertThat(contract.isFullyPriced()).isTrue();
        assertThat(contract.unitPrice()).isEqualTo(d("4.6200"));
    }

    @Test
    void aForwardWithoutAPriceIsRefused() {
        assertThatThrownBy(() -> forward().flatPrice(null).build()).isInstanceOf(ContractError.class);
    }

    @Test
    void anHtaIsNotPricedUntilTheBasisIsSet() {
        PurchaseContract contract = hta().build();
        assertThat(contract.isFullyPriced()).isFalse();
        assertThatThrownBy(contract::unitPrice).isInstanceOf(ContractError.class);
    }

    @Test
    void settingTheBasisPricesAnHta() {
        PurchaseContract contract = hta().build().setBasis(d("-0.2500"), LocalDate.of(2024, 10, 15));
        assertThat(contract.isFullyPriced()).isTrue();
        assertThat(contract.unitPrice()).isEqualTo(d("4.6000"));
    }

    @Test
    void theBasisCanOnlyBeSetOnce() {
        PurchaseContract contract = hta().build().setBasis(d("-0.2500"), LocalDate.of(2024, 10, 15));
        assertThatThrownBy(() -> contract.setBasis(d("-0.1500"), LocalDate.of(2024, 10, 20)))
                .isInstanceOf(ContractError.class);
    }

    @Test
    void theBasisCannotBeSetAfterTheWindowCloses() {
        assertThatThrownBy(() -> hta().build().setBasis(d("-0.2500"), LocalDate.of(2024, 12, 15)))
                .isInstanceOf(ContractError.class);
    }

    @Test
    void onlyAnHtaSetsABasis() {
        assertThatThrownBy(() -> forward().build().setBasis(d("-0.25"), LocalDate.of(2024, 10, 15)))
                .isInstanceOf(ContractError.class);
    }

    @Test
    void aBasisContractPricesWhenTheFuturesAreFixed() {
        PurchaseContract contract = PurchaseContract.builder()
                .number("B-2024-009")
                .kind(ContractKind.BASIS)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d("5000"))
                .window(WINDOW)
                .futuresMonth(CZ4)
                .basis(d("-0.3000"))
                .build();
        assertThat(contract.isFullyPriced()).isFalse();
        PurchaseContract priced = contract.setFutures(d("4.7000"), LocalDate.of(2024, 11, 1));
        assertThat(priced.unitPrice()).isEqualTo(d("4.4000"));
    }

    @Test
    void onlyABasisContractSetsFutures() {
        assertThatThrownBy(() -> hta().build().setFutures(d("4.70"), LocalDate.of(2024, 11, 1)))
                .isInstanceOf(ContractError.class);
    }

    @Test
    void aDeferredPriceContractCarriesNoPrice() {
        PurchaseContract contract =
                deferred("D-2024-003").serviceRatePerBushel(d("0.0050")).build();
        assertThat(contract.isFullyPriced()).isFalse();
        assertThatThrownBy(() -> deferred("D-2024-004").flatPrice(d("4.50")).build())
                .isInstanceOf(ContractError.class);
    }

    @Test
    void serviceChargeRoundsUpToAWholeMonth() {
        PurchaseContract contract =
                deferred("D-2024-003").serviceRatePerBushel(d("0.0050")).build();
        assertThat(contract.serviceCharge(d("8000"), 31)).isEqualTo(d("80.00"));
        assertThat(contract.serviceCharge(d("8000"), 30)).isEqualTo(d("40.00"));
        assertThat(contract.serviceCharge(d("8000"), 0)).isEqualTo(d("0.00"));
    }

    @Test
    void toleranceGivesAFloorAndACeiling() {
        PurchaseContract contract = forward().tolerancePercent(d("5")).build();
        assertThat(contract.minimumBushels()).isEqualTo(d("19000.0000"));
        assertThat(contract.maximumBushels()).isEqualTo(d("21000.0000"));
    }

    @Test
    void toleranceOutsideAQuarterIsRefused() {
        assertThatThrownBy(() -> forward().tolerancePercent(d("40")).build()).isInstanceOf(ContractError.class);
    }

    @Test
    void zeroBushelsIsNotAContract() {
        assertThatThrownBy(() -> forward().bushels(d("0")).build()).isInstanceOf(ContractError.class);
    }

    @Test
    void aFuturesMonthOfTheWrongCommodityIsRefused() {
        assertThatThrownBy(() ->
                        hta().futuresMonth(new FuturesMonth("SOYB", "X", 2024)).build())
                .isInstanceOf(ContractError.class);
    }

    @Test
    void rollingForwardAddsTheSpread() {
        PurchaseContract rolled = hta().build().rollTo(CH5, d("0.1200"));
        assertThat(rolled.futuresMonth()).contains(CH5);
        assertThat(rolled.futuresPrice()).contains(d("4.9700"));
    }

    @Test
    void rollingBackwardIsRefused() {
        PurchaseContract contract =
                hta().futuresMonth(CH5).futuresPrice(d("4.97")).build();
        assertThatThrownBy(() -> contract.rollTo(CZ4, d("-0.12"))).isInstanceOf(ContractError.class);
    }

    @Test
    void deliveryIsOnlyAcceptedInsideTheWindowWhileOpen() {
        PurchaseContract contract = forward().build();
        assertThat(contract.acceptsDeliveryOn(LocalDate.of(2024, 10, 15))).isTrue();
        assertThat(contract.acceptsDeliveryOn(LocalDate.of(2024, 12, 15))).isFalse();
        assertThat(contract.cancel().acceptsDeliveryOn(LocalDate.of(2024, 10, 15)))
                .isFalse();
    }

    @Test
    void aCancelledContractCannotBeCancelledAgain() {
        PurchaseContract contract = forward().build().cancel();
        assertThat(contract.status()).isSameAs(ContractStatus.CANCELLED);
        assertThatThrownBy(contract::cancel).isInstanceOf(ContractError.class);
    }
}
