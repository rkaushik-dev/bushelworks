package io.github.rkaushikdev.bushelworks.services.deferred;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.StorageLotRow;
import io.github.rkaushikdev.bushelworks.services.warehousing.Warehousing;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeferredTest {

    static final LocalDate DELIVERED = LocalDate.of(2024, 10, 3);

    Database database;

    /** MERRICK has two unpriced corn lots, 10,000 and 5,000 bushels, and 3,000 in plain storage. */
    @BeforeEach
    void delivered() {
        database = TestDatabase.fresh();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Deferred.deliver(uow, "D-1", "MERRICK", "CORN", "HOME", d("10000"), DELIVERED);
            Deferred.deliver(uow, "D-2", "MERRICK", "CORN", "HOME", d("5000"), LocalDate.of(2024, 11, 12));
            Warehousing.store(uow, "L-9", "MERRICK", "CORN", "HOME", d("3000"), DELIVERED);
            uow.commit();
        }
    }

    @Test
    void serviceTermsRefuseANegativeRate() {
        assertThatThrownBy(() -> ServiceTerms.of(d("-0.01"))).isInstanceOf(DeferredPriceError.class);
    }

    @Test
    void theFreePeriodComesOffTheFront() {
        ServiceTerms terms = ServiceTerms.of(d("0.03")).withFreeDays(15);
        assertThat(terms.chargeableDays(DELIVERED, LocalDate.of(2024, 10, 18))).isZero();
        assertThat(terms.chargeableDays(DELIVERED, LocalDate.of(2024, 11, 2))).isEqualTo(15);
    }

    @Test
    void theFeeIsProratedByTheDay() {
        // 10000 bu, 30 days at 0.03 a month
        assertThat(ServiceTerms.DEFAULT.charge(d("10000"), DELIVERED, LocalDate.of(2024, 11, 2)))
                .isEqualTo(d("300.00"));
    }

    @Test
    void chargingBeforeDeliveryIsRefused() {
        assertThatThrownBy(() -> ServiceTerms.DEFAULT.charge(d("100"), DELIVERED, LocalDate.of(2024, 10, 1)))
                .isInstanceOf(DeferredPriceError.class);
    }

    @Test
    void aTermLimitMakesALotOverdue() {
        assertThat(ServiceTerms.DEFAULT.isOverdue(DELIVERED, LocalDate.of(2025, 10, 2)))
                .isFalse();
        assertThat(ServiceTerms.DEFAULT.isOverdue(DELIVERED, LocalDate.of(2025, 10, 4)))
                .isTrue();
    }

    @Test
    void termsWithNoLimitNeverGoOverdue() {
        ServiceTerms terms = ServiceTerms.of(d("0.03"));
        assertThat(terms.isOverdue(DELIVERED, LocalDate.of(2030, 1, 1))).isFalse();
    }

    @Test
    void deferredLotsAreSeparateFromStorage() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Deferred.unpricedLots(uow))
                    .extracting(StorageLotRow::getLotId)
                    .containsExactly("D-1", "D-2");
        }
    }

    @Test
    void pricingReturnsTheGrossAndTheFee() {
        PricedLot priced;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            priced = Deferred.priceLot(uow, "D-1", d("4000"), d("4.3500"), LocalDate.of(2024, 11, 2));
        }
        assertThat(priced.gross()).isEqualTo(d("17400.00"));
        assertThat(priced.fee()).isEqualTo(d("120.00"));
    }

    @Test
    void theFeeIsChargedOnlyOnTheBushelsBeingPriced() {
        BigDecimal allOfIt;
        BigDecimal someOfIt;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            allOfIt = Deferred.priceLot(uow, "D-1", d("10000"), d("4.35"), LocalDate.of(2024, 11, 2))
                    .fee();
            someOfIt = Deferred.priceLot(uow, "D-1", d("4000"), d("4.35"), LocalDate.of(2024, 11, 2))
                    .fee();
        }
        assertThat(someOfIt).isLessThan(allOfIt);
    }

    @Test
    void pricingMoreThanIsOpenIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Deferred.priceLot(uow, "D-1", d("10001"), d("4.35"), LocalDate.of(2024, 11, 2)))
                    .isInstanceOf(DeferredPriceError.class);
        }
    }

    @Test
    void pricingNothingIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Deferred.priceLot(uow, "D-1", d("0"), d("4.35"), LocalDate.of(2024, 11, 2)))
                    .isInstanceOf(DeferredPriceError.class);
        }
    }

    @Test
    void aPriceOfZeroIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Deferred.priceLot(uow, "D-1", d("100"), d("0"), LocalDate.of(2024, 11, 2)))
                    .isInstanceOf(DeferredPriceError.class);
        }
    }

    @Test
    void pricingAStorageLotIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Deferred.priceLot(uow, "L-9", d("100"), d("4.35"), LocalDate.of(2024, 11, 2)))
                    .isInstanceOf(DeferredPriceError.class);
        }
    }

    @Test
    void pricingBeforeDeliveryIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Deferred.priceLot(uow, "D-1", d("100"), d("4.35"), LocalDate.of(2024, 9, 1)))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void aProducerPositionAddsTheLotsUp() {
        DeferredPosition found;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            found = Deferred.position(uow, "MERRICK", "CORN", LocalDate.of(2024, 11, 12))
                    .orElseThrow();
        }
        assertThat(found.bushels()).isEqualTo(d("15000.0000"));
        assertThat(found.oldestDelivery()).isEqualTo(DELIVERED);
        assertThat(found.serviceOwed()).isPositive();
        assertThat(found.isOverdue()).isFalse();
    }

    @Test
    void aProducerWithNothingUnpricedHasNoPosition() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Deferred.position(uow, "HOLLIS", "CORN", LocalDate.of(2024, 11, 12)))
                    .isEmpty();
        }
    }

    @Test
    void aPositionReportsWhichLotsAreOverdue() {
        DeferredPosition found;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            found = Deferred.position(uow, "MERRICK", "CORN", LocalDate.of(2025, 12, 1))
                    .orElseThrow();
        }
        assertThat(found.isOverdue()).isTrue();
        assertThat(found.overdueLots()).containsExactly("D-1", "D-2");
    }

    @Test
    void theHouseIsLongTheUnpricedBushels() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Deferred.houseExposure(uow, "CORN", LocalDate.of(2024, 11, 12)))
                    .isEqualTo(d("15000.0000"));
        }
    }

    @Test
    void exposureIgnoresDeliveriesAfterTheDayAskedAbout() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Deferred.houseExposure(uow, "CORN", LocalDate.of(2024, 10, 15)))
                    .isEqualTo(d("10000.0000"));
        }
    }

    @Test
    void storageBushelsAreNotHouseExposure() {
        BigDecimal exposure;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            exposure = Deferred.houseExposure(uow, "CORN", LocalDate.of(2024, 11, 12));
        }
        assertThat(exposure).isEqualTo(d("15000.0000"));
    }

    @Test
    void theOverdueListIsEmptyInsideTheTerm() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Deferred.overdue(uow, LocalDate.of(2025, 1, 1))).isEmpty();
        }
    }

    @Test
    void theOverdueListNamesTheLotsPastTheTerm() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Deferred.overdue(uow, LocalDate.of(2025, 12, 1)))
                    .extracting(StorageLotRow::getLotId)
                    .containsExactly("D-1", "D-2");
        }
    }

    @Test
    void totalUnpricedDropsAsBushelsAreTakenOut() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Deferred.totalUnpriced(uow)).isEqualTo(d("15000.0000"));
            Warehousing.withdraw(uow, "MERRICK", "CORN", d("3000"), LocalDate.of(2024, 12, 1));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Deferred.totalUnpriced(uow)).isLessThan(d("15000.0000"));
        }
    }
}
