package io.github.rkaushikdev.bushelworks.domain.storage;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StorageTest {

    static final StorageRate DAILY = StorageRate.of("CORN", d("0.0450"))
            .withMethod(AccrualMethod.DAILY)
            .withFreeDays(30)
            .withInChargePerBushel(d("0.0900"));

    static final StorageRate ANNIVERSARY = StorageRate.of("CORN", d("0.0450"))
            .withMethod(AccrualMethod.ANNIVERSARY)
            .withFreeDays(30);

    static final StorageRate CALENDAR = StorageRate.of("CORN", d("0.0450"))
            .withMethod(AccrualMethod.CALENDAR)
            .withFreeDays(0);

    static final LocalDate RECEIVED = LocalDate.of(2024, 10, 15);

    static LocalDate day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth);
    }

    static StorageLot lot(String lotId, String bushels, LocalDate storedOn) {
        return new StorageLot(lotId, "MERRICK", "CORN", "HOME", d(bushels), storedOn);
    }

    @Test
    void nothingAccruesInsideTheFreePeriod() {
        assertThat(DAILY.chargeableDays(RECEIVED, day(2024, 11, 14))).isZero();
        assertThat(DAILY.charge(d("10000"), RECEIVED, day(2024, 11, 14))).isEqualTo(d("0.00"));
    }

    @Test
    void theFreePeriodComesOffTheFront() {
        assertThat(DAILY.chargeableDays(RECEIVED, day(2024, 12, 14))).isEqualTo(30);
    }

    @Test
    void dailyAccrualProratesTheMonthlyRate() {
        assertThat(DAILY.charge(d("10000"), RECEIVED, day(2024, 12, 14))).isEqualTo(d("450.00"));
    }

    @Test
    void dailyAccrualChargesAPartMonth() {
        assertThat(DAILY.charge(d("10000"), RECEIVED, day(2024, 11, 29))).isEqualTo(d("225.00"));
    }

    @Test
    void anniversaryAccrualRoundsAPartMonthUp() {
        assertThat(ANNIVERSARY.chargeableMonths(RECEIVED, day(2024, 11, 29))).isEqualByComparingTo(d("1"));
        assertThat(ANNIVERSARY.chargeableMonths(RECEIVED, day(2024, 12, 15))).isEqualByComparingTo(d("2"));
    }

    @Test
    void oneDayPastTheAnniversaryCostsAWholeMonth() {
        assertThat(ANNIVERSARY.charge(d("10000"), RECEIVED, day(2024, 12, 14))).isEqualTo(d("450.00"));
        assertThat(ANNIVERSARY.charge(d("10000"), RECEIVED, day(2024, 12, 15))).isEqualTo(d("900.00"));
    }

    @Test
    void calendarAccrualChargesEveryMonthTouched() {
        assertThat(CALENDAR.chargeableMonths(day(2024, 10, 31), day(2024, 11, 1)))
                .isEqualByComparingTo(d("2"));
    }

    @Test
    void calendarAccrualChargesOneMonthForASingleDay() {
        assertThat(CALENDAR.chargeableMonths(day(2024, 10, 1), day(2024, 10, 2)))
                .isEqualByComparingTo(d("1"));
    }

    @Test
    void storageCannotBeChargedBackwards() {
        assertThatThrownBy(() -> DAILY.chargeableDays(RECEIVED, day(2024, 10, 1)))
                .isInstanceOf(StorageError.class);
    }

    @Test
    void minimumChargeAppliesOnlyOnceSomethingIsOwed() {
        StorageRate rate = StorageRate.of("CORN", d("0.0450")).withMinimumCharge(d("35.00"));
        assertThat(rate.charge(d("10"), day(2024, 10, 15), day(2024, 10, 15))).isEqualTo(d("0.00"));
        assertThat(rate.charge(d("10"), day(2024, 10, 15), day(2024, 11, 15))).isEqualTo(d("35.00"));
    }

    @Test
    void theInChargeIsAFlatPerBushelFee() {
        assertThat(DAILY.inCharge(d("10000"))).isEqualTo(d("900.00"));
    }

    @Test
    void aNegativeRateIsRefused() {
        assertThatThrownBy(() -> StorageRate.of("CORN", d("-0.01"))).isInstanceOf(StorageError.class);
    }

    @Test
    void aFirstPeriodChargesFromReceipt() {
        assertThat(DAILY.chargeBetween(d("10000"), RECEIVED, Optional.empty(), day(2024, 12, 14)))
                .isEqualTo(d("450.00"));
    }

    @Test
    void aLaterPeriodChargesOnlyItsOwnDays() {
        assertThat(DAILY.chargeBetween(d("10000"), RECEIVED, Optional.of(day(2024, 12, 14)), day(2025, 1, 13)))
                .isEqualTo(d("450.00"));
    }

    @Test
    void aPeriodThatHasAlreadyBeenBilledChargesNothing() {
        assertThat(DAILY.chargeBetween(d("10000"), RECEIVED, Optional.of(day(2024, 12, 14)), day(2024, 12, 14)))
                .isEqualTo(d("0.00"));
        assertThat(DAILY.chargeBetween(d("10000"), RECEIVED, Optional.of(day(2024, 12, 14)), day(2024, 12, 1)))
                .isEqualTo(d("0.00"));
    }

    @Test
    void aPeriodWhollyInsideTheFreeDaysChargesNothing() {
        assertThat(DAILY.chargeBetween(d("10000"), RECEIVED, Optional.of(day(2024, 10, 20)), day(2024, 11, 1)))
                .isEqualTo(d("0.00"));
    }

    @Test
    void bushelsLeavingBetweenRunsDoNotEraseWhatWasBilled() {
        // Billed 10000 bushels to 14 December, then half the lot goes out. The
        // next run charges the remaining 5000 for its own thirty days and does not
        // go back and recompute the first period at the smaller balance.
        assertThat(DAILY.chargeBetween(d("5000"), RECEIVED, Optional.of(day(2024, 12, 14)), day(2025, 1, 13)))
                .isEqualTo(d("225.00"));
    }

    @Test
    void theAnniversaryMethodCountsWholeMonthsPerPeriod() {
        assertThat(ANNIVERSARY.chargeBetween(d("10000"), RECEIVED, Optional.empty(), day(2024, 12, 14)))
                .isEqualTo(d("450.00"));
        assertThat(ANNIVERSARY.chargeBetween(d("10000"), RECEIVED, Optional.of(day(2024, 12, 14)), day(2025, 1, 13)))
                .isEqualTo(d("450.00"));
    }

    @Test
    void chargingAPeriodOnNegativeBushelsIsRefused() {
        assertThatThrownBy(() -> DAILY.chargeBetween(d("-1"), RECEIVED, Optional.empty(), day(2024, 12, 14)))
                .isInstanceOf(StorageError.class);
    }

    @Test
    void aLotTracksWhatIsLeftOpen() {
        StorageLot small = lot("L-1", "5000", RECEIVED).withdraw(d("1200"));
        assertThat(small.openBushels()).isEqualTo(d("3800.0000"));
        assertThat(small.isEmpty()).isFalse();
    }

    @Test
    void withdrawingTheWholeLotEmptiesIt() {
        StorageLot empty = lot("L-1", "5000", RECEIVED).withdraw(d("5000"));
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.openBushels()).isEqualTo(d("0.0000"));
    }

    @Test
    void cannotWithdrawMoreThanIsOpen() {
        assertThatThrownBy(() -> lot("L-1", "5000", RECEIVED).withdraw(d("5001")))
                .isInstanceOf(StorageError.class);
    }

    @Test
    void cannotWithdrawNothing() {
        assertThatThrownBy(() -> lot("L-1", "5000", RECEIVED).withdraw(d("0"))).isInstanceOf(StorageError.class);
    }

    @Test
    void aLotAccruesOnWhatIsStillOpen() {
        StorageLot partial = lot("L-1", "10000", RECEIVED).withdraw(d("5000"));
        assertThat(partial.accrued(DAILY, day(2024, 12, 14))).isEqualTo(d("225.00"));
    }

    @Test
    void drawDownTakesTheOldestLotFirst() {
        List<StorageLot> lots = List.of(lot("L-1", "3000", day(2024, 9, 1)), lot("L-2", "4000", day(2024, 10, 1)));
        StorageLot.DrawDown result = StorageLot.drawDown(lots, d("3500"));
        assertThat(result.taken())
                .containsExactly(
                        new StorageLot.Taken("L-1", d("3000.0000")), new StorageLot.Taken("L-2", d("500.0000")));
        assertThat(result.lots().get(0).isEmpty()).isTrue();
        assertThat(result.lots().get(1).openBushels()).isEqualTo(d("3500.0000"));
    }

    @Test
    void drawDownCanBeAskedForNewestFirst() {
        List<StorageLot> lots = List.of(lot("L-1", "3000", day(2024, 9, 1)), lot("L-2", "4000", day(2024, 10, 1)));
        StorageLot.DrawDown result = StorageLot.drawDown(lots, d("1000"), false);
        assertThat(result.taken()).containsExactly(new StorageLot.Taken("L-2", d("1000.0000")));
    }

    @Test
    void drawDownRefusesToOverdraw() {
        List<StorageLot> lots = List.of(lot("L-1", "3000", day(2024, 9, 1)));
        assertThatThrownBy(() -> StorageLot.drawDown(lots, d("3001"))).isInstanceOf(StorageError.class);
    }

    @Test
    void drawDownOfNothingIsAnError() {
        assertThatThrownBy(() -> StorageLot.drawDown(List.of(lot("L-1", "3000", day(2024, 9, 1))), d("0")))
                .isInstanceOf(StorageError.class);
    }

    @Test
    void aLotNeedsBushels() {
        assertThatThrownBy(() -> lot("L-1", "0", RECEIVED)).isInstanceOf(StorageError.class);
    }
}
