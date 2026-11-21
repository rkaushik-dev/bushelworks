package io.github.rkaushikdev.bushelworks.persistence;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.persistence.TestDatabase.assertIntegrityError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConfigurationError;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.persistence.models.CommodityRow;
import io.github.rkaushikdev.bushelworks.persistence.models.Location;
import io.github.rkaushikdev.bushelworks.persistence.models.Party;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The database, its sessions, and the reference tables everything else points at. */
class PersistenceBaseTest {

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    static Party aParty(String code, String name) {
        Party party = new Party();
        party.setCode(code);
        party.setName(name);
        party.setSortName(name.toUpperCase(Locale.ROOT));
        return party;
    }

    static CommodityRow aCommodity(String testWeight, String baseMoisture) {
        CommodityRow commodity = new CommodityRow();
        commodity.setCode("X");
        commodity.setName("X");
        commodity.setStandardTestWeight(d(testWeight));
        commodity.setBaseMoisture(d(baseMoisture));
        return commodity;
    }

    static Optional<Party> partyCoded(EntityManager session, String code) {
        return session
                .createQuery("select p from Party p where p.code = :code", Party.class)
                .setParameter("code", code)
                .getResultList()
                .stream()
                .findFirst();
    }

    @Test
    void theSchemaHasTheReferenceTables() {
        assertThat(database.tableNames()).contains("party", "location", "commodity", "split_agreement");
    }

    @Test
    void constraintsAreNamedByTheConvention() {
        assertThat(new Schema(database.dataSource()).uniques("party")).containsKey("uq_party_code");
    }

    @Test
    void aPartyRoundTrips() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Party party = aParty("MERRICK", "Merrick Farms LLC");
            party.setSortName("MERRICK FARMS");
            party.setStateCode("KS");
            party.setOpenedOn(LocalDate.of(2014, 3, 1));
            uow.session().persist(party);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Party found = partyCoded(uow.session(), "MERRICK").orElseThrow();
            assertThat(found.getName()).isEqualTo("Merrick Farms LLC");
            assertThat(found.isActive()).isTrue();
            assertThat(found.isCheckoffExempt()).isFalse();
            assertThat(found.getCreatedAt()).isNotNull();
        }
    }

    @Test
    void aDuplicatePartyCodeIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aParty("MERRICK", "A"));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aParty("MERRICK", "B"));
                uow.commit();
            });
        }
    }

    @Test
    void aBlankPartyCodeIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aParty("", "A"));
                uow.commit();
            });
        }
    }

    @Test
    void aLocationRoundTrips() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Location location = new Location();
            location.setCode("HOME");
            location.setName("Home elevator");
            location.setStateCode("KS");
            location.setWarehouseLicence("KS-1174");
            location.setLicensedCapacity(1_450_000);
            uow.session().persist(location);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Location found = TestDatabase.first(uow.session(), Location.class).orElseThrow();
            assertThat(found.getLicensedCapacity()).contains(1_450_000);
            assertThat(found).hasToString("HOME Home elevator");
        }
    }

    @Test
    void aCommodityWithAZeroTestWeightIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aCommodity("0", "15"));
                uow.commit();
            });
        }
    }

    @Test
    void aCommodityWithAnImpossibleBaseMoistureIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aCommodity("56", "150"));
                uow.commit();
            });
        }
    }

    @Test
    void numericColumnsComeBackAsDecimals() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            CommodityRow corn = aCommodity("56", "15.0");
            corn.setCode("CORN");
            corn.setName("Yellow Corn");
            uow.session().persist(corn);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            CommodityRow found =
                    TestDatabase.first(uow.session(), CommodityRow.class).orElseThrow();
            assertThat(Columns.asDecimal(found.getStandardTestWeight())).isEqualByComparingTo("56");
            assertThat(found.getStandardTestWeight().scale()).isEqualTo(Columns.PRICE_SCALE);
        }
    }

    @Test
    void asDecimalCopesWithDoublesAndNull() {
        assertThat(Columns.asDecimal(56.0)).isEqualTo(d("56.0"));
        assertThat(Columns.asDecimal(null)).isEqualTo(BigDecimal.ZERO);
        assertThat(Columns.asDecimal(d("4.35"))).isEqualTo(d("4.35"));
    }

    @Test
    void theDescriptionNamesThePrimaryKey() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Party party = aParty("MERRICK", "A");
            uow.session().persist(party);
            uow.commit();
            assertThat(party.describe()).startsWith("<Party id=").isEqualTo("<Party id=" + party.getId() + ">");
        }
    }

    @Test
    void inSessionClosesWhatItOpens() {
        AtomicReference<EntityManager> opened = new AtomicReference<>();
        boolean wasOpen = database.inSession(session -> {
            opened.set(session);
            return session.isOpen();
        });
        assertThat(wasOpen).isTrue();
        assertThat(opened.get().isOpen()).isFalse();
    }

    @Test
    void anInMemoryDatabaseIsSharedAcrossSessions() {
        try (Database memory = Database.open("jdbc:h2:mem:shared-" + UUID.randomUUID())) {
            memory.migrations().upgrade();
            try (UnitOfWork uow = UnitOfWork.open(memory)) {
                uow.session().persist(aParty("HOLLIS", "Hollis"));
                uow.commit();
            }
            try (UnitOfWork uow = UnitOfWork.open(memory)) {
                assertThat(partyCoded(uow.session(), "HOLLIS")).isPresent();
            }
        }
    }

    @Test
    void anUnnamedInMemoryDatabaseIsRefused() {
        assertThatThrownBy(() -> Database.open("jdbc:h2:mem:"))
                .isInstanceOf(ConfigurationError.class)
                .hasMessageContaining("name it");
        assertThatThrownBy(() -> Database.open("jdbc:h2:mem:;DB_CLOSE_DELAY=-1"))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void anH2UrlIsToldWhichColumnNamesAreNotKeywords() {
        Settings settings = Settings.defaults();
        assertThat(Database.jdbcUrl(settings)).isEqualTo("jdbc:h2:file:./bushelworks;NON_KEYWORDS=VALUE,YEAR");
        String named = "jdbc:h2:mem:x;non_keywords=VALUE";
        assertThat(Database.jdbcUrl(settings.withDatabaseUrl(named))).isEqualTo(named);
        String elsewhere = "jdbc:postgresql://office/bushelworks";
        assertThat(Database.jdbcUrl(settings.withDatabaseUrl(elsewhere))).isEqualTo(elsewhere);
    }
}
