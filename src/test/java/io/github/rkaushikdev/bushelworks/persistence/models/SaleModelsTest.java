package io.github.rkaushikdev.bushelworks.persistence.models;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.persistence.TestDatabase.assertIntegrityError;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Sales out of the house and the loads shipped against them. */
class SaleModelsTest {

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    static SaleRow aSale() {
        return aSale(sale -> {});
    }

    static SaleRow aSale(Consumer<SaleRow> overrides) {
        SaleRow sale = new SaleRow();
        sale.setNumber("S-2024-0088");
        sale.setKind("forward");
        sale.setBuyer("Salina Terminal");
        sale.setDestination("terminal");
        sale.setCommodityCode("CORN");
        sale.setOriginCode("HOME");
        sale.setWrittenOn(LocalDate.of(2024, 9, 5));
        sale.setBushels(d("50000"));
        sale.setWindowOpens(LocalDate.of(2024, 11, 1));
        sale.setWindowCloses(LocalDate.of(2024, 12, 31));
        sale.setFlatPrice(d("4.7800"));
        sale.setFreightPerBushel(d("0.1800"));
        sale.setSpecMaxMoisture(d("15.0"));
        sale.setSpecMinTestWeight(d("54.0"));
        sale.setSpecMaxDamage(d("5.0"));
        sale.setSpecMaxForeign(d("3.0"));
        overrides.accept(sale);
        return sale;
    }

    static ShipmentRow aShipment() {
        return aShipment(shipment -> {});
    }

    static ShipmentRow aShipment(Consumer<ShipmentRow> overrides) {
        ShipmentRow shipment = new ShipmentRow();
        shipment.setReference("CAR-4417");
        shipment.setShippedOn(LocalDate.of(2024, 11, 12));
        shipment.setCarrierId("BNSF 480221");
        shipment.setOriginPounds(d("196000"));
        shipment.setOriginBushels(d("3500.0000"));
        overrides.accept(shipment);
        return shipment;
    }

    @Test
    void theSaleTablesExist() {
        assertThat(database.tableNames()).contains("sale_contract", "shipment");
    }

    @Test
    void aSaleRoundTrips() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aSale());
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow found = TestDatabase.first(uow.session(), SaleRow.class).orElseThrow();
            assertThat(found.getStatus()).isEqualTo("open");
            assertThat(found.getCarrier()).isEqualTo("truck");
            assertThat(found.getShipped()).isEqualTo(d("0.0000"));
            assertThat(found.toString()).startsWith("S-2024-0088 Salina Terminal");
        }
    }

    @Test
    void aSaleNumberIsUnique() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aSale());
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aSale());
                uow.commit();
            });
        }
    }

    @Test
    void aSaleOfNoBushelsIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aSale(sale -> sale.setBushels(d("0"))));
                uow.commit();
            });
        }
    }

    @Test
    void negativeFreightIsRefusedByTheDatabase() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aSale(sale -> sale.setFreightPerBushel(d("-0.01"))));
                uow.commit();
            });
        }
    }

    @Test
    void aWindowThatClosesFirstIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aSale(sale -> {
                    sale.setWindowOpens(LocalDate.of(2024, 12, 31));
                    sale.setWindowCloses(LocalDate.of(2024, 11, 1));
                }));
                uow.commit();
            });
        }
    }

    @Test
    void aShipmentHangsOffItsSale() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow sale = aSale();
            sale.setShipments(List.of(aShipment()));
            uow.session().persist(sale);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow found = TestDatabase.first(uow.session(), SaleRow.class).orElseThrow();
            assertThat(found.getShipments()).hasSize(1);
            assertThat(found.getShipments().get(0).getCarrierId()).contains("BNSF 480221");
        }
    }

    @Test
    void aReferenceIsUniqueWithinASale() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow sale = aSale();
            sale.setShipments(List.of(aShipment(), aShipment()));
            assertIntegrityError(() -> {
                uow.session().persist(sale);
                uow.commit();
            });
        }
    }

    @Test
    void theSameReferenceOnAnotherSaleIsFine() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow first = aSale();
            first.setShipments(List.of(aShipment()));
            SaleRow second = aSale(sale -> sale.setNumber("S-2024-0089"));
            second.setShipments(List.of(aShipment()));
            uow.session().persist(first);
            uow.session().persist(second);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(TestDatabase.all(uow.session(), ShipmentRow.class)).hasSize(2);
        }
    }

    @Test
    void aShipmentOfNothingIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow sale = aSale();
            sale.setShipments(List.of(aShipment(shipment -> shipment.setOriginPounds(d("0")))));
            assertIntegrityError(() -> {
                uow.session().persist(sale);
                uow.commit();
            });
        }
    }

    @Test
    void aShipmentStartsUnsettledWithNoDestinationWeight() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow sale = aSale();
            sale.setShipments(List.of(aShipment()));
            uow.session().persist(sale);
            uow.commit();
            ShipmentRow shipment = sale.getShipments().get(0);
            assertThat(shipment.isSettled()).isFalse();
            assertThat(shipment.getTransitShrink()).isEmpty();
            assertThat(shipment.isRejected()).isFalse();
        }
    }

    @Test
    void transitShrinkAppearsOnceTheDestinationWeightDoes() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow sale = aSale();
            sale.setShipments(List.of(aShipment(shipment -> {
                shipment.setDestinationBushels(d("3488.0000"));
                shipment.setDestinationPounds(d("195328"));
                shipment.setDestinationGrade("2");
                shipment.setSettledOn(LocalDate.of(2024, 11, 26));
            })));
            uow.session().persist(sale);
            uow.commit();
            ShipmentRow shipment = sale.getShipments().get(0);
            assertThat(shipment.isSettled()).isTrue();
            assertThat(shipment.getTransitShrink()).contains(d("12.0000"));
        }
    }

    @Test
    void aDestinationWeighingHeavyGivesANegativeShrink() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow sale = aSale();
            sale.setShipments(List.of(aShipment(shipment -> shipment.setDestinationBushels(d("3510.0000")))));
            uow.session().persist(sale);
            uow.commit();
            assertThat(sale.getShipments().get(0).getTransitShrink()).contains(d("-10.0000"));
        }
    }

    @Test
    void aRejectedCarKeepsItsReason() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow sale = aSale();
            sale.setShipments(List.of(aShipment(shipment -> {
                shipment.setRejected(true);
                shipment.setRejectionReason("16.8 moisture at destination");
            })));
            uow.session().persist(sale);
            uow.commit();
            ShipmentRow shipment = sale.getShipments().get(0);
            assertThat(shipment.isRejected()).isTrue();
            assertThat(shipment.getRejectionReason())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("16.8"));
        }
    }

    @Test
    void deletingASaleTakesItsShipments() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SaleRow sale = aSale();
            sale.setShipments(List.of(aShipment()));
            uow.session().persist(sale);
            uow.commit();
            uow.session().remove(sale);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(TestDatabase.all(uow.session(), ShipmentRow.class)).isEmpty();
        }
    }
}
