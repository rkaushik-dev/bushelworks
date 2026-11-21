package io.github.rkaushikdev.bushelworks.services.shipping;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.SaleRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ShipmentRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShippingTest {

    static final LocalDate SHIPPED = LocalDate.of(2024, 11, 12);

    Database database;

    /** Two open sales on file: corn and soybeans, ten thousand bushels each, out of HOME. */
    @BeforeEach
    void sold() {
        database = TestDatabase.fresh();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aSale());
            SaleRow beans = aSale();
            beans.setNumber("S-2024-0089");
            beans.setCommodityCode("SOYB");
            uow.session().persist(beans);
            uow.commit();
        }
    }

    static SaleRow aSale() {
        SaleRow row = new SaleRow();
        row.setNumber("S-2024-0088");
        row.setKind("forward");
        row.setBuyer("Salina Terminal");
        row.setDestination("terminal");
        row.setCommodityCode("CORN");
        row.setOriginCode("HOME");
        row.setWrittenOn(LocalDate.of(2024, 9, 5));
        row.setBushels(d("10000"));
        row.setWindowOpens(LocalDate.of(2024, 11, 1));
        row.setWindowCloses(LocalDate.of(2024, 12, 31));
        row.setFlatPrice(d("4.7800"));
        row.setFreightPerBushel(d("0.1800"));
        row.setSpecMaxMoisture(d("15.0"));
        row.setSpecMinTestWeight(d("54.0"));
        row.setSpecMaxDamage(d("5.0"));
        row.setSpecMaxForeign(d("3.0"));
        return row;
    }

    static Loaded loadCar(UnitOfWork uow, String reference, String pounds) {
        return Shipping.loadOut(uow, "S-2024-0088", reference, d(pounds), SHIPPED);
    }

    static ShipmentRow settleCarOne(UnitOfWork uow, LocalDate settledOn) {
        return Shipping.settleShipment(uow, "S-2024-0088", "CAR-1", d("195328"), "2", d("16730.00"), settledOn);
    }

    @Test
    void aMissingSaleSaysSo() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Shipping.sale(uow, "S-9999")).isInstanceOf(SaleNotFound.class);
        }
    }

    @Test
    void openSalesAreFilteredByCommodity() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Shipping.openSales(uow, "CORN"))
                    .extracting(SaleRow::getNumber)
                    .containsExactly("S-2024-0088");
        }
    }

    @Test
    void openSalesAreFilteredByTheWindow() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Shipping.openSales(uow, "CORN", LocalDate.of(2025, 3, 1)))
                    .isEmpty();
        }
    }

    @Test
    void loadingOutMovesTheShippedTotal() {
        Loaded loaded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loaded = loadCar(uow, "CAR-1", "196000");
            uow.commit();
        }
        assertThat(loaded.bushels()).isEqualTo(d("3500.0000"));
        assertThat(loaded.remaining()).isEqualTo(d("6500.0000"));
    }

    @Test
    void theShipmentRowIsWritten() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Shipping.loadOut(uow, "S-2024-0088", "CAR-1", d("196000"), SHIPPED, "BNSF 480221");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow row = Shipping.sale(uow, "S-2024-0088");
            assertThat(row.getShipments()).hasSize(1);
            assertThat(row.getShipments().get(0).getCarrierId()).contains("BNSF 480221");
        }
    }

    @Test
    void fillingASaleClosesIt() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadCar(uow, "CAR-1", "560000");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Shipping.sale(uow, "S-2024-0088").getStatus()).isEqualTo("filled");
        }
    }

    @Test
    void aFilledSaleTakesNoMore() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadCar(uow, "CAR-1", "560000");
            assertThatThrownBy(() -> loadCar(uow, "CAR-2", "10000")).isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void loadingPastTheToleranceIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> loadCar(uow, "CAR-1", "600000")).isInstanceOf(ShippingError.class);
        }
    }

    @Test
    void loadingOutsideTheWindowIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(
                            () -> Shipping.loadOut(uow, "S-2024-0088", "CAR-1", d("196000"), LocalDate.of(2025, 3, 1)))
                    .isInstanceOf(ShippingError.class);
        }
    }

    @Test
    void aLoadOfNothingIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> loadCar(uow, "CAR-1", "0")).isInstanceOf(ShippingError.class);
        }
    }

    @Test
    void theSameReferenceTwiceIsAConflict() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadCar(uow, "CAR-1", "196000");
            assertThatThrownBy(() -> loadCar(uow, "CAR-1", "196000")).isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void theBuyersSettlementPostsTheDestinationWeight() {
        ShipmentRow shipment;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadCar(uow, "CAR-1", "196000");
            shipment = settleCarOne(uow, LocalDate.of(2024, 11, 26));
            uow.commit();
        }
        assertThat(shipment.isSettled()).isTrue();
        assertThat(shipment.getDestinationGrade()).contains("2");
        assertThat(shipment.getTransitShrink()).contains(d("12.0000"));
    }

    @Test
    void settlingTwiceIsAConflict() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadCar(uow, "CAR-1", "196000");
            settleCarOne(uow, LocalDate.of(2024, 11, 26));
            assertThatThrownBy(() -> settleCarOne(uow, LocalDate.of(2024, 11, 27)))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void settlingAShipmentNobodyLoadedIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Shipping.settleShipment(
                            uow, "S-2024-0088", "CAR-9", d("195328"), "2", d("16730.00"), LocalDate.of(2024, 11, 26)))
                    .isInstanceOf(SaleNotFound.class);
        }
    }

    @Test
    void aRejectedCarComesBackOntoTheSale() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadCar(uow, "CAR-1", "560000");
            assertThat(Shipping.sale(uow, "S-2024-0088").getStatus()).isEqualTo("filled");
            Shipping.rejectShipment(uow, "S-2024-0088", "CAR-1", "16.8 moisture at destination");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow row = Shipping.sale(uow, "S-2024-0088");
            assertThat(row.getStatus()).isEqualTo("open");
            assertThat(row.getShipped()).isEqualTo(d("0.0000"));
        }
    }

    @Test
    void rejectingTwiceIsAConflict() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadCar(uow, "CAR-1", "196000");
            Shipping.rejectShipment(uow, "S-2024-0088", "CAR-1", "wet");
            assertThatThrownBy(() -> Shipping.rejectShipment(uow, "S-2024-0088", "CAR-1", "wet again"))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void aRejectionNeedsAReason() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadCar(uow, "CAR-1", "196000");
            assertThatThrownBy(() -> Shipping.rejectShipment(uow, "S-2024-0088", "CAR-1", ""))
                    .isInstanceOf(ShippingError.class);
        }
    }

    @Test
    void shippedBushelsIgnoresRejectedCars() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadCar(uow, "CAR-1", "196000");
            loadCar(uow, "CAR-2", "196000");
            Shipping.rejectShipment(uow, "S-2024-0088", "CAR-2", "wet");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Shipping.shippedBushels(uow, "CORN", LocalDate.of(2024, 12, 1)))
                    .isEqualTo(d("3500.0000"));
        }
    }

    @Test
    void soldForwardIsWhatHasNotGoneOut() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Shipping.soldForward(uow, "CORN", SHIPPED)).isEqualTo(d("10000.0000"));
            loadCar(uow, "CAR-1", "196000");
            assertThat(Shipping.soldForward(uow, "CORN", SHIPPED)).isEqualTo(d("6500.0000"));
        }
    }

    @Test
    void transitLossAddsTheSettledCars() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadCar(uow, "CAR-1", "196000");
            settleCarOne(uow, LocalDate.of(2024, 11, 26));
            assertThat(Shipping.transitLoss(uow, "S-2024-0088")).isEqualTo(d("12.0000"));
        }
    }

    @Test
    void anUnsettledCarContributesNoTransitLoss() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadCar(uow, "CAR-1", "196000");
            assertThat(Shipping.transitLoss(uow, "S-2024-0088")).isEqualTo(d("0.0000"));
        }
    }

    @Test
    void freightIsOwedOnWhatWentOut() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadCar(uow, "CAR-1", "196000");
            BigDecimal owed = Shipping.freightOwed(uow, "S-2024-0088");
            assertThat(owed).isEqualTo(d("630.00"));
        }
    }
}
