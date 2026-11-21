package io.github.rkaushikdev.bushelworks.services.parties;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.Party;
import io.github.rkaushikdev.bushelworks.persistence.repositories.PartyNotFound;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PartiesTest {

    static final LocalDate OPENED = LocalDate.of(2024, 1, 15);
    static final LocalDate SEASON = LocalDate.of(2024, 3, 1);

    Database database;
    RuleBook rules;
    ScaleTicket cornTicket;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        rules = HouseFixtures.rules();
        cornTicket = HouseFixtures.cornTicket();
    }

    /** Three producers on the file, committed. */
    void filed() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Parties.openAccount(uow, "MERRICK", "Merrick Farms LLC", OPENED);
            Parties.openAccount(uow, "BROCKMAN", "Ellen Brockman", OPENED);
            Parties.openAccount(uow, "HOLLIS", "Hollis Brothers", OPENED);
            uow.commit();
        }
    }

    static List<SplitShare> aSplit() {
        return List.of(
                new SplitShare("BROCKMAN", d("25"), SplitRole.LANDLORD),
                new SplitShare("MERRICK", d("75"), SplitRole.TENANT));
    }

    @Test
    void openingAnAccountPutsItOnTheFile() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.parties().get("MERRICK").getName()).isEqualTo("Merrick Farms LLC");
        }
    }

    @Test
    void theSortNameIsUpperCased() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.parties().get("MERRICK").getSortName()).isEqualTo("MERRICK FARMS LLC");
        }
    }

    @Test
    void anAccountNeedsACodeAndAName() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Parties.openAccount(uow, "", "Somebody")).isInstanceOf(PartyError.class);
            assertThatThrownBy(() -> Parties.openAccount(uow, "X", "")).isInstanceOf(PartyError.class);
        }
    }

    @Test
    void theSameCodeTwiceIsAConflict() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Parties.openAccount(uow, "MERRICK", "Somebody Else"))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void aMissingPartySaysSo() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> uow.parties().get("NOBODY")).isInstanceOf(PartyNotFound.class);
        }
    }

    @Test
    void closingAnAccountTakesItOffTheActiveList() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Parties.closeAccount(uow, "HOLLIS", LocalDate.of(2024, 12, 31));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.parties().active()).extracting(Party::getCode).containsExactly("BROCKMAN", "MERRICK");
        }
    }

    @Test
    void closingTwiceIsAConflict() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Parties.closeAccount(uow, "HOLLIS", LocalDate.of(2024, 12, 31));
            assertThatThrownBy(() -> Parties.closeAccount(uow, "HOLLIS", LocalDate.of(2024, 12, 31)))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void anAccountWithUnsettledTicketsCannotClose() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Parties.closeAccount(uow, "MERRICK", LocalDate.of(2024, 12, 31)))
                    .isInstanceOf(ConflictError.class)
                    .hasMessageContaining("A-10441");
        }
    }

    @Test
    void aVoidedTicketDoesNotHoldAnAccountOpen() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.tickets().voidTicket("HOME", "A-10441", "reweighed");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatCode(() -> Parties.closeAccount(uow, "MERRICK", LocalDate.of(2024, 12, 31)))
                    .doesNotThrowAnyException();
            uow.commit();
        }
    }

    @Test
    void aProducerCanBeMarkedExempt() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Parties.setExempt(uow, "MERRICK", false).isCheckoffExempt())
                    .isFalse();
            assertThat(Parties.setExempt(uow, "MERRICK", true).isCheckoffExempt())
                    .isTrue();
            uow.commit();
        }
    }

    @Test
    void filingAnAgreementThatAddsUp() {
        filed();
        Agreement agreement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            agreement = Parties.fileAgreement(uow, "HOME-PLACE", aSplit(), SEASON);
            uow.commit();
        }
        assertThat(agreement.isBalanced()).isTrue();
        assertThat(agreement.totalPercent()).isEqualByComparingTo("100");
    }

    @Test
    void anAgreementThatDoesNotAddUpIsRefused() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Parties.fileAgreement(
                            uow, "HOME-PLACE", List.of(new SplitShare("MERRICK", d("60"))), SEASON))
                    .isInstanceOf(PartyError.class);
        }
    }

    @Test
    void anAgreementNeedsAFarmAndAShare() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Parties.fileAgreement(uow, "", aSplit(), SEASON))
                    .isInstanceOf(PartyError.class);
            assertThatThrownBy(() -> Parties.fileAgreement(uow, "HOME-PLACE", List.of(), SEASON))
                    .isInstanceOf(PartyError.class);
        }
    }

    @Test
    void aPartyCannotAppearTwiceOnAnAgreement() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            List<SplitShare> twice = List.of(new SplitShare("MERRICK", d("50")), new SplitShare("MERRICK", d("50")));
            assertThatThrownBy(() -> Parties.fileAgreement(uow, "HOME-PLACE", twice, SEASON))
                    .isInstanceOf(PartyError.class);
        }
    }

    @Test
    void anAgreementNamingSomebodyNotOnTheFileIsRefused() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            List<SplitShare> stranger = List.of(new SplitShare("NOBODY", d("50")), new SplitShare("MERRICK", d("50")));
            assertThatThrownBy(() -> Parties.fileAgreement(uow, "HOME-PLACE", stranger, SEASON))
                    .isInstanceOf(PartyNotFound.class);
        }
    }

    @Test
    void theAgreementInForceIsTheOneThatReadsBack() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Parties.fileAgreement(uow, "HOME-PLACE", aSplit(), SEASON);
            uow.commit();
        }
        Agreement agreement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            agreement = Parties.agreementFor(uow, "HOME-PLACE", LocalDate.of(2024, 10, 3));
        }
        assertThat(agreement.shares()).extracting(SplitShare::partyId).containsExactlyInAnyOrder("BROCKMAN", "MERRICK");
        assertThat(agreement.isBalanced()).isTrue();
    }

    @Test
    void nothingIsInForceBeforeTheAgreementStarts() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Parties.fileAgreement(uow, "HOME-PLACE", aSplit(), SEASON);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Parties.agreementFor(uow, "HOME-PLACE", LocalDate.of(2024, 1, 5)))
                    .isInstanceOf(NoSplitAgreement.class);
        }
    }

    @Test
    void aFarmWithNoAgreementSaysSo() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Parties.agreementFor(uow, "NORTH-EIGHTY", LocalDate.of(2024, 10, 3)))
                    .isInstanceOf(NoSplitAgreement.class);
        }
    }

    @Test
    void aNewAgreementClosesTheOldOne() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Parties.fileAgreement(uow, "HOME-PLACE", aSplit(), SEASON);
            Parties.fileAgreement(
                    uow, "HOME-PLACE", List.of(new SplitShare("MERRICK", d("100"))), LocalDate.of(2025, 3, 1));
            uow.commit();
        }
        Agreement old;
        Agreement current;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            old = Parties.agreementFor(uow, "HOME-PLACE", LocalDate.of(2024, 10, 3));
            current = Parties.agreementFor(uow, "HOME-PLACE", LocalDate.of(2025, 10, 3));
        }
        assertThat(old.shares()).hasSize(2);
        assertThat(current.shares()).hasSize(1);
    }

    @Test
    void backdatingAnAgreementUnderALiveOneIsRefused() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Parties.fileAgreement(uow, "HOME-PLACE", aSplit(), LocalDate.of(2025, 3, 1));
            assertThatThrownBy(() -> Parties.fileAgreement(uow, "HOME-PLACE", aSplit(), SEASON))
                    .isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void theFarmsAProducerHasAShareOf() {
        filed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Parties.fileAgreement(uow, "HOME-PLACE", aSplit(), SEASON);
            Parties.fileAgreement(uow, "NORTH-EIGHTY", List.of(new SplitShare("MERRICK", d("100"))), SEASON);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Parties.farmsFor(uow, "MERRICK")).containsExactly("HOME-PLACE", "NORTH-EIGHTY");
            assertThat(Parties.farmsFor(uow, "BROCKMAN")).containsExactly("HOME-PLACE");
            assertThat(Parties.farmsFor(uow, "HOLLIS")).isEmpty();
        }
    }
}
