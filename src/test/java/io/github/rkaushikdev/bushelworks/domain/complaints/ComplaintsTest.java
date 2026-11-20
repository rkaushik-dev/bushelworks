package io.github.rkaushikdev.bushelworks.domain.complaints;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComplaintsTest {

    static final LocalDate DELIVERED = LocalDate.of(2024, 10, 3);
    static final LocalDate RAISED = LocalDate.of(2024, 10, 9);

    static Complaint.Builder aComplaint() {
        return Complaint.builder()
                .reference("C-2024-018")
                .source(Source.BUYER)
                .raisedOn(RAISED)
                .deliveredOn(DELIVERED)
                .subject("car ran 15.8 moisture against a 14.0 contract")
                .partyCode("CARGILL")
                .binId("BIN-3")
                .probeId("P-88213");
    }

    static Register aRegister() {
        Register register = new Register();
        register.add(aComplaint().build());
        return register;
    }

    @Test
    void aComplaintNeedsAReferenceAndASubject() {
        assertThatThrownBy(() -> aComplaint().reference("").build()).isInstanceOf(ComplaintError.class);
        assertThatThrownBy(() -> aComplaint().subject("").build()).isInstanceOf(ComplaintError.class);
    }

    @Test
    void aComplaintCannotPredateTheDelivery() {
        assertThatThrownBy(
                        () -> aComplaint().raisedOn(LocalDate.of(2024, 10, 1)).build())
                .isInstanceOf(ComplaintError.class);
    }

    @Test
    void aCreditCannotBeNegative() {
        assertThatThrownBy(() -> aComplaint().credit(d("-10.00")).build()).isInstanceOf(ComplaintError.class);
    }

    @Test
    void aComplaintKnowsHowLongTheGrainHadBeenGone() {
        assertThat(aComplaint().build().daysAfter()).isEqualTo(6);
    }

    @Test
    void aComplaintInsideTheMonthIsNotLate() {
        assertThat(aComplaint().build().isLate()).isFalse();
    }

    @Test
    void aComplaintAfterTheMonthIsLate() {
        assertThat(aComplaint().raisedOn(LocalDate.of(2024, 11, 20)).build().isLate())
                .isTrue();
    }

    @Test
    void aNewComplaintIsOpen() {
        Complaint complaint = aComplaint().build();
        assertThat(complaint.isOpen()).isTrue();
        assertThat(complaint.outcome()).isSameAs(Outcome.OPEN);
    }

    @Test
    void aSampleOnTheShelfCanBePulled() {
        assertThat(aComplaint().build().sampleIsAvailable()).isTrue();
    }

    @Test
    void aSamplePastRetentionIsGone() {
        assertThat(aComplaint().raisedOn(LocalDate.of(2024, 12, 1)).build().sampleIsAvailable())
                .isFalse();
    }

    @Test
    void aComplaintWithNoProbeHasNothingToPull() {
        assertThat(aComplaint().probeId(null).build().sampleIsAvailable()).isFalse();
    }

    @Test
    void theRetentionPeriodCanBeSetByTheHouse() {
        Complaint complaint = aComplaint().raisedOn(LocalDate.of(2024, 10, 20)).build();
        assertThat(complaint.sampleIsAvailable(45)).isTrue();
        assertThat(complaint.sampleIsAvailable(10)).isFalse();
    }

    @Test
    void closingRecordsTheOutcomeAndTheDay() {
        Complaint closed = aComplaint().build().close(Outcome.UPHELD, LocalDate.of(2024, 10, 14), d("412.5"));
        assertThat(closed.outcome()).isSameAs(Outcome.UPHELD);
        assertThat(closed.closedOn()).contains(LocalDate.of(2024, 10, 14));
        assertThat(closed.credit()).isEqualTo(d("412.50"));
        assertThat(closed.isOpen()).isFalse();
    }

    @Test
    void closingAClosedComplaintIsAConflict() {
        Complaint closed = aComplaint().build().close(Outcome.REJECTED, LocalDate.of(2024, 10, 14));
        assertThatThrownBy(() -> closed.close(Outcome.UPHELD, LocalDate.of(2024, 10, 15)))
                .isInstanceOf(ConflictError.class);
    }

    @Test
    void closingNeedsARealOutcome() {
        assertThatThrownBy(() -> aComplaint().build().close(Outcome.OPEN, LocalDate.of(2024, 10, 14)))
                .isInstanceOf(ComplaintError.class);
    }

    @Test
    void aComplaintCannotCloseBeforeItWasRaised() {
        assertThatThrownBy(() -> aComplaint().build().close(Outcome.SETTLED, LocalDate.of(2024, 10, 4)))
                .isInstanceOf(ComplaintError.class);
    }

    @Test
    void aRejectedComplaintCarriesNoCredit() {
        assertThatThrownBy(() -> aComplaint().build().close(Outcome.REJECTED, LocalDate.of(2024, 10, 14), d("50")))
                .isInstanceOf(ComplaintError.class);
    }

    @Test
    void theNoteSurvivesClosing() {
        Complaint closed = aComplaint()
                .build()
                .close(Outcome.SETTLED, LocalDate.of(2024, 10, 14), d("100"), "split the difference");
        assertThat(closed.note()).isEqualTo("split the difference");
    }

    @Test
    void theSameReferenceTwiceIsAConflict() {
        Register register = aRegister();
        assertThatThrownBy(() -> register.add(aComplaint().build())).isInstanceOf(ConflictError.class);
    }

    @Test
    void replacingAComplaintThatIsNotThereIsRefused() {
        assertThatThrownBy(() -> new Register().replace(aComplaint().build())).isInstanceOf(ComplaintError.class);
    }

    @Test
    void closingAComplaintTakesItOffTheOpenList() {
        Register register = aRegister();
        register.replace(register.openComplaints().get(0).close(Outcome.REJECTED, RAISED));
        assertThat(register.openComplaints()).isEmpty();
    }

    @Test
    void theOpenListIsOldestFirst() {
        Register register = aRegister();
        register.add(aComplaint()
                .reference("C-2024-005")
                .raisedOn(LocalDate.of(2024, 10, 4))
                .build());
        assertThat(register.openComplaints())
                .extracting(Complaint::reference)
                .containsExactly("C-2024-005", "C-2024-018");
    }

    @Test
    void complaintsCanBeFoundByBin() {
        Register register = aRegister();
        register.add(aComplaint().reference("C-2").binId("BIN-7").build());
        assertThat(register.forBin("BIN-7")).extracting(Complaint::reference).containsExactly("C-2");
    }

    @Test
    void aBinComplainedAboutThreeTimesIsFlagged() {
        Register register = aRegister();
        register.add(aComplaint().reference("C-2").build());
        register.add(aComplaint().reference("C-3").build());
        assertThat(register.repeatBins()).containsExactly("BIN-3");
    }

    @Test
    void twoComplaintsAreNotAPattern() {
        Register register = aRegister();
        register.add(aComplaint().reference("C-2").build());
        assertThat(register.repeatBins()).isEmpty();
    }

    @Test
    void complaintsWithNoBinAreLeftOutOfThePattern() {
        Register register = new Register();
        for (int index = 0; index < 3; index++) {
            register.add(aComplaint().reference("C-" + index).binId(null).build());
        }
        assertThat(register.repeatBins()).isEmpty();
    }

    @Test
    void theCreditsAddUp() {
        Register register = aRegister();
        register.replace(register.openComplaints().get(0).close(Outcome.UPHELD, RAISED, d("412.50")));
        register.add(aComplaint().reference("C-2").credit(d("87.25")).build());
        assertThat(register.credits()).isEqualTo(d("499.75"));
    }

    @Test
    void nothingClosedYetMeansNoUpheldRate() {
        assertThat(aRegister().upheldRate()).isEqualTo(d("0.0"));
    }

    @Test
    void theUpheldRateCountsOnlyClosedComplaints() {
        Register register = aRegister();
        register.replace(register.openComplaints().get(0).close(Outcome.UPHELD, RAISED));
        register.add(aComplaint().reference("C-2").build());
        register.replace(aComplaint().reference("C-2").build().close(Outcome.REJECTED, RAISED));
        register.add(aComplaint().reference("C-3").build());
        assertThat(register.upheldRate()).isEqualTo(d("50.0"));
    }

    @Test
    void anOpenComplaintWithNoSampleIsIndefensible() {
        Register register = new Register();
        register.add(aComplaint().reference("C-9").probeId(null).build());
        assertThat(register.indefensible()).extracting(Complaint::reference).containsExactly("C-9");
    }

    @Test
    void aComplaintWithASampleIsDefensible() {
        assertThat(aRegister().indefensible()).isEmpty();
    }

    @Test
    void aClosedComplaintIsNotOnTheIndefensibleList() {
        Register register = new Register();
        register.add(aComplaint().reference("C-9").probeId(null).build());
        register.replace(register.openComplaints().get(0).close(Outcome.SETTLED, RAISED));
        assertThat(register.indefensible()).isEmpty();
    }

    @Test
    void complaintsGroupByWhoRang() {
        List<Complaint> complaints = List.of(
                aComplaint().build(),
                aComplaint().reference("C-2").source(Source.PRODUCER).build(),
                aComplaint().reference("C-3").source(Source.PRODUCER).build());
        assertThat(Register.bySource(complaints)).isEqualTo(Map.of("buyer", 1, "producer", 2));
    }

    @Test
    void groupingNothingGivesNothing() {
        assertThat(Register.bySource(List.of())).isEmpty();
    }
}
