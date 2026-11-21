package io.github.rkaushikdev.bushelworks.services.warehousing;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.StorageLotRow;
import io.github.rkaushikdev.bushelworks.persistence.models.WarehouseReceiptRow;
import io.github.rkaushikdev.bushelworks.persistence.models.WithdrawalRow;
import io.github.rkaushikdev.bushelworks.services.houses.Houses;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WarehousingTest {

    static final LocalDate STORED = LocalDate.of(2024, 10, 3);

    Database database;
    RuleBook house;

    /** MERRICK has 8,000 and 4,000 bushels of corn in storage at HOME, HOLLIS has 2,000. */
    @BeforeEach
    void stocked() {
        database = TestDatabase.fresh();
        house = Houses.houseRulebook();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.store(uow, "L-1", "MERRICK", "CORN", "HOME", d("8000"), STORED);
            Warehousing.store(uow, "L-2", "MERRICK", "CORN", "HOME", d("4000"), LocalDate.of(2024, 10, 20));
            Warehousing.store(uow, "L-3", "HOLLIS", "CORN", "HOME", d("2000"), STORED);
            uow.commit();
        }
    }

    static Map<String, BigDecimal> charged(AccrualRun run) {
        return run.lines().stream().collect(Collectors.toMap(AccrualLine::lotId, AccrualLine::dollars));
    }

    static List<String> lotIds(List<StorageLotRow> rows) {
        return rows.stream().map(StorageLotRow::getLotId).toList();
    }

    void accrueAndCommit(LocalDate through) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.accrue(uow, house, through);
            uow.commit();
        }
    }

    @Test
    void aLotOpensWithEverythingStillInIt() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Warehousing.lot(uow, "L-1").getOpenBushels()).isEqualTo(d("8000.0000"));
        }
    }

    @Test
    void aMissingLotSaysSo() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Warehousing.lot(uow, "L-99")).isInstanceOf(LotNotFound.class);
        }
    }

    @Test
    void openingTheSameLotTwiceIsAConflict() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Warehousing.store(uow, "L-1", "MERRICK", "CORN", "HOME", d("100"), STORED))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void aLotOfNothingIsRefused() {
        Database empty = TestDatabase.fresh();
        try (UnitOfWork uow = UnitOfWork.open(empty)) {
            assertThatThrownBy(() -> Warehousing.store(uow, "L-9", "MERRICK", "CORN", "HOME", d("0"), STORED))
                    .isInstanceOf(StorageServiceError.class);
        }
    }

    @Test
    void openLotsAreFilteredByProducer() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(lotIds(Warehousing.openLots(uow, "MERRICK"))).containsExactly("L-1", "L-2");
            assertThat(lotIds(Warehousing.openLots(uow, "HOLLIS"))).containsExactly("L-3");
        }
    }

    @Test
    void withdrawingTakesTheOldestLotFirst() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.withdraw(uow, "MERRICK", "CORN", d("9000"), LocalDate.of(2024, 11, 1));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Warehousing.lot(uow, "L-1").getOpenBushels()).isEqualTo(d("0.0000"));
            assertThat(Warehousing.lot(uow, "L-2").getOpenBushels()).isEqualTo(d("3000.0000"));
        }
    }

    @Test
    void anEmptiedLotClosesItself() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.withdraw(uow, "MERRICK", "CORN", d("8000"), LocalDate.of(2024, 11, 1));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Warehousing.lot(uow, "L-1").getClosedOn()).contains(LocalDate.of(2024, 11, 1));
            assertThat(lotIds(Warehousing.openLots(uow, "MERRICK"))).containsExactly("L-2");
        }
    }

    @Test
    void withdrawingMoreThanIsThereIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(
                            () -> Warehousing.withdraw(uow, "MERRICK", "CORN", d("12001"), LocalDate.of(2024, 11, 1)))
                    .isInstanceOf(StorageServiceError.class);
        }
    }

    @Test
    void withdrawingNothingIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Warehousing.withdraw(uow, "MERRICK", "CORN", d("0"), LocalDate.of(2024, 11, 1)))
                    .isInstanceOf(StorageServiceError.class);
        }
    }

    @Test
    void aWithdrawalRecordsTheSettlementItPaid() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            List<WithdrawalRow> written = Warehousing.withdraw(
                    uow,
                    "MERRICK",
                    "CORN",
                    d("1000"),
                    LocalDate.of(2024, 11, 1),
                    WithdrawalOptions.defaults().withSettlementNumber("S-2024-0417"));
            assertThat(written.get(0).getSettlementNumber()).contains("S-2024-0417");
        }
    }

    @Test
    void whatIsOnHandIsTheOpenBalance() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Warehousing.onHand(uow, "CORN")).isEqualTo(d("14000.0000"));
            Warehousing.withdraw(uow, "MERRICK", "CORN", d("2000"), LocalDate.of(2024, 11, 1));
            assertThat(Warehousing.onHand(uow, "CORN")).isEqualTo(d("12000.0000"));
        }
    }

    @Test
    void theFirstAccrualChargesFromReceipt() {
        AccrualRun run;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            run = Warehousing.accrue(uow, house, LocalDate.of(2024, 12, 14));
            uow.commit();
        }
        // 8000 bu, 72 days on hand, 30 of them free, so 42 days at 0.045 a month
        assertThat(charged(run).get("L-1")).isEqualTo(d("504.00"));
        assertThat(run.total()).isPositive();
    }

    @Test
    void aLotStillInsideItsFreePeriodAccruesNothing() {
        AccrualRun run;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            run = Warehousing.accrue(uow, house, LocalDate.of(2024, 11, 1));
        }
        assertThat(charged(run).get("L-2")).isEqualTo(d("0.00"));
    }

    @Test
    void runningTheAccrualTwiceChargesNothingTheSecondTime() {
        accrueAndCommit(LocalDate.of(2024, 12, 14));
        AccrualRun second;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            second = Warehousing.accrue(uow, house, LocalDate.of(2024, 12, 14));
        }
        assertThat(second.lines()).isEmpty();
    }

    @Test
    void aLaterRunChargesOnlyTheTimeSince() {
        accrueAndCommit(LocalDate.of(2024, 12, 14));
        AccrualRun second;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            second = Warehousing.accrue(uow, house, LocalDate.of(2025, 1, 13));
            uow.commit();
        }
        assertThat(charged(second).get("L-1")).isEqualTo(d("360.00"));
    }

    @Test
    void theAccrualAccumulatesOnTheLot() {
        accrueAndCommit(LocalDate.of(2024, 12, 14));
        accrueAndCommit(LocalDate.of(2025, 1, 13));
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Warehousing.lot(uow, "L-1").getAccruedToDate()).isEqualTo(d("864.00"));
            assertThat(Warehousing.lot(uow, "L-1").getBilledThrough()).contains(LocalDate.of(2025, 1, 13));
        }
    }

    /**
     * The regression that made the house give storage away.
     *
     * <p>Bill the lot, take half of it out, bill again. The second run has to charge the remaining
     * balance for the new period. It used to compute the whole charge from receipt on the smaller
     * balance, subtract what it had already billed on the larger one, get a negative number and floor
     * it to nothing.
     */
    @Test
    void bushelsLeavingBetweenRunsDoNotReduceWhatWasBilled() {
        accrueAndCommit(LocalDate.of(2024, 12, 14));
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.withdraw(uow, "MERRICK", "CORN", d("4000"), LocalDate.of(2024, 12, 15));
            uow.commit();
        }
        AccrualRun run;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            run = Warehousing.accrue(uow, house, LocalDate.of(2025, 1, 13));
            uow.commit();
        }
        assertThat(charged(run).get("L-1")).isEqualTo(d("180.00"));
    }

    @Test
    void theEarlierBillingIsStillOnTheLotAfterwards() {
        accrueAndCommit(LocalDate.of(2024, 12, 14));
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.withdraw(uow, "MERRICK", "CORN", d("4000"), LocalDate.of(2024, 12, 15));
            Warehousing.accrue(uow, house, LocalDate.of(2025, 1, 13));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Warehousing.lot(uow, "L-1").getAccruedToDate()).isEqualTo(d("684.00"));
        }
    }

    @Test
    void aDryRunDoesNotMoveTheBilledThrough() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.accrue(uow, house, LocalDate.of(2024, 12, 14), null, false);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Warehousing.lot(uow, "L-1").getBilledThrough()).isEmpty();
        }
    }

    @Test
    void theRunCanBeSplitByProducer() {
        AccrualRun run;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            run = Warehousing.accrue(uow, house, LocalDate.of(2024, 12, 14));
        }
        assertThat(run.forParty("MERRICK")).extracting(AccrualLine::lotId).containsExactlyInAnyOrder("L-1", "L-2");
        assertThat(run.forParty("HOLLIS")).extracting(AccrualLine::lotId).containsExactlyInAnyOrder("L-3");
    }

    @Test
    void aReceiptIsWrittenAgainstAnOpenLot() {
        WarehouseReceiptRow receipt;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            receipt = Warehousing.issueReceipt(uow, "WR-0001", "L-1", "2", LocalDate.of(2024, 10, 4));
            uow.commit();
        }
        assertThat(receipt.getBushels()).isEqualTo(d("8000.0000"));
        assertThat(receipt.isLive()).isTrue();
    }

    @Test
    void aLotCarriesOneLiveReceiptAtATime() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.issueReceipt(uow, "WR-0001", "L-1", "2", LocalDate.of(2024, 10, 4));
            assertThatThrownBy(() -> Warehousing.issueReceipt(uow, "WR-0002", "L-1", "2", LocalDate.of(2024, 10, 5)))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void aCancelledReceiptFreesTheLot() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.issueReceipt(uow, "WR-0001", "L-1", "2", LocalDate.of(2024, 10, 4));
            Warehousing.cancelReceipt(uow, "WR-0001", LocalDate.of(2025, 3, 1));
            assertThatCode(() -> {
                        Warehousing.issueReceipt(uow, "WR-0002", "L-1", "2", LocalDate.of(2025, 3, 2));
                        uow.commit();
                    })
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void cancellingTwiceIsAConflict() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.issueReceipt(uow, "WR-0001", "L-1", "2", LocalDate.of(2024, 10, 4));
            Warehousing.cancelReceipt(uow, "WR-0001", LocalDate.of(2025, 3, 1));
            assertThatThrownBy(() -> Warehousing.cancelReceipt(uow, "WR-0001", LocalDate.of(2025, 3, 2)))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void anEmptyLotCannotCarryAReceipt() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Warehousing.withdraw(uow, "HOLLIS", "CORN", d("2000"), LocalDate.of(2024, 11, 1));
            assertThatThrownBy(() -> Warehousing.issueReceipt(uow, "WR-0003", "L-3", "2", LocalDate.of(2024, 11, 2)))
                    .isInstanceOf(StorageServiceError.class);
        }
    }
}
