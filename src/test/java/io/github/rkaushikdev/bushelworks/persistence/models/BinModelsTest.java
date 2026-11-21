package io.github.rkaushikdev.bushelworks.persistence.models;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.persistence.TestDatabase.assertIntegrityError;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Bins, what is in them, and the cable readings hanging off them. */
class BinModelsTest {

    static final OffsetDateTime TAKEN = OffsetDateTime.of(2024, 11, 27, 6, 0, 0, 0, ZoneOffset.UTC);

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    static BinRow aBin() {
        return aBin(bin -> {});
    }

    static BinRow aBin(Consumer<BinRow> overrides) {
        BinRow bin = new BinRow();
        bin.setBinId("B-04");
        bin.setLocationCode("HOME");
        bin.setCapacityBushels(d("100000"));
        bin.setAerated(true);
        overrides.accept(bin);
        return bin;
    }

    static BinReadingRow aReading() {
        return aReading(reading -> {});
    }

    static BinReadingRow aReading(Consumer<BinReadingRow> overrides) {
        BinReadingRow reading = new BinReadingRow();
        reading.setTakenAt(TAKEN);
        reading.setCable(1);
        reading.setTemperatureC(d("12.4"));
        reading.setMoisture(d("14.2"));
        reading.setFanRunning(true);
        overrides.accept(reading);
        return reading;
    }

    private BinRow storedBin(UnitOfWork uow) {
        return TestDatabase.first(uow.session(), BinRow.class).orElseThrow();
    }

    @Test
    void theBinTablesExist() {
        assertThat(database.tableNames()).contains("bin", "bin_reading");
    }

    @Test
    void aBinRoundTrips() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aBin());
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinRow found = storedBin(uow);
            assertThat(found.isEmpty()).isTrue();
            assertThat(found.getHeadroom()).isEqualTo(d("100000.0000"));
            assertThat(found.isOutOfService()).isFalse();
            assertThat(found).hasToString("HOME/B-04");
        }
    }

    @Test
    void aBinIdIsUniquePerSite() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aBin());
            uow.session().persist(aBin(bin -> bin.setLocationCode("LURAY")));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aBin());
                uow.commit();
            });
        }
    }

    @Test
    void aBinWithNoCapacityIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aBin(bin -> bin.setCapacityBushels(d("0"))));
                uow.commit();
            });
        }
    }

    @Test
    void moreGrainThanTheBinHoldsIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aBin(bin -> {
                    bin.setBushels(d("200000"));
                    bin.setCommodityCode("CORN");
                }));
                uow.commit();
            });
        }
    }

    @Test
    void negativeContentsAreRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aBin(bin -> bin.setBushels(d("-1"))));
                uow.commit();
            });
        }
    }

    @Test
    void aFilledBinReportsItsHeadroom() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aBin(bin -> {
                bin.setBushels(d("40000"));
                bin.setCommodityCode("CORN");
            }));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinRow found = storedBin(uow);
            assertThat(found.isEmpty()).isFalse();
            assertThat(found.getHeadroom()).isEqualTo(d("60000.0000"));
        }
    }

    @Test
    void aBinKeepsTheBlendItHolds() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aBin(bin -> {
                bin.setBushels(d("40000"));
                bin.setCommodityCode("CORN");
                bin.setMoisture(d("14.6"));
                bin.setTestWeight(d("56.2"));
            }));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinRow found = storedBin(uow);
            assertThat(found.getMoisture()).contains(d("14.6000"));
            assertThat(found.getTestWeight()).contains(d("56.2000"));
        }
    }

    @Test
    void readingsHangOffTheBinInTimeOrder() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinRow bin = aBin();
            bin.setReadings(List.of(aReading(reading -> reading.setTakenAt(TAKEN.plusDays(1))), aReading()));
            uow.session().persist(bin);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            List<OffsetDateTime> taken = storedBin(uow).getReadings().stream()
                    .map(BinReadingRow::getTakenAt)
                    .toList();
            assertThat(taken).hasSize(2).isSorted();
            assertThat(taken.get(0)).isAtSameInstantAs(TAKEN);
        }
    }

    @Test
    void aWarmReadingIsFlagged() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinRow bin = aBin();
            bin.setReadings(List.of(aReading(reading -> reading.setTemperatureC(d("24.0")))));
            uow.session().persist(bin);
            uow.commit();
            assertThat(bin.getReadings().get(0).isWarm()).isTrue();
        }
    }

    @Test
    void aCoolReadingIsNotFlagged() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinRow bin = aBin();
            bin.setReadings(List.of(aReading()));
            uow.session().persist(bin);
            uow.commit();
            assertThat(bin.getReadings().get(0).isWarm()).isFalse();
        }
    }

    @Test
    void anImpossibleTemperatureIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinRow bin = aBin();
            bin.setReadings(List.of(aReading(reading -> reading.setTemperatureC(d("120")))));
            assertIntegrityError(() -> {
                uow.session().persist(bin);
                uow.commit();
            });
        }
    }

    @Test
    void severalCablesCanBeReadAtOnce() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinRow bin = aBin();
            bin.setReadings(Stream.of(1, 2, 3)
                    .map(cable -> aReading(reading -> reading.setCable(cable)))
                    .toList());
            uow.session().persist(bin);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(storedBin(uow).getReadings())
                    .extracting(BinReadingRow::getCable)
                    .containsExactlyInAnyOrder(1, 2, 3);
        }
    }

    @Test
    void deletingABinTakesItsReadings() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinRow bin = aBin();
            bin.setReadings(List.of(aReading()));
            uow.session().persist(bin);
            uow.commit();
            uow.session().remove(bin);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(TestDatabase.all(uow.session(), BinReadingRow.class)).isEmpty();
        }
    }

    @Test
    void aBinOutOfServiceKeepsTheReason() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aBin(bin -> {
                bin.setOutOfService(true);
                bin.setOutOfServiceReason("fan motor");
            }));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            BinRow found = storedBin(uow);
            assertThat(found.isOutOfService()).isTrue();
            assertThat(found.getOutOfServiceReason()).contains("fan motor");
        }
    }
}
