package io.github.rkaushikdev.bushelworks.domain.grainbank;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GrainbankTest {

    static final LocalDate OPENED = LocalDate.of(2024, 10, 3);
    static final Map<String, Ration> RATIONS = Ration.standard();
    static final Ration CRACK = RATIONS.get("CRACK");
    static final Ration FINISH = RATIONS.get("FIN");

    static FeedAccount anAccount() {
        return FeedAccount.open("STROH", "CORN", "HOME", OPENED);
    }

    static FeedAccount converted() {
        return anAccount().deposit("A-1", d("1000"), OPENED).convertToStorage(LocalDate.of(2024, 12, 1));
    }

    @Test
    void theMillRunsThreeRations() {
        assertThat(RATIONS.keySet().stream().sorted()).containsExactly("CRACK", "FIN", "GROW");
    }

    @Test
    void anInclusionOutOfRangeIsRefused() {
        assertThatThrownBy(() -> new Ration("X", "X", d("0"), d("10.00"))).isInstanceOf(GrainBankError.class);
        assertThatThrownBy(() -> new Ration("X", "X", d("120"), d("10.00"))).isInstanceOf(GrainBankError.class);
    }

    @Test
    void negativeProcessingIsRefused() {
        assertThatThrownBy(() -> new Ration("X", "X", d("80"), d("-1"))).isInstanceOf(GrainBankError.class);
    }

    @Test
    void crackedCornTakesATonOfCornPlusTheMillShrink() {
        // 2000 lb of corn plus 0.5 percent, over 56 lb a bushel
        assertThat(CRACK.bushelsFor(d("1"))).isEqualTo(d("35.8929"));
        assertThat(Ration.MILL_SHRINK_PERCENT).isEqualByComparingTo(d("0.5"));
    }

    @Test
    void aLeanerRationTakesFewerBushels() {
        assertThat(FINISH.bushelsFor(d("1"))).isLessThan(CRACK.bushelsFor(d("1")));
    }

    @Test
    void makingNoFeedIsRefused() {
        assertThatThrownBy(() -> CRACK.bushelsFor(d("0"))).isInstanceOf(GrainBankError.class);
    }

    @Test
    void processingIsChargedByTheTon() {
        assertThat(FINISH.processingCharge(d("12"))).isEqualTo(d("150.00"));
    }

    @Test
    void aNewAccountIsEmpty() {
        FeedAccount account = anAccount();
        assertThat(account.balance()).isEqualTo(d("0.0000"));
        assertThat(account.isConverted()).isFalse();
        assertThat(account.charges()).isEqualTo(d("0.00"));
    }

    @Test
    void aDepositPutsBushelsIn() {
        FeedAccount account = anAccount().deposit("A-10441", d("932.8571"), OPENED);
        assertThat(account.balance()).isEqualTo(d("932.8571"));
        assertThat(account.movements().get(0).isDeposit()).isTrue();
    }

    @Test
    void aDepositOfNothingIsRefused() {
        assertThatThrownBy(() -> anAccount().deposit("A-1", d("0"), OPENED)).isInstanceOf(GrainBankError.class);
    }

    @Test
    void aWithdrawalTakesTheRationsBushelsOut() {
        FeedAccount after = anAccount()
                .deposit("A-1", d("1000"), OPENED)
                .withdraw("F-1", CRACK, d("10"), LocalDate.of(2024, 10, 10));
        assertThat(after.balance()).isEqualByComparingTo(d("1000.0000").subtract(CRACK.bushelsFor(d("10"))));
    }

    @Test
    void aWithdrawalRecordsTheTonsAndTheRation() {
        FeedAccount after = anAccount()
                .deposit("A-1", d("1000"), OPENED)
                .withdraw("F-1", FINISH, d("12"), LocalDate.of(2024, 10, 10));
        Movement movement = after.movements().get(after.movements().size() - 1);
        assertThat(movement.tons()).contains(d("12"));
        assertThat(movement.rationCode()).contains("FIN");
        assertThat(movement.isDeposit()).isFalse();
    }

    @Test
    void aWithdrawalCarriesTheProcessingCharge() {
        FeedAccount after = anAccount()
                .deposit("A-1", d("1000"), OPENED)
                .withdraw("F-1", FINISH, d("12"), LocalDate.of(2024, 10, 10));
        assertThat(after.charges()).isEqualTo(d("150.00"));
    }

    @Test
    void withdrawingMoreThanIsInTheAccountIsRefused() {
        FeedAccount account = anAccount().deposit("A-1", d("100"), OPENED);
        assertThatThrownBy(() -> account.withdraw("F-1", CRACK, d("10"), LocalDate.of(2024, 10, 10)))
                .isInstanceOf(GrainBankError.class);
    }

    @Test
    void theTonsTakenAddUp() {
        FeedAccount account = anAccount()
                .deposit("A-1", d("2000"), OPENED)
                .withdraw("F-1", CRACK, d("10"), LocalDate.of(2024, 10, 10))
                .withdraw("F-2", FINISH, d("12"), LocalDate.of(2024, 10, 24));
        assertThat(account.tonsTaken()).isEqualTo(d("22.000"));
    }

    @Test
    void activitySinceADateLeavesOutTheEarlierMovements() {
        FeedAccount account = anAccount()
                .deposit("A-1", d("2000"), OPENED)
                .withdraw("F-1", CRACK, d("10"), LocalDate.of(2024, 10, 10))
                .withdraw("F-2", CRACK, d("5"), LocalDate.of(2024, 11, 4));
        assertThat(account.activity(LocalDate.of(2024, 11, 1)))
                .extracting(Movement::reference)
                .containsExactly("F-2");
    }

    @Test
    void convertingClosesTheAccountToFeed() {
        FeedAccount account = converted();
        assertThat(account.isConverted()).isTrue();
        assertThat(account.convertedOn()).contains(LocalDate.of(2024, 12, 1));
    }

    @Test
    void aConvertedAccountTakesNoMoreGrain() {
        assertThatThrownBy(() -> converted().deposit("A-2", d("100"), LocalDate.of(2024, 12, 2)))
                .isInstanceOf(ConflictError.class);
    }

    @Test
    void aConvertedAccountGivesOutNoMoreFeed() {
        assertThatThrownBy(() -> converted().withdraw("F-1", CRACK, d("1"), LocalDate.of(2024, 12, 2)))
                .isInstanceOf(ConflictError.class);
    }

    @Test
    void convertingTwiceIsAConflict() {
        assertThatThrownBy(() -> converted().convertToStorage(LocalDate.of(2024, 12, 2)))
                .isInstanceOf(ConflictError.class);
    }

    @Test
    void anEmptyAccountCannotBeConverted() {
        assertThatThrownBy(() -> anAccount().convertToStorage(LocalDate.of(2024, 12, 1)))
                .isInstanceOf(GrainBankError.class);
    }

    @Test
    void theBalanceSurvivesARunOfMovements() {
        FeedAccount account = anAccount().deposit("A-1", d("5000"), OPENED);
        for (int index = 0; index < 4; index++) {
            account = account.withdraw("F-" + index, CRACK, d("10"), LocalDate.of(2024, 10, 10 + index));
        }
        BigDecimal expected = d("5000.0000").subtract(CRACK.bushelsFor(d("10")).multiply(d(4)));
        assertThat(account.balance()).isEqualByComparingTo(expected);
    }
}
