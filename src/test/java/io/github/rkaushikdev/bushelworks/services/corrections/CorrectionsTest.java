package io.github.rkaushikdev.bushelworks.services.corrections;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.LookupFailure;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.services.audit.Audit;
import io.github.rkaushikdev.bushelworks.services.audit.Document;
import io.github.rkaushikdev.bushelworks.services.audit.Entry;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CorrectionsTest {

    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);
    static final BigDecimal SPOT = d("4.3500");
    static final LocalDate REVERSED = LocalDate.of(2024, 10, 22);

    Database database;
    RuleBook rules;
    ScaleTicket cornTicket;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        rules = HouseFixtures.rules();
        cornTicket = HouseFixtures.cornTicket();
    }

    static PurchaseContract aContract(String bushels) {
        return PurchaseContract.builder()
                .number("F-2024-118")
                .kind(ContractKind.FORWARD)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d(bushels))
                .window(new DeliveryWindow(LocalDate.of(2024, 9, 1), LocalDate.of(2024, 11, 30)))
                .flatPrice(d("4.6200"))
                .build();
    }

    /** The corn ticket applied to a forward contract, committed. */
    void applied() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract("20000"));
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.commit();
        }
    }

    /** The applied ticket settled, committed. */
    Settlement settled() {
        applied();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Settlement statement = SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT);
            uow.commit();
            return statement;
        }
    }

    @Test
    void unapplyingTakesTheBushelsBack() {
        applied();
        BigDecimal before;
        Unapplied result;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            before = uow.contracts().appliedBushels("F-2024-118");
            result = Corrections.unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "wrong contract");
            uow.commit();
        }
        assertThat(result.bushels()).isEqualByComparingTo(before);
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.contracts().appliedBushels("F-2024-118")).isEqualByComparingTo("0");
        }
    }

    @Test
    void theTicketIsUntouchedByAnUnapply() {
        applied();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Corrections.unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "wrong contract");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow row = uow.tickets().row("HOME", "A-10441");
            assertThat(row.isVoided()).isFalse();
            assertThat(row.getNetPounds()).isEqualByComparingTo("52240");
        }
    }

    @Test
    void unapplyingTwiceFindsNothingToReverse() {
        applied();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Corrections.unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "wrong contract");
            assertThatThrownBy(() -> Corrections.unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "again"))
                    .isInstanceOf(LookupFailure.class);
        }
    }

    @Test
    void anUnapplyNeedsAReason() {
        applied();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Corrections.unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, ""))
                    .isInstanceOf(CorrectionError.class);
        }
    }

    @Test
    void aSettledTicketCannotBeUnapplied() {
        settled();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Corrections.unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "too late"))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void aFilledContractReopensWhenATicketComesOff() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract("500"));
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.contracts().close("F-2024-118");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Corrections.unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "wrong contract");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.contracts().row("F-2024-118").getStatus()).isEqualTo("open");
        }
    }

    @Test
    void anUnapplyLeavesAnAuditEntry() {
        applied();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Corrections.unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "driver named the wrong one", "office");
            uow.commit();
        }
        List<Entry> entries;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            entries = Audit.forDocument(uow, Document.CONTRACT, "F-2024-118");
        }
        Entry last = entries.get(entries.size() - 1);
        assertThat(last.action()).isEqualTo("reversed");
        assertThat(last.detail())
                .hasValueSatisfying(detail -> assertThat(detail).contains("wrong one"));
    }

    @Test
    void reissuingWritesAReplacementAndVoidsTheOriginal() {
        applied();
        ScaleTicketRow replacement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            replacement = Corrections.voidAndReissue(
                    uow, "HOME", "A-10441", "A-10441R", d("78000"), d("26180"), "scale head was reading light");
            uow.commit();
        }
        assertThat(replacement.getNetPounds()).isEqualByComparingTo("51820");
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.tickets().row("HOME", "A-10441").isVoided()).isTrue();
            assertThat(uow.tickets().row("HOME", "A-10441R").isVoided()).isFalse();
        }
    }

    @Test
    void theReplacementCarriesTheSampleAndTheSplits() {
        applied();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Corrections.voidAndReissue(uow, "HOME", "A-10441", "A-10441R", d("78000"), d("26180"), "reweighed");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow row = uow.tickets().row("HOME", "A-10441R");
            assertThat(row.getSamples()).hasSize(5);
            assertThat(row.getSplits().get(0).getPartyCode()).isEqualTo("MERRICK");
            assertThat(row.getRemarks())
                    .hasValueSatisfying(remarks -> assertThat(remarks).contains("reissue of A-10441"));
        }
    }

    @Test
    void aReissueNeedsAReason() {
        applied();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() ->
                            Corrections.voidAndReissue(uow, "HOME", "A-10441", "A-10441R", d("78000"), d("26180"), ""))
                    .isInstanceOf(CorrectionError.class);
        }
    }

    @Test
    void aReissueWithATareAboveGrossIsRefused() {
        applied();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Corrections.voidAndReissue(
                            uow, "HOME", "A-10441", "A-10441R", d("26000"), d("26180"), "reweighed"))
                    .isInstanceOf(CorrectionError.class);
        }
    }

    @Test
    void reissuingOntoAnExistingNumberIsAConflict() {
        applied();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(
                    uow, cornTicket.toBuilder().ticketNumber("A-10442").build(), "MERRICK", rules);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Corrections.voidAndReissue(
                            uow, "HOME", "A-10441", "A-10442", d("78000"), d("26180"), "reweighed"))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void aSettledTicketCannotBeReissued() {
        settled();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Corrections.voidAndReissue(
                            uow, "HOME", "A-10441", "A-10441R", d("78000"), d("26180"), "too late"))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void reversingASettlementTurnsTheSignOver() {
        Settlement statement = settled();
        Settlement reversal;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            reversal = Corrections.reverseSettlement(uow, statement.number(), REVERSED, "priced wrong");
            uow.commit();
        }
        assertThat(reversal.net()).isEqualByComparingTo(statement.net().negate());
        assertThat(reversal.number()).startsWith("SR-");
    }

    @Test
    void theOriginalIsMarkedVoidedRatherThanDeleted() {
        Settlement statement = settled();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Corrections.reverseSettlement(uow, statement.number(), REVERSED, "priced wrong");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.settlements().row(statement.number()).isVoided()).isTrue();
        }
    }

    @Test
    void reversingFreesTheTicketsToSettleAgain() {
        Settlement statement = settled();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Corrections.reverseSettlement(uow, statement.number(), REVERSED, "priced wrong");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.tickets().awaitingSettlement("HOME"))
                    .extracting(ScaleTicketRow::getTicketNumber)
                    .containsExactly("A-10441");
        }
    }

    @Test
    void reversingTwiceIsAConflict() {
        Settlement statement = settled();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Corrections.reverseSettlement(uow, statement.number(), REVERSED, "priced wrong");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Corrections.reverseSettlement(uow, statement.number(), REVERSED, "again"))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void aPaidSettlementCannotBeReversedHere() {
        applied();
        String number;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            number = SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
                    .number();
            uow.settlements().markPaid(number, "010441", LocalDate.of(2024, 10, 21));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Corrections.reverseSettlement(uow, number, REVERSED, "priced wrong"))
                    .isInstanceOf(ConflictError.class)
                    .hasMessageContaining("010441");
        }
    }

    @Test
    void aReversalNeedsAReason() {
        applied();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Settlement statement = SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT);
            assertThatThrownBy(() -> Corrections.reverseSettlement(uow, statement.number(), REVERSED, ""))
                    .isInstanceOf(CorrectionError.class);
        }
    }

    @Test
    void reversalsAreListedForAProducer() {
        Settlement statement = settled();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Corrections.reverseSettlement(uow, statement.number(), REVERSED, "priced wrong");
            uow.commit();
        }
        List<String> numbers;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            numbers = Corrections.reversalsFor(uow, "MERRICK").stream()
                    .map(SettlementRow::getNumber)
                    .toList();
        }
        assertThat(numbers).hasSize(1);
        assertThat(numbers.get(0)).startsWith("SR-");
    }
}
