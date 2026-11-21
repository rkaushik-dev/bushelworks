package io.github.rkaushikdev.bushelworks.domain.liens;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiensTest {

    static final LocalDate FILED = LocalDate.of(2024, 3, 1);
    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);

    static Interest.Builder joint() {
        return Interest.builder()
                .reference("UCC-2024-0114")
                .partyCode("MERRICK")
                .holder("Farmers State Bank")
                .kind(LienKind.JOINT_PAYEE)
                .filedOn(FILED);
    }

    static Interest.Builder assignment() {
        return Interest.builder()
                .reference("ASG-2024-0022")
                .partyCode("MERRICK")
                .holder("Great Plains Seed")
                .kind(LienKind.ASSIGNMENT)
                .filedOn(FILED)
                .amountLimit(d("4000.00"));
    }

    static Interest.Builder notice() {
        return Interest.builder()
                .reference("NOT-2024-0009")
                .partyCode("MERRICK")
                .holder("Sunflower Credit")
                .kind(LienKind.NOTICE_ONLY)
                .filedOn(FILED);
    }

    static List<Payment> split(String net, Interest... interests) {
        return Liens.splitPayment("MERRICK", d(net), List.of(interests), "CORN", SETTLED);
    }

    @Test
    void anInterestNeedsAReferenceAndAHolder() {
        assertThatThrownBy(() -> joint().reference("").build()).isInstanceOf(LienError.class);
        assertThatThrownBy(() -> joint().holder("").build()).isInstanceOf(LienError.class);
    }

    @Test
    void anAssignmentNeedsAnAmount() {
        assertThatThrownBy(() -> assignment().amountLimit(null).build()).isInstanceOf(LienError.class);
    }

    @Test
    void anAssignmentOfNothingIsRefused() {
        assertThatThrownBy(() -> assignment().amountLimit(d("0")).build()).isInstanceOf(LienError.class);
    }

    @Test
    void aJointPayeeCannotCarryAnAmount() {
        assertThatThrownBy(() -> joint().amountLimit(d("1000")).build()).isInstanceOf(LienError.class);
    }

    @Test
    void anInterestReleasedBeforeFilingIsRefused() {
        assertThatThrownBy(() -> joint().releasedOn(LocalDate.of(2024, 1, 1)).build())
                .isInstanceOf(LienError.class);
    }

    @Test
    void anInterestIsLiveFromItsFiling() {
        assertThat(joint().build().isLiveOn(LocalDate.of(2024, 2, 1))).isFalse();
        assertThat(joint().build().isLiveOn(SETTLED)).isTrue();
    }

    @Test
    void aReleasedInterestStopsBiting() {
        Interest released = joint().releasedOn(LocalDate.of(2024, 9, 1)).build();
        assertThat(released.isLiveOn(LocalDate.of(2024, 8, 1))).isTrue();
        assertThat(released.isLiveOn(SETTLED)).isFalse();
    }

    @Test
    void aBlanketInterestCoversEveryCommodity() {
        assertThat(joint().build().covers("CORN")).isTrue();
        assertThat(joint().build().covers("SOYB")).isTrue();
    }

    @Test
    void aSpecificInterestCoversOnlyItsCommodity() {
        Interest specific = joint().commodityCode("CORN").build();
        assertThat(specific.covers("CORN")).isTrue();
        assertThat(specific.covers("SOYB")).isFalse();
    }

    @Test
    void liveInterestsAreOldestFilingFirst() {
        Interest later = assignment()
                .reference("ASG-9")
                .filedOn(LocalDate.of(2024, 6, 1))
                .build();
        assertThat(Liens.liveInterests(List.of(later, joint().build()), "CORN", SETTLED))
                .extracting(Interest::reference)
                .containsExactly("UCC-2024-0114", "ASG-9");
    }

    @Test
    void aProducerWithNothingFiledGetsOneCheque() {
        List<Payment> payments = split("10000.00");
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).payee()).isEqualTo("MERRICK");
        assertThat(payments.get(0).isJoint()).isFalse();
        assertThat(payments.get(0).dollars()).isEqualTo(d("10000.00"));
    }

    @Test
    void aJointPayeeGoesOnTheProducersCheque() {
        List<Payment> payments = split("10000.00", joint().build());
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).isJoint()).isTrue();
        assertThat(payments.get(0).names()).containsExactly("MERRICK", "Farmers State Bank");
    }

    @Test
    void twoJointPayeesBothGoOn() {
        Interest second = joint().reference("UCC-2").holder("Sunflower Credit").build();
        assertThat(split("10000.00", joint().build(), second).get(0).names()).hasSize(3);
    }

    @Test
    void anAssignmentComesOffTheTop() {
        List<Payment> payments = split("10000.00", assignment().build());
        assertThat(payments).extracting(Payment::payee).containsExactly("Great Plains Seed", "MERRICK");
        assertThat(payments.get(0).dollars()).isEqualTo(d("4000.00"));
        assertThat(payments.get(1).dollars()).isEqualTo(d("6000.00"));
    }

    @Test
    void anAssignmentIsCappedAtWhatItIsWrittenFor() {
        assertThat(split("100000.00", assignment().build()).get(0).dollars()).isEqualTo(d("4000.00"));
    }

    @Test
    void anAssignmentBiggerThanTheSettlementTakesAllOfIt() {
        List<Payment> payments = split("2500.00", assignment().build());
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).payee()).isEqualTo("Great Plains Seed");
        assertThat(payments.get(0).dollars()).isEqualTo(d("2500.00"));
    }

    @Test
    void twoAssignmentsArePaidOldestFilingFirst() {
        Interest later = assignment()
                .reference("ASG-9")
                .holder("Cheyenne Chemical")
                .filedOn(LocalDate.of(2024, 6, 1))
                .amountLimit(d("5000.00"))
                .build();
        List<Payment> payments = split("6000.00", later, assignment().build());
        assertThat(payments).extracting(Payment::payee).containsExactly("Great Plains Seed", "Cheyenne Chemical");
        assertThat(payments.get(0).dollars()).isEqualTo(d("4000.00"));
        assertThat(payments.get(1).dollars()).isEqualTo(d("2000.00"));
    }

    @Test
    void anAssignmentAndAJointPayeeBothApply() {
        List<Payment> payments = split("10000.00", assignment().build(), joint().build());
        assertThat(payments.get(0).payee()).isEqualTo("Great Plains Seed");
        assertThat(payments.get(1).names()).containsExactly("MERRICK", "Farmers State Bank");
    }

    @Test
    void aNoticeOnlyInterestChangesNothing() {
        List<Payment> payments = split("10000.00", notice().build());
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).isJoint()).isFalse();
    }

    @Test
    void aNoticeStillShowsOnTheStatementFooter() {
        assertThat(Liens.notices(List.of(notice().build(), joint().build()), SETTLED))
                .extracting(Interest::reference)
                .containsExactly("NOT-2024-0009");
    }

    @Test
    void anInterestOnAnotherCommodityDoesNotBite() {
        List<Payment> payments =
                split("10000.00", assignment().commodityCode("SOYB").build());
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).payee()).isEqualTo("MERRICK");
    }

    @Test
    void aReleasedInterestDoesNotBite() {
        List<Payment> payments = split(
                "10000.00", assignment().releasedOn(LocalDate.of(2024, 9, 1)).build());
        assertThat(payments.get(0).payee()).isEqualTo("MERRICK");
    }

    @Test
    void aSettlementOfNothingWritesNoCheques() {
        assertThat(split("0", joint().build())).isEmpty();
        assertThat(split("-100")).isEmpty();
    }

    @Test
    void theChequesAddBackToTheSettlement() {
        BigDecimal net = d("10000.00");
        List<Payment> payments =
                Liens.splitPayment("MERRICK", net, List.of(assignment().build(), joint().build()), "CORN", SETTLED);
        assertThatCode(() -> Liens.checkBalances(payments, net)).doesNotThrowAnyException();
    }

    @Test
    void aShortChequeRunIsCaught() {
        List<Payment> payments = split("10000.00");
        assertThatThrownBy(() -> Liens.checkBalances(payments, d("12000.00"))).isInstanceOf(LienError.class);
    }

    @Test
    void noChequesBalanceAgainstADebitSettlement() {
        assertThatCode(() -> Liens.checkBalances(List.of(), d("-500.00"))).doesNotThrowAnyException();
    }
}
