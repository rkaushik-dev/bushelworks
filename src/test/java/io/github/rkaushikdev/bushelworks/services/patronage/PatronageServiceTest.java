package io.github.rkaushikdev.bushelworks.services.patronage;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.TestDecimals;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Counter;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageError;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageLine;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageYear;
import io.github.rkaushikdev.bushelworks.services.houses.Houses;
import io.github.rkaushikdev.bushelworks.services.parties.Parties;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The patronage table, built off settlements that are actually on file. */
class PatronageServiceTest {

    static final LocalDate OPENS = LocalDate.of(2024, 9, 1);
    static final LocalDate CLOSES = LocalDate.of(2025, 8, 31);
    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);
    static final BigDecimal SPOT = d("4.3500");

    Database database;
    RuleBook rules;
    ScaleTicket cornTicket;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        rules = HouseFixtures.rules();
        cornTicket = HouseFixtures.cornTicket();
    }

    /** Open an account, haul {@code count} loads numbered from {@code start}, and settle them. */
    void settle(String partyCode, int count, int start, LocalDate settled) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            String name = partyCode.charAt(0) + partyCode.substring(1).toLowerCase(Locale.ROOT) + " Farms";
            Parties.openAccount(uow, partyCode, name);
            for (int index = 0; index < count; index++) {
                ScaleHouse.recordLoad(
                        uow,
                        cornTicket.toBuilder()
                                .ticketNumber("A-" + (start + index))
                                .build(),
                        partyCode,
                        rules);
            }
            SettlementRun.settleProducer(uow, partyCode, "HOME", Houses.houseRulebook(), settled, SPOT);
            uow.commit();
        }
    }

    void oneProducer() {
        settle("MERRICK", 3, 10441, SETTLED);
    }

    void twoProducers() {
        settle("MERRICK", 3, 10441, SETTLED);
        settle("HOLLIS", 1, 20441, SETTLED);
    }

    PatronageYear yearOf() {
        return yearOf(null);
    }

    PatronageYear yearOf(Collection<String> members) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            return Patronage.buildYear(uow, OPENS, CLOSES, null, members);
        }
    }

    static List<String> codes(List<PatronageLine> lines) {
        return lines.stream().map(PatronageLine::partyCode).toList();
    }

    @Test
    void aHouseWithNoSettlementsHasAnEmptyYear() {
        PatronageYear year = yearOf();
        assertThat(year.lines()).isEmpty();
        assertThat(year.bushels()).isEqualTo(d("0.0000"));
    }

    @Test
    void aSettledProducerAppearsOnce() {
        oneProducer();
        assertThat(codes(yearOf().lines())).containsExactly("MERRICK");
    }

    @Test
    void theProducerCarriesTheNameOffThePartyTable() {
        oneProducer();
        assertThat(yearOf().lines().get(0).name()).isEqualTo("Merrick Farms");
    }

    @Test
    void aProducerWithNoPartyRowFallsBackToTheCode() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            for (int index = 0; index < 2; index++) {
                ScaleHouse.recordLoad(
                        uow,
                        cornTicket.toBuilder()
                                .ticketNumber("A-" + (30441 + index))
                                .build(),
                        "GHOST",
                        rules);
            }
            SettlementRun.settleProducer(uow, "GHOST", "HOME", Houses.houseRulebook(), SETTLED, SPOT);
            uow.commit();
        }
        assertThat(yearOf().lines().get(0).name()).isEqualTo("GHOST");
    }

    @Test
    void theBushelsComeOffTheGrossLines() {
        oneProducer();
        assertThat(yearOf().lines().get(0).bushels()).isGreaterThan(d("2000"));
    }

    @Test
    void theGrossIsTheSettlementGross() {
        oneProducer();
        assertThat(yearOf().lines().get(0).gross()).isPositive();
    }

    @Test
    void theMarginIsOnlyTheChargeKinds() {
        oneProducer();
        PatronageLine line = yearOf().lines().get(0);
        assertThat(line.margin()).isPositive().isLessThan(line.gross());
    }

    @Test
    void theLoadCountComesOffTheTickets() {
        oneProducer();
        assertThat(yearOf().lines().get(0).loads()).isEqualTo(3);
    }

    @Test
    void twoProducersBothLandOnTheTable() {
        twoProducers();
        assertThat(codes(yearOf().lines())).containsExactly("HOLLIS", "MERRICK");
    }

    @Test
    void theBiggerProducerRanksFirst() {
        twoProducers();
        assertThat(yearOf().byBushels().get(0).partyCode()).isEqualTo("MERRICK");
    }

    @Test
    void theYearTotalsAcrossProducers() {
        twoProducers();
        PatronageYear year = yearOf();
        List<BigDecimal> bushels =
                year.lines().stream().map(PatronageLine::bushels).toList();
        assertThat(year.bushels()).isEqualByComparingTo(TestDecimals.sum(bushels));
    }

    @Test
    void everybodyIsAMemberWhenNoRollIsGiven() {
        twoProducers();
        assertThat(yearOf().members()).hasSize(2);
    }

    @Test
    void aRollLeavesTheOthersOffTheMemberList() {
        twoProducers();
        assertThat(codes(yearOf(List.of("MERRICK")).members())).containsExactly("MERRICK");
    }

    @Test
    void anEmptyRollMeansNobodyIsAMember() {
        twoProducers();
        assertThat(yearOf(List.of()).members()).isEmpty();
    }

    @Test
    void aSettlementOutsideTheSpanIsLeftOut() {
        settle("MERRICK", 2, 10441, LocalDate.of(2024, 8, 1));
        assertThat(yearOf().lines()).isEmpty();
    }

    @Test
    void aSettlementAtAnotherSiteIsLeftOut() {
        oneProducer();
        PatronageYear year;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            year = Patronage.buildYear(uow, OPENS, CLOSES, "LURAY", null);
        }
        assertThat(year.lines()).isEmpty();
    }

    @Test
    void theSiteIsStampedOnTheYear() {
        oneProducer();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            PatronageYear year = Patronage.buildYear(uow, OPENS, CLOSES, "HOME", null);
            assertThat(year.locationCode()).isEqualTo("HOME");
            assertThat(year.lines()).hasSize(1);
        }
    }

    @Test
    void noSiteMeansEverySite() {
        oneProducer();
        assertThat(yearOf().locationCode()).isEqualTo("*");
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            PatronageYear year = Patronage.buildYear(uow, OPENS, CLOSES, "", null);
            assertThat(year.locationCode()).isEqualTo("*");
            assertThat(year.lines()).hasSize(1);
        }
    }

    @Test
    void aVoidedSettlementDropsOut() {
        oneProducer();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            for (SettlementRow row : uow.settlements().forDay(SETTLED)) {
                row.setVoided(true);
            }
            uow.commit();
        }
        assertThat(yearOf().lines()).isEmpty();
    }

    @Test
    void aYearThatClosesBeforeItOpensIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Patronage.buildYear(uow, CLOSES, OPENS))
                    .isInstanceOf(PatronageError.class)
                    .hasMessage("the year closes before it opens");
        }
    }

    @Test
    void theCropYearRunsSeptemberToAugust() {
        assertThat(Patronage.cropYear(2024))
                .isEqualTo(new CropYear(LocalDate.of(2024, 9, 1), LocalDate.of(2025, 8, 31)));
    }

    @Test
    void aCropYearPicksUpAnOctoberSettlement() {
        oneProducer();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Patronage.buildCropYear(uow, 2024).lines()).hasSize(1);
        }
    }

    @Test
    void aCropYearLeavesOutTheYearBefore() {
        oneProducer();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Patronage.buildCropYear(uow, 2023).lines()).isEmpty();
        }
    }

    @Test
    void theTopListIsBiggestFirst() {
        twoProducers();
        assertThat(Patronage.topProducers(yearOf())).containsExactly("MERRICK", "HOLLIS");
    }

    @Test
    void theTopListCanBeCutShort() {
        twoProducers();
        assertThat(Patronage.topProducers(yearOf(), 1)).containsExactly("MERRICK");
    }

    @Test
    void anAllocationSplitsThePool() {
        twoProducers();
        Map<String, BigDecimal> split = Patronage.allocate(yearOf(), d("10000.00"));
        assertThat(TestDecimals.sum(List.copyOf(split.values()))).isEqualTo(d("10000.00"));
    }

    @Test
    void aShareUnderTheMinimumIsDropped() {
        twoProducers();
        Map<String, BigDecimal> split = Patronage.allocate(yearOf(), d("10.00"), d("9.00"));
        assertThat(split).doesNotContainKey("HOLLIS");
    }

    @Test
    void droppingAShareDoesNotHandItToSomebodyElse() {
        twoProducers();
        PatronageYear year = yearOf();
        Map<String, BigDecimal> whole = year.allocations(d("1000.00"));
        Map<String, BigDecimal> trimmed = Patronage.allocate(year, d("1000.00"), d("300.00"));
        assertThat(trimmed).doesNotContainKey("HOLLIS");
        assertThat(trimmed.get("MERRICK")).isEqualTo(whole.get("MERRICK"));
    }

    @Test
    void theMarginKindsLeaveOutTheCheckoff() {
        assertThat(Patronage.MARGIN_KINDS).extracting(LineKind::value).doesNotContain("assessment");
    }

    @Test
    void theMarginKindsLeaveOutAdvances() {
        assertThat(Patronage.MARGIN_KINDS).extracting(LineKind::value).doesNotContain("advance");
    }

    @Test
    void theWholeYearIsOneQueryPerTable() {
        twoProducers();
        Counter counter;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            counter = Counter.during(database, () -> Patronage.buildYear(uow, OPENS, CLOSES));
        }
        assertThat(counter.selecting("settlement")).isEqualTo(1);
        assertThat(counter.selecting("settlement_ticket")).isEqualTo(1);
        assertThat(counter.selecting("settlement_line")).isEqualTo(1);
        assertThat(counter.selecting("party")).isEqualTo(1);
        assertThat(counter.count()).isEqualTo(4);
    }

    @Test
    void anEmptyYearAsksNothingButTheWindow() {
        Counter counter;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            counter = Counter.during(database, () -> Patronage.buildYear(uow, OPENS, CLOSES));
        }
        assertThat(counter.count()).isEqualTo(1);
    }
}
