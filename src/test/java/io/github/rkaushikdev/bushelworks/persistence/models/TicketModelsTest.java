package io.github.rkaushikdev.bushelworks.persistence.models;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.persistence.TestDatabase.assertIntegrityError;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Scale tickets, their probe readings and their shares. */
class TicketModelsTest {

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    static ScaleTicketRow aTicket() {
        return aTicket(ticket -> {});
    }

    static ScaleTicketRow aTicket(Consumer<ScaleTicketRow> overrides) {
        ScaleTicketRow ticket = new ScaleTicketRow();
        ticket.setTicketNumber("A-10441");
        ticket.setLocationCode("HOME");
        ticket.setScaleDate(LocalDate.of(2024, 10, 3));
        ticket.setCommodityCode("CORN");
        ticket.setPartyCode("MERRICK");
        ticket.setGrossPounds(d("78420"));
        ticket.setTarePounds(d("26180"));
        ticket.setDisposition("cash_sale");
        overrides.accept(ticket);
        return ticket;
    }

    static SampleRow aSample(String factor, String value) {
        SampleRow sample = new SampleRow();
        sample.setFactor(factor);
        sample.setValue(d(value));
        return sample;
    }

    static TicketSplitRow aShare(String partyCode, BigDecimal percent) {
        TicketSplitRow share = new TicketSplitRow();
        share.setPartyCode(partyCode);
        share.setPercent(percent);
        return share;
    }

    @Test
    void theTicketTablesExist() {
        assertThat(database.tableNames()).contains("scale_ticket", "ticket_sample", "ticket_split");
    }

    @Test
    void aTicketRoundTripsWithItsChildren() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow ticket = aTicket();
            SampleRow moisture = aSample("moisture", "18.2");
            moisture.setInstrument("GAC2500");
            ticket.setSamples(List.of(moisture, aSample("test_weight", "56.5")));
            ticket.setSplits(List.of(aShare("MERRICK", d("100"))));
            uow.session().persist(ticket);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow found = uow.session()
                    .createQuery(
                            "select t from ScaleTicketRow t where t.ticketNumber = 'A-10441'", ScaleTicketRow.class)
                    .getSingleResult();
            assertThat(found.getNetPounds()).isEqualTo(d("52240"));
            assertThat(found.getSamples())
                    .extracting(SampleRow::getFactor)
                    .containsExactlyInAnyOrder("moisture", "test_weight");
            assertThat(found.getSplits().get(0).getPercent()).isEqualTo(d("100.0000"));
            assertThat(found).hasToString("HOME/A-10441");
        }
    }

    @Test
    void aTicketNumberIsUniquePerLocationNotGlobally() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(aTicket());
            uow.session().persist(aTicket(ticket -> ticket.setLocationCode("LURAY")));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aTicket());
                uow.commit();
            });
        }
    }

    @Test
    void aTareAboveGrossIsRefusedByTheDatabase() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aTicket(ticket -> {
                    ticket.setGrossPounds(d("26180"));
                    ticket.setTarePounds(d("78420"));
                }));
                uow.commit();
            });
        }
    }

    @Test
    void dockageOutsideTheRangeIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.session().persist(aTicket(ticket -> ticket.setDockagePercent(d("100"))));
                uow.commit();
            });
        }
    }

    @Test
    void oneReadingPerFactorPerTicket() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow ticket = aTicket();
            ticket.setSamples(List.of(aSample("moisture", "18.2"), aSample("moisture", "18.4")));
            assertIntegrityError(() -> {
                uow.session().persist(ticket);
                uow.commit();
            });
        }
    }

    @Test
    void aNegativeReadingIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow ticket = aTicket();
            ticket.setSamples(List.of(aSample("moisture", "-1")));
            assertIntegrityError(() -> {
                uow.session().persist(ticket);
                uow.commit();
            });
        }
    }

    @Test
    void aShareOverOneHundredIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow ticket = aTicket();
            ticket.setSplits(List.of(aShare("MERRICK", d("101"))));
            assertIntegrityError(() -> {
                uow.session().persist(ticket);
                uow.commit();
            });
        }
    }

    @Test
    void deletingATicketTakesItsChildrenWithIt() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow ticket = aTicket();
            ticket.setSamples(List.of(aSample("moisture", "18.2")));
            ticket.setSplits(List.of(aShare("MERRICK", d("100"))));
            uow.session().persist(ticket);
            uow.commit();
            uow.session().remove(ticket);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(TestDatabase.all(uow.session(), SampleRow.class)).isEmpty();
            assertThat(TestDatabase.all(uow.session(), TicketSplitRow.class)).isEmpty();
        }
    }

    @Test
    void aTicketStartsUnvoidedAndUnsettled() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow ticket = aTicket();
            uow.session().persist(ticket);
            uow.commit();
            assertThat(ticket.isVoided()).isFalse();
            assertThat(ticket.isSettled()).isFalse();
        }
    }

    @Test
    void theModelsPackageStillHoldsTheReferenceTables() {
        assertThat(Party.class.getAnnotation(Table.class).name()).isEqualTo("party");
        assertThat(Location.class.getAnnotation(Table.class).name()).isEqualTo("location");
        assertThat(CommodityRow.class.getAnnotation(Table.class).name()).isEqualTo("commodity");
        assertThat(List.of(Party.class, Location.class, CommodityRow.class))
                .allMatch(type -> type.getPackage().equals(ScaleTicketRow.class.getPackage()));
    }
}
