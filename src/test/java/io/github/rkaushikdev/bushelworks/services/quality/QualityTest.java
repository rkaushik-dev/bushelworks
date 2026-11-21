package io.github.rkaushikdev.bushelworks.services.quality;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.complaints.Complaint;
import io.github.rkaushikdev.bushelworks.domain.complaints.ComplaintError;
import io.github.rkaushikdev.bushelworks.domain.complaints.Outcome;
import io.github.rkaushikdev.bushelworks.domain.complaints.Register;
import io.github.rkaushikdev.bushelworks.domain.complaints.Source;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ComplaintRow;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Taking, working and closing a complaint against what is on file. */
class QualityTest {

    static final LocalDate DELIVERED = LocalDate.of(2024, 10, 3);
    static final LocalDate RAISED = LocalDate.of(2024, 10, 9);
    static final LocalDate OPENS = LocalDate.of(2024, 9, 1);
    static final LocalDate CLOSES = LocalDate.of(2025, 8, 31);
    static final LocalDate SHUT = LocalDate.of(2024, 10, 14);

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    /** Everything a complaint is filed with, so a test can change one part of it. */
    record Body(
            String reference,
            String source,
            String subject,
            LocalDate raisedOn,
            LocalDate deliveredOn,
            FilingOptions options) {

        Body withReference(String value) {
            return new Body(value, source, subject, raisedOn, deliveredOn, options);
        }

        Body withSource(String value) {
            return new Body(reference, value, subject, raisedOn, deliveredOn, options);
        }

        Body withRaisedOn(LocalDate value) {
            return new Body(reference, source, subject, value, deliveredOn, options);
        }

        Body with(UnaryOperator<FilingOptions> change) {
            return new Body(reference, source, subject, raisedOn, deliveredOn, change.apply(options));
        }
    }

    /** A buyer's moisture complaint about a car out of BIN-3, with its retained sample. */
    static final Body ONE = new Body(
            "C-2024-018",
            Source.BUYER.value(),
            "car ran 15.8 moisture against a 14.0 contract",
            RAISED,
            DELIVERED,
            FilingOptions.defaults().withPartyCode("CARGILL").withBinId("BIN-3").withProbeId("P-88213"));

    static ComplaintRow fileOne(UnitOfWork uow) {
        return fileOne(uow, body -> body);
    }

    static ComplaintRow fileOne(UnitOfWork uow, UnaryOperator<Body> overrides) {
        Body body = overrides.apply(ONE);
        return Quality.fileComplaint(
                uow,
                body.reference(),
                body.source(),
                body.subject(),
                body.raisedOn(),
                body.deliveredOn(),
                body.options());
    }

