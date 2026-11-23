package io.github.rkaushikdev.bushelworks.domain.advances;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdvancesTest {

    static final LocalDate DRAWN = LocalDate.of(2024, 9, 12);
    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);

    static Advance aSettlementAdvance() {
        return Advance.of("ADV-2024-0031", "MERRICK", AdvanceKind.SETTLEMENT, d("5000.00"), DRAWN);
    }

    static Advance aCropAdvance() {
        return Advance.of("CRP-2024-0007", "MERRICK", AdvanceKind.CROP, d("50000.00"), DRAWN)
                .withAnnualRatePercent(d("8.5"))
                .withDueOn(LocalDate.of(2025, 3, 1));
    }

    @Test
    void anAdvanceNeedsAReferenceAndAnAmount() {
        assertThatThrownBy(() -> aSettlementAdvance().withReference("")).isInstanceOf(AdvanceError.class);
        assertThatThrownBy(() -> aSettlementAdvance().withPrincipal(d("0"))).isInstanceOf(AdvanceError.class);
    }

    @Test
    void aSettlementAdvanceCarriesNoInterest() {
        assertThatThrownBy(() -> aSettlementAdvance().withAnnualRatePercent(d("8.5")))
                .isInstanceOf(AdvanceError.class);
        assertThat(aSettlementAdvance().interestTo(SETTLED)).isEqualTo(d("0.00"));
    }

    @Test
    void aNegativeRateIsRefused() {
        assertThatThrownBy(() -> aCropAdvance().withAnnualRatePercent(d("-1"))).isInstanceOf(AdvanceError.class);
    }

    @Test
    void anAdvanceDueBeforeItWasDrawnIsRefused() {
        assertThatThrownBy(() -> aCropAdvance().withDueOn(LocalDate.of(2024, 9, 1)))
                .isInstanceOf(AdvanceError.class);
    }

    @Test
    void aNewAdvanceIsWhollyOutstanding() {
        Advance advance = aSettlementAdvance();
        assertThat(advance.outstanding()).isEqualTo(d("5000.00"));
        assertThat(advance.isSettled()).isFalse();
        assertThat(advance.repaid()).isEqualTo(d("0.00"));
    }

    @Test
    void daysOutIsCountedFromTheDraw() {
        assertThat(aSettlementAdvance().daysOut(SETTLED)).isEqualTo(38);
    }

    @Test
    void askingBeforeTheDrawIsRefused() {
        assertThatThrownBy(() -> aSettlementAdvance().daysOut(LocalDate.of(2024, 9, 1)))
                .isInstanceOf(AdvanceError.class);
    }

    @Test
    void cropAdvanceInterestIsSimpleOnActualDays() {
        // 50000 at 8.5% for 38 days over 365
        assertThat(aCropAdvance().interestTo(SETTLED)).isEqualTo(d("442.47"));
    }

    @Test
    void thePayoffIsPrincipalPlusInterest() {
        assertThat(aCropAdvance().payoff(SETTLED)).isEqualTo(d("50442.47"));
    }

    @Test
    void interestOnTheDayItWasDrawnIsNothing() {
        assertThat(aCropAdvance().interestTo(DRAWN)).isEqualTo(d("0.00"));
    }

    @Test
    void aRepaymentReducesWhatIsOutstanding() {
        Advance advance = aSettlementAdvance().repay("S-1", d("2000.00"), SETTLED);
        assertThat(advance.repaid()).isEqualTo(d("2000.00"));
        assertThat(advance.outstanding()).isEqualTo(d("3000.00"));
    }

    @Test
    void repayingTheWholeThingSettlesIt() {
        Advance advance = aSettlementAdvance().repay("S-1", d("5000.00"), SETTLED);
        assertThat(advance.isSettled()).isTrue();
        assertThat(advance.outstanding()).isEqualTo(d("0.00"));
    }

    @Test
    void repayingASettledAdvanceIsAConflict() {
        Advance advance = aSettlementAdvance().repay("S-1", d("5000.00"), SETTLED);
        assertThatThrownBy(() -> advance.repay("S-2", d("1.00"), SETTLED)).isInstanceOf(ConflictError.class);
    }

    @Test
    void repayingMoreThanIsOwedIsRefused() {
        assertThatThrownBy(() -> aSettlementAdvance().repay("S-1", d("5001.00"), SETTLED))
                .isInstanceOf(AdvanceError.class);
    }

    @Test
    void repayingNothingIsRefused() {
        assertThatThrownBy(() -> aSettlementAdvance().repay("S-1", d("0"), SETTLED))
                .isInstanceOf(AdvanceError.class);
    }

    @Test
    void interestAccruesOnWhatIsLeftAfterARepayment() {
        Advance advance = aCropAdvance().repay("S-1", d("25000.00"), LocalDate.of(2024, 10, 1));
        // 19 days on 50,000 before the recovery and 19 on 25,000 after it
        assertThat(advance.interestTo(SETTLED)).isEqualTo(d("331.85"));
        assertThat(advance.interestTo(LocalDate.of(2024, 10, 1))).isEqualTo(d("221.23"));
        assertThat(advance.balanceOn(LocalDate.of(2024, 9, 30))).isEqualTo(d("50000.00"));
        assertThat(advance.balanceOn(LocalDate.of(2024, 10, 1))).isEqualTo(d("25000.00"));
    }

    @Test
    void aRateChangeRunsFromItsOwnDay() {
        Advance advance = aCropAdvance().withRateChange(LocalDate.of(2024, 10, 1), d("9.0"));
        assertThat(advance.rateOn(LocalDate.of(2024, 9, 30))).isEqualTo(d("8.5"));
        assertThat(advance.rateOn(LocalDate.of(2024, 10, 1))).isEqualTo(d("9.0"));
        assertThat(advance.interestTo(SETTLED)).isEqualTo(d("455.48"));
        assertThat(advance.interestTo(LocalDate.of(2024, 9, 25)))
                .isEqualTo(aCropAdvance().interestTo(LocalDate.of(2024, 9, 25)));
    }

    @Test
    void recoveriesAndRateChangesTakeTurns() {
        Advance advance = aCropAdvance()
                .withRateChange(LocalDate.of(2024, 10, 10), d("9.0"))
                .repay("S-1", d("20000.00"), LocalDate.of(2024, 10, 1));
        assertThat(advance.rateChanges()).hasSize(1);
        assertThat(advance.interestTo(SETTLED)).isEqualTo(d("358.08"));
        assertThat(advance.payoff(SETTLED)).isEqualTo(d("30358.08"));
    }

    @Test
    void aRateChangeHasToBeAfterTheDrawAndOnADayOfItsOwn() {
        assertThatThrownBy(() -> aCropAdvance().withRateChange(DRAWN, d("9.0"))).isInstanceOf(AdvanceError.class);
        Advance changed = aCropAdvance().withRateChange(LocalDate.of(2024, 10, 1), d("9.0"));
        assertThatThrownBy(() -> changed.withRateChange(LocalDate.of(2024, 10, 1), d("9.25")))
                .isInstanceOf(AdvanceError.class);
        assertThatThrownBy(() -> new RateChange(LocalDate.of(2024, 10, 1), d("-1")))
                .isInstanceOf(AdvanceError.class);
        assertThatThrownBy(() -> aSettlementAdvance().withRateChange(LocalDate.of(2024, 10, 1), d("9.0")))
                .isInstanceOf(AdvanceError.class);
    }

    @Test
    void anAdvanceWithNoDueDateNeverGoesOverdue() {
        assertThat(aSettlementAdvance().isOverdue(LocalDate.of(2030, 1, 1))).isFalse();
    }

    @Test
    void anAdvancePastItsDueDateIsOverdue() {
        assertThat(aCropAdvance().isOverdue(LocalDate.of(2025, 2, 1))).isFalse();
        assertThat(aCropAdvance().isOverdue(LocalDate.of(2025, 4, 1))).isTrue();
    }

    @Test
    void aRepaidAdvanceIsNeverOverdue() {
        Advance advance = aCropAdvance().repay("S-1", d("50000.00"), SETTLED);
        assertThat(advance.isOverdue(LocalDate.of(2025, 4, 1))).isFalse();
    }

    @Test
    void theCapIsAPercentageOfTheGrainValue() {
        assertThat(Advances.capFor(d("10000.00"))).isEqualTo(d("8000.00"));
        assertThat(Advances.capFor(d("10000.00"), d("50"))).isEqualTo(d("5000.00"));
        assertThat(Advances.SETTLEMENT_ADVANCE_CAP).isEqualByComparingTo(d("80"));
    }

    @Test
    void aNegativeGrainValueCannotBeAdvancedAgainst() {
        assertThatThrownBy(() -> Advances.capFor(d("-1"))).isInstanceOf(AdvanceError.class);
    }

    @Test
    void anAdvanceInsideTheCapPasses() {
        assertThatCode(() -> Advances.checkSettlementAdvance(d("8000.00"), d("10000.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void anAdvanceAboveTheCapIsRefusedWithBothFigures() {
        assertThatThrownBy(() -> Advances.checkSettlementAdvance(d("9000.00"), d("10000.00")))
                .isInstanceOf(AdvanceError.class)
                .hasMessageContaining("8000.00");
    }

    @Test
    void whatIsOutstandingAcrossSeveralAdvances() {
        assertThat(Advances.outstandingFor(List.of(aSettlementAdvance(), aCropAdvance())))
                .isEqualTo(d("55000.00"));
    }

    @Test
    void aSettlementPaysTheOldestAdvanceFirst() {
        List<Advance> advances = List.of(
                aCropAdvance().withDrawnOn(LocalDate.of(2024, 8, 1)).withPrincipal(d("1000.00")), aSettlementAdvance());
        Advances.Recovery recovery = Advances.applyToSettlement(advances, d("1500.00"), "S-1", SETTLED);
        assertThat(recovery.advances().get(0).isSettled()).isTrue();
        assertThat(recovery.advances().get(1).isSettled()).isFalse();
        assertThat(recovery.remaining()).isEqualTo(d("0.00"));
    }

    @Test
    void aSettlementBiggerThanTheAdvancesLeavesTheRest() {
        Advances.Recovery recovery =
                Advances.applyToSettlement(List.of(aSettlementAdvance()), d("8000.00"), "S-1", SETTLED);
        assertThat(recovery.advances().get(0).isSettled()).isTrue();
        assertThat(recovery.remaining()).isEqualTo(d("3000.00"));
    }

    @Test
    void aSettlementThatPaysNothingLeavesEverythingOutstanding() {
        Advances.Recovery recovery =
                Advances.applyToSettlement(List.of(aSettlementAdvance()), d("0.00"), "S-1", SETTLED);
        assertThat(recovery.advances().get(0).outstanding()).isEqualTo(d("5000.00"));
        assertThat(recovery.remaining()).isEqualTo(d("0.00"));
    }

    @Test
    void aNegativeSettlementRecoversNothing() {
        Advances.Recovery recovery =
                Advances.applyToSettlement(List.of(aSettlementAdvance()), d("-100.00"), "S-1", SETTLED);
        assertThat(recovery.advances().get(0).outstanding()).isEqualTo(d("5000.00"));
        assertThat(recovery.remaining()).isEqualTo(d("0.00"));
    }

    @Test
    void aPartialRecoveryLeavesTheRemainderForNextTime() {
        Advances.Recovery recovery =
                Advances.applyToSettlement(List.of(aSettlementAdvance()), d("2000.00"), "S-1", SETTLED);
        assertThat(recovery.advances().get(0).outstanding()).isEqualTo(d("3000.00"));
        assertThat(recovery.remaining()).isEqualTo(d("0.00"));
    }

    @Test
    void anAlreadySettledAdvanceIsSkipped() {
        List<Advance> advances = List.of(aSettlementAdvance().repay("S-0", d("5000.00"), SETTLED));
        Advances.Recovery recovery = Advances.applyToSettlement(advances, d("1000.00"), "S-1", SETTLED);
        assertThat(recovery.remaining()).isEqualTo(d("1000.00"));
        assertThat(recovery.advances().get(0).repayments()).hasSize(1);
    }
}
