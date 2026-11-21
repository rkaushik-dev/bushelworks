package io.github.rkaushikdev.bushelworks.persistence;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.QuickStatements;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import io.github.rkaushikdev.bushelworks.domain.sales.Destination;
import io.github.rkaushikdev.bushelworks.domain.sales.SaleContract;
import io.github.rkaushikdev.bushelworks.domain.sales.SaleKind;
import io.github.rkaushikdev.bushelworks.domain.sales.Specification;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementLine;
import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import io.github.rkaushikdev.bushelworks.persistence.models.ContractRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SaleRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SampleRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementLineRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rows to value objects and back, through the database where it matters. */
class MappersTest {

    Database database;
    ScaleTicket cornTicket;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        cornTicket = HouseFixtures.cornTicket();
    }

    private <T> T stored(Class<T> type) {
        return database.inSession(session -> TestDatabase.first(session, type).orElseThrow());
    }

    private void persist(Object row) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.session().persist(row);
            uow.commit();
        }
    }

    private Settlement statement(String number) {
        return QuickStatements.settlementFor(
                        number, cornTicket, d("4.3500"), HouseFixtures.rules(), "MERRICK", LocalDate.of(2024, 10, 20))
                .build();
    }

    /** Two objects with the same values, a decimal's places aside, as the domain compares them. */
    private static void assertSameValues(Object actual, Object expected) {
        assertThat(actual)
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(expected);
    }

    @Test
    void aTicketSurvivesARoundTripThroughTheDatabase() {
        persist(Mappers.ticketToRow(cornTicket, "MERRICK"));
        ScaleTicket restored = database.inSession(session -> Mappers.ticketFromRow(session.createQuery(
                        "select t from ScaleTicketRow t where t.ticketNumber = 'A-10441'", ScaleTicketRow.class)
                .getSingleResult()));

        assertThat(restored.netPounds()).isEqualByComparingTo(cornTicket.netPounds());
        assertSameValues(restored.sample(), cornTicket.sample());
        assertThat(restored.sample().keySet()).isEqualTo(cornTicket.sample().keySet());
        assertThat(restored.disposition()).isEqualTo(Disposition.CASH_SALE);
        assertThat(restored.parties()).containsExactly("MERRICK");
        assertThat(restored.driver()).isEqualTo("R. Merrick");
    }

    @Test
    void aSplitTicketKeepsItsRoles() {
        ScaleTicket shared = cornTicket.toBuilder()
                .splits(List.of(
                        new SplitShare("BROCKMAN", d("25"), SplitRole.LANDLORD),
                        new SplitShare("MERRICK", d("75"), SplitRole.TENANT)))
                .build();
        persist(Mappers.ticketToRow(shared, "MERRICK"));
        ScaleTicket restored = database.inSession(session -> Mappers.ticketFromRow(
                TestDatabase.first(session, ScaleTicketRow.class).orElseThrow()));
        Map<String, SplitRole> roles =
                restored.splits().stream().collect(Collectors.toMap(SplitShare::partyId, SplitShare::role));
        assertThat(roles).containsEntry("BROCKMAN", SplitRole.LANDLORD).containsEntry("MERRICK", SplitRole.TENANT);
    }

    @Test
    void anUnknownFactorInTheDatabaseIsRefused() {
        ScaleTicketRow row = Mappers.ticketToRow(cornTicket, "MERRICK");
        SampleRow colour = new SampleRow();
        colour.setFactor("colour");
        colour.setValue(d("1"));
        row.addSample(colour);
        persist(row);
        database.inSession(session -> {
            ScaleTicketRow stored =
                    TestDatabase.first(session, ScaleTicketRow.class).orElseThrow();
            assertThatThrownBy(() -> Mappers.ticketFromRow(stored))
                    .isInstanceOf(MappingError.class)
                    .hasMessage("colour is not a grade factor")
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .satisfies(refused ->
                            assertThat(((MappingError) refused).code()).isEqualTo("mapping_error"));
            return stored;
        });
    }

    @Test
    void aFuturesSymbolReadsBackAsAMonth() {
        assertThat(Mappers.futuresMonthFromSymbol("CORNZ24", "CORN")).contains(new FuturesMonth("CORN", "Z", 2024));
        assertThat(Mappers.futuresMonthFromSymbol(null, "CORN")).isEmpty();
        assertThat(Mappers.futuresMonthFromSymbol("", "CORN")).isEmpty();
    }

    @Test
    void aSymbolForTheWrongCommodityIsRefused() {
        assertThatThrownBy(() -> Mappers.futuresMonthFromSymbol("SOYBX24", "CORN"))
                .isInstanceOf(MappingError.class)
                .hasMessage("SOYBX24 is not a CORN contract");
    }

    @Test
    void aMalformedSymbolIsRefused() {
        assertThatThrownBy(() -> Mappers.futuresMonthFromSymbol("CORNZZZ", "CORN"))
                .isInstanceOf(MappingError.class)
                .hasMessage("cannot read a delivery month out of CORNZZZ");
        assertThatThrownBy(() -> Mappers.futuresMonthFromSymbol("CORNB24", "CORN"))
                .isInstanceOf(MappingError.class);
        assertThatThrownBy(() -> Mappers.futuresMonthFromSymbol("CORNZ2", "CORN"))
                .isInstanceOf(MappingError.class);
    }

    @Test
    void aContractSurvivesARoundTrip() {
        PurchaseContract contract = PurchaseContract.builder()
                .number("H-2024-042")
                .kind(ContractKind.HTA)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d("10000"))
                .window(new DeliveryWindow(LocalDate.of(2024, 10, 1), LocalDate.of(2024, 11, 30)))
                .futuresMonth(new FuturesMonth("CORN", "Z", 2024))
                .futuresPrice(d("4.8500"))
                .build();
        persist(Mappers.contractToRow(contract));
        PurchaseContract restored = Mappers.contractFromRow(stored(ContractRow.class));
        assertSameValues(restored, contract);
        assertThat(restored.isFullyPriced()).isFalse();
    }

    @Test
    void aPricedForwardSurvivesARoundTrip() {
        PurchaseContract contract = PurchaseContract.builder()
                .number("F-2024-118")
                .kind(ContractKind.FORWARD)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d("20000"))
                .window(new DeliveryWindow(LocalDate.of(2024, 10, 1), LocalDate.of(2024, 11, 30)))
                .flatPrice(d("4.6200"))
                .build();
        persist(Mappers.contractToRow(contract));
        PurchaseContract restored = Mappers.contractFromRow(stored(ContractRow.class));
        assertThat(restored.unitPrice()).isEqualByComparingTo("4.6200");
    }

    @Test
    void aSaleSurvivesARoundTrip() {
        SaleContract sale = SaleContract.builder()
                .number("S-2024-0088")
                .kind(SaleKind.BASIS)
                .buyer("Salina Terminal")
                .destination(Destination.TERMINAL)
                .commodityCode("CORN")
                .originCode("HOME")
                .writtenOn(LocalDate.of(2024, 9, 5))
                .bushels(d("50000"))
                .window(new DeliveryWindow(LocalDate.of(2024, 11, 1), LocalDate.of(2024, 12, 31)))
                .specification(new Specification(d("15.0"), d("54.0"), d("5.0"), d("3.0")))
                .futuresMonth(new FuturesMonth("CORN", "Z", 2024))
                .basis(d("-0.3500"))
                .freightPerBushel(d("0.1800"))
                .shipped(d("3500"))
                .build();
        persist(Mappers.saleToRow(sale));
        SaleRow row = stored(SaleRow.class);
        assertThat(row.getStatus()).isEqualTo("open");
        assertThat(row.getFuturesSymbol()).contains("CORNZ24");
        assertSameValues(Mappers.saleFromRow(row), sale);
    }

    @Test
    void aSettlementSurvivesARoundTrip() {
        Settlement original = statement("S-2024-0417");
        persist(Mappers.settlementToRow(original));
        Settlement restored = database.inSession(session -> Mappers.settlementFromRow(
                TestDatabase.first(session, SettlementRow.class).orElseThrow()));

        assertThat(restored.net()).isEqualByComparingTo(original.net());
        assertThat(restored.gross()).isEqualByComparingTo(original.gross());
        assertThat(restored.lines())
                .extracting(SettlementLine::kind)
                .isEqualTo(original.lines().stream().map(SettlementLine::kind).toList());
        assertThatCode(restored::checkBalances).doesNotThrowAnyException();
    }

    @Test
    void theStoredTotalsMatchTheRecomputedOnes() {
        Settlement original = statement("S-1");
        persist(Mappers.settlementToRow(original));
        SettlementRow row = stored(SettlementRow.class);
        assertThat(row.getNet()).isEqualByComparingTo(original.net());
        assertThat(row.getGross()).isEqualByComparingTo(original.gross());
        assertThat(row.getDeductions()).isEqualByComparingTo(original.deductions());
    }

    @Test
    void lineSequenceIsWrittenInCanonicalOrder() {
        persist(Mappers.settlementToRow(statement("S-1")));
        database.inSession(session -> {
            SettlementRow row = TestDatabase.first(session, SettlementRow.class).orElseThrow();
            List<Integer> sequences =
                    row.getLines().stream().map(SettlementLineRow::getSequence).toList();
            assertThat(sequences).hasSizeGreaterThan(1);
            assertThat(sequences)
                    .containsExactlyElementsOf(
                            IntStream.rangeClosed(1, sequences.size()).boxed().toList());
            assertThat(row.getLines().get(0).getKind()).isEqualTo(LineKind.GROSS.value());
            return row;
        });
    }

    @Test
    void aTicketWithNoOptionalFieldsMapsToNulls() {
        ScaleTicket plain = cornTicket.toBuilder()
                .truckId("")
                .driver("")
                .farm("")
                .fieldName("")
                .build();
        ScaleTicketRow row = Mappers.ticketToRow(plain, "MERRICK");
        assertThat(row.getTruckId()).isEmpty();
        assertThat(row.getDriver()).isEmpty();
        assertThat(Mappers.ticketFromRow(row).driver()).isEmpty();
    }

    @Test
    void aSampleIsStoredInAStableOrder() {
        ScaleTicketRow row = Mappers.ticketToRow(cornTicket, "MERRICK");
        List<String> factors =
                row.getSamples().stream().map(SampleRow::getFactor).toList();
        assertThat(factors).isSorted().contains(Factor.MOISTURE.value());
    }
}