    @Test
    void aComplaintCanBeFiled() {
        ComplaintRow row;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            row = fileOne(uow);
            uow.commit();
        }
        assertThat(row.getOutcome()).isEqualTo("open");
    }

    @Test
    void aSourceCanComeInAsAString() {
        ComplaintRow row;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            row = fileOne(uow, body -> body.withSource("producer"));
            uow.commit();
        }
        assertThat(row.getSource()).isEqualTo("producer");
    }

    @Test
    void aSourceNobodyKnowsIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> fileOne(uow, body -> body.withSource("postman")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aComplaintBeforeTheDeliveryIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> fileOne(uow, body -> body.withRaisedOn(LocalDate.of(2024, 10, 1))))
                    .isInstanceOf(ComplaintError.class);
        }
    }

    @Test
    void theSameReferenceTwiceIsAConflict() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow);
            assertThatThrownBy(() -> fileOne(uow)).isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void aNamedTicketThatIsNotOnFileIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> fileOne(uow, body -> body.with(options -> options.withTicketNumber("A-99999"))))
                    .isInstanceOf(ComplaintError.class)
                    .hasMessage("no live ticket A-99999");
        }
    }

    @Test
    void aTicketOnFileIsAccepted() {
        ScaleTicket cornTicket = HouseFixtures.cornTicket();
        ComplaintRow row;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", HouseFixtures.rules());
            row = fileOne(uow, body -> body.with(options -> options.withTicketNumber(cornTicket.ticketNumber())));
            uow.commit();
        }
        assertThat(row.getTicketNumber()).contains(cornTicket.ticketNumber());
    }

    @Test
    void aTicketWeighedAfterTheDeliveryIsRefused() {
        ScaleTicket cornTicket = HouseFixtures.cornTicket();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(
                    uow,
                    cornTicket.toBuilder().scaleDate(LocalDate.of(2024, 11, 20)).build(),
                    "MERRICK",
                    HouseFixtures.rules());
            assertThatThrownBy(() -> fileOne(
                            uow, body -> body.with(options -> options.withTicketNumber(cornTicket.ticketNumber()))))
                    .isInstanceOf(ComplaintError.class)
                    .hasMessage("ticket " + cornTicket.ticketNumber() + " was weighed after 2024-10-03");
        }
    }

    @Test
    void aRowAndTheDomainObjectAgree() {
        Complaint complaint;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ComplaintRow row = fileOne(uow);
            uow.commit();
            complaint = Quality.toDomain(row);
        }
        assertThat(complaint.reference()).isEqualTo("C-2024-018");
        assertThat(complaint.source()).isEqualTo(Source.BUYER);
        assertThat(complaint.isOpen()).isTrue();
    }

    @Test
    void theMappingSurvivesARoundTrip() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ComplaintRow row = fileOne(uow);
            uow.commit();
            Complaint again = Quality.toDomain(Quality.toRow(Quality.toDomain(row)));
            assertThat(again).isEqualTo(Quality.toDomain(row));
        }
    }

    @Test
    void closingWritesTheOutcomeBack() {
        ComplaintRow row;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow);
            row = Quality.close(uow, "C-2024-018", Outcome.UPHELD, SHUT, d("412.5"));
            uow.commit();
        }
        assertThat(row.getOutcome()).isEqualTo("upheld");
        assertThat(row.getCredit()).isEqualTo(d("412.50"));
        assertThat(row.getClosedOn()).contains(SHUT);
    }

    @Test
    void anOutcomeCanComeInAsAString() {
        ComplaintRow row;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow);
            row = Quality.close(uow, "C-2024-018", "settled", SHUT);
            uow.commit();
        }
        assertThat(row.getOutcome()).isEqualTo("settled");
    }

    @Test
    void closingTwiceIsAConflict() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow);
            Quality.close(uow, "C-2024-018", Outcome.REJECTED, SHUT);
            assertThatThrownBy(() -> Quality.close(uow, "C-2024-018", Outcome.UPHELD, SHUT.plusDays(1)))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void aRejectedComplaintCarriesNoCredit() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow);
            assertThatThrownBy(() -> Quality.close(uow, "C-2024-018", Outcome.REJECTED, SHUT, d("50")))
                    .isInstanceOf(ComplaintError.class);
        }
    }

    @Test
    void aClosedComplaintLeavesTheOpenList() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow);
            Quality.close(uow, "C-2024-018", Outcome.SETTLED, SHUT);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Quality.openComplaints(uow)).isEmpty();
        }
    }

    @Test
    void theOpenListHandsBackDomainObjects() {
        List<Complaint> found;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow);
            uow.commit();
            found = Quality.openComplaints(uow);
        }
        assertThat(found.get(0).daysAfter()).isEqualTo(6);
    }

    @Test
    void theOpenListCanBeNarrowedToABin() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow);
            fileOne(uow, body -> body.withReference("C-2").with(options -> options.withBinId("BIN-7")));
            uow.commit();
            assertThat(Quality.references(Quality.openComplaints(uow, "BIN-7"))).containsExactly("C-2");
        }
    }

    @Test
    void aRegisterCarriesTheSpan() {
        Register register;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow);
            fileOne(uow, body -> body.withReference("C-2").withRaisedOn(LocalDate.of(2025, 10, 1)));
            uow.commit();
            register = Quality.registerBetween(uow, OPENS, CLOSES);
        }
        assertThat(Quality.references(register.complaints())).containsExactly("C-2024-018");
    }

    @Test
    void aComplaintWithNoSampleIsIndefensible() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow, body -> body.with(options -> options.withProbeId(null)));
            uow.commit();
            assertThat(Quality.references(Quality.indefensible(uow, OPENS, CLOSES)))
                    .containsExactly("C-2024-018");
        }
    }

    @Test
    void aComplaintPastRetentionIsIndefensible() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow, body -> body.withRaisedOn(LocalDate.of(2024, 12, 1)));
            uow.commit();
            assertThat(Quality.indefensible(uow, OPENS, CLOSES)).hasSize(1);
        }
    }

    @Test
    void aComplaintInsideRetentionIsDefensible() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow);
            uow.commit();
            assertThat(Quality.indefensible(uow, OPENS, CLOSES)).isEmpty();
        }
    }

    @Test
    void theRetentionPeriodIsSixWeeksAndABit() {
        assertThat(Quality.RETENTION_DAYS).isEqualTo(45);
    }

    void fileSeveral(UnitOfWork uow, int how) {
        for (int index = 0; index < how; index++) {
            String reference = "C-" + index;
            fileOne(uow, body -> body.withReference(reference));
        }
    }

    @Test
    void threeComplaintsMakeABinAPattern() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileSeveral(uow, 3);
            uow.commit();
            assertThat(Quality.repeatBins(uow, OPENS, CLOSES)).containsExactly("BIN-3");
        }
    }

    @Test
    void twoComplaintsAreNotAPattern() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileSeveral(uow, 2);
            uow.commit();
            assertThat(Quality.repeatBins(uow, OPENS, CLOSES)).isEmpty();
        }
    }

    @Test
    void theThresholdCanBeMoved() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileSeveral(uow, 2);
            uow.commit();
            assertThat(Quality.repeatBins(uow, OPENS, CLOSES, 2)).containsExactly("BIN-3");
        }
    }

    @Test
    void theSummaryCountsWhatCameIn() {
        ComplaintSummary page;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow);
            fileOne(uow, body -> body.withReference("C-2").withSource(Source.PRODUCER.value()));
            uow.commit();
            page = Quality.summary(uow, OPENS, CLOSES);
        }
        assertThat(page.count()).isEqualTo(2);
        assertThat(page.open()).isEqualTo(2);
        assertThat(page.bySource()).isEqualTo(Map.of("buyer", 1, "producer", 1));
    }

    @Test
    void theSummaryCarriesTheUpheldRate() {
        ComplaintSummary page;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            fileOne(uow);
            fileOne(uow, body -> body.withReference("C-2"));
            Quality.close(uow, "C-2024-018", Outcome.UPHELD, SHUT, d("412.50"));
            Quality.close(uow, "C-2", Outcome.REJECTED, SHUT);
            uow.commit();
            page = Quality.summary(uow, OPENS, CLOSES);
        }
        assertThat(page.upheldRate()).isEqualTo(d("50.0"));
        assertThat(page.credits()).isEqualTo(d("412.50"));
    }

    @Test
    void aQuietSpanSummarisesToNothing() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ComplaintSummary page = Quality.summary(uow, OPENS, CLOSES);
            assertThat(page.count()).isZero();
            assertThat(page.bySource()).isEmpty();
            assertThat(page.indefensible()).isEmpty();
        }
    }

    @Test
    void theSummaryNamesTheWindow() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Quality.summary(uow, OPENS, CLOSES).windowDays()).isEqualTo(30);
        }
    }
}
