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

/** Storage lots, the bushels withdrawn from them, and the receipts written against them. */
class StorageModelsTest {

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    static StorageLotRow aLot() {
        return aLot(lot -> {});
    }

    static StorageLotRow aLot(Consumer<StorageLotRow> overrides) {
        StorageLotRow lot = new StorageLotRow();
        lot.setLotId("L-2024-0031");
        lot.setPartyCode("MERRICK");
        lot.setCommodityCode("CORN");
        lot.setLocationCode("HOME");
        lot.setBushels(d("8000"));
        lot.setStoredOn(LocalDate.of(2024, 10, 3));
        overrides.accept(lot);
        return lot;
    }

    static WithdrawalRow aWithdrawal(String bushels, LocalDate on) {
        WithdrawalRow withdrawal = new WithdrawalRow();
        withdrawal.setBushels(d(bushels));
        withdrawal.setWithdrawnOn(on);
        return withdrawal;
    }

    static WarehouseReceiptRow aReceipt(String number, StorageLotRow lot) {
        WarehouseReceiptRow receipt = new WarehouseReceiptRow();
        receipt.setReceiptNumber(number);
        receipt.setLotRowId(lot.getId());
        receipt.setPartyCode("MERRICK");
        receipt.setCommodityCode("CORN");
        receipt.setLocationCode("HOME");
        receipt.setBushels(d("8000"));
        receipt.setGrade("2");
        receipt.setIssuedOn(LocalDate.of(2024, 10, 4));
        return receipt;
    }

    @Test
    void theStorageTablesExist() {
        assertThat(database.tableNames()).contains("storage_lot", "storage_withdrawal", "warehouse_receipt");
    }

    @Test
    void aLotRoundTrips() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aLot());
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            StorageLotRow found =
                    TestDatabase.first(uow.session(), StorageLotRow.class).orElseThrow();
            assertThat(found.getOpenBushels()).isEqualTo(d("8000.0000"));
            assertThat(found.getAccruedToDate()).isEqualTo(d("0.00"));
            assertThat(found.isDeferredPrice()).isFalse();
            assertThat(found).hasToString("L-2024-0031 MERRICK CORN");
        }
    }

    @Test
    void aLotIdIsUnique() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aLot());
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aLot());
                uow.commit();
            });
        }
    }

    @Test
    void aLotOfNoBushelsIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aLot(lot -> lot.setBushels(d("0"))));
                uow.commit();
            });
        }
    }

    @Test
    void withdrawalsComeOffTheOpenBalance() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            StorageLotRow lot = aLot();
            lot.setWithdrawals(List.of(
                    aWithdrawal("1200", LocalDate.of(2024, 11, 1)), aWithdrawal("800", LocalDate.of(2024, 12, 1))));
            uow.session().persist(lot);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            StorageLotRow found =
                    TestDatabase.first(uow.session(), StorageLotRow.class).orElseThrow();
            assertThat(found.getWithdrawn()).isEqualTo(d("2000.0000"));
            assertThat(found.getOpenBushels()).isEqualTo(d("6000.0000"));
        }
    }

    @Test
    void aWithdrawalOfNothingIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            StorageLotRow lot = aLot();
            lot.setWithdrawals(List.of(aWithdrawal("0", LocalDate.of(2024, 11, 1))));
            assertIntegrityError(() -> {
                uow.session().persist(lot);
                uow.commit();
            });
        }
    }

    @Test
    void aWithdrawalRecordsWhyAndAgainstWhichSettlement() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            StorageLotRow lot = aLot();
            WithdrawalRow withdrawal = aWithdrawal("1200", LocalDate.of(2024, 11, 1));
            withdrawal.setReason("settled");
            withdrawal.setSettlementNumber("S-2024-0417");
            lot.setWithdrawals(List.of(withdrawal));
            uow.session().persist(lot);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            WithdrawalRow withdrawal =
                    TestDatabase.first(uow.session(), WithdrawalRow.class).orElseThrow();
            assertThat(withdrawal.getSettlementNumber()).contains("S-2024-0417");
            assertThat(withdrawal.getReason()).isEqualTo("settled");
        }
    }

    @Test
    void deletingALotTakesItsWithdrawals() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            StorageLotRow lot = aLot();
            lot.setWithdrawals(List.of(aWithdrawal("100", LocalDate.of(2024, 11, 1))));
            uow.session().persist(lot);
            uow.commit();
            uow.session().remove(lot);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(TestDatabase.all(uow.session(), WithdrawalRow.class)).isEmpty();
        }
    }

    @Test
    void aReceiptIsLiveUntilItIsCancelled() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            StorageLotRow lot = aLot();
            uow.session().persist(lot);
            uow.flush();
            uow.session().persist(aReceipt("WR-004417", lot));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            WarehouseReceiptRow receipt =
                    TestDatabase.first(uow.session(), WarehouseReceiptRow.class).orElseThrow();
            assertThat(receipt.isLive()).isTrue();
            receipt.setCancelledOn(LocalDate.of(2025, 3, 1));
            uow.commit();
            assertThat(receipt.isLive()).isFalse();
        }
    }

    @Test
    void aReceiptNumberIsUnique() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            StorageLotRow lot = aLot();
            uow.session().persist(lot);
            uow.flush();
            assertIntegrityError(() -> {
                for (int copy = 0; copy < 2; copy++) {
                    uow.session().persist(aReceipt("WR-004417", lot));
                }
                uow.commit();
            });
        }
    }

    @Test
    void aReceiptCanCarryALienholder() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            StorageLotRow lot = aLot();
            uow.session().persist(lot);
            uow.flush();
            WarehouseReceiptRow receipt = aReceipt("WR-004418", lot);
            receipt.setLienholder("Farmers State Bank");
            uow.session().persist(receipt);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(TestDatabase.first(uow.session(), WarehouseReceiptRow.class)
                            .orElseThrow()
                            .getLienholder())
                    .contains("Farmers State Bank");
        }
    }
}
