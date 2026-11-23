package io.github.rkaushikdev.bushelworks.domain.settlement;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.TestDecimals.sum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettlementTest {

    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);

    static final List<SplitShare> LEASE = List.of(
            new SplitShare("BROCKMAN", d("25"), SplitRole.LANDLORD),
            new SplitShare("MERRICK", d("75"), SplitRole.TENANT));

    static SettlementBuilder builder() {
        return new SettlementBuilder("S-2024-0417", SETTLED, "MERRICK", "HOME");
    }

    static Settlement fullStatement() {
        SettlementBuilder b = builder();
        b.referenceTicket("A-10441");
        b.referenceContract("F-2024-118");
        b.addGross("GRAIN", "Corn, 932.8571 bu", d("932.8571"), d("4.3500"));
        b.addCharge(LineKind.DISCOUNT, "TW", "Light test weight", d("18.66"), Optional.empty(), Optional.of(d("0.02")));
        b.addCharge(LineKind.DRYING, "DRY", "Drying 3.2 points", d("139.93"));
        b.addCharge(LineKind.STORAGE, "STOR", "Storage to 20 Oct", d("42.00"));
        b.addCharge(LineKind.ASSESSMENT, "KSCORN", "Kansas Corn Commission", d("9.33"));
        b.addCharge(LineKind.ADVANCE, "ADV", "Advance 12 Oct", d("1000.00"));
        return b.build();
    }

    @Test
    void aStatementBalancesByConstruction() {
        Settlement statement = fullStatement();
        statement.checkBalances();
        assertThat(statement.gross()).isEqualTo(d("4057.93"));
        assertThat(statement.deductions()).isEqualTo(d("1209.92"));
        assertThat(statement.net()).isEqualTo(d("2848.01"));
    }

    @Test
    void linesComeBackInCanonicalOrder() {
        assertThat(fullStatement().lines())
                .extracting(SettlementLine::kind)
                .containsExactly(
                        LineKind.GROSS,
                        LineKind.DISCOUNT,
                        LineKind.DRYING,
                        LineKind.STORAGE,
                        LineKind.ASSESSMENT,
                        LineKind.ADVANCE);
    }

    @Test
    void reducingKindsCarryANegativeSign() {
        SettlementLine discount = fullStatement().ofKind(LineKind.DISCOUNT).get(0);
        assertThat(discount.dollars()).isEqualTo(d("18.66"));
        assertThat(discount.signed()).isEqualTo(d("-18.66"));
    }

    @Test
    void grossCarriesAPositiveSign() {
        assertThat(fullStatement().ofKind(LineKind.GROSS).get(0).signed()).isEqualTo(d("4057.93"));
    }

    @Test
    void valueAfterDiscountsIgnoresServicesAndLevies() {
        assertThat(fullStatement().valueAfterDiscounts()).isEqualTo(d("4039.27"));
    }

    @Test
    void aLineMayNotBeStoredNegative() {
        assertThatThrownBy(() -> new SettlementLine(LineKind.DISCOUNT, "TW", "nope", d("-1.00")))
                .isInstanceOf(SettlementError.class);
    }

    @Test
    void zeroLinesAreDroppedRatherThanPrinted() {
        SettlementBuilder b = builder();
        b.addGross("GRAIN", "Corn", d("100"), d("4.00"));
        b.addCharge(LineKind.DISCOUNT, "TW", "No discount", d("0.00"));
        assertThat(b.build().lines()).hasSize(1);
    }

    @Test
    void referencesAreRecordedOnce() {
        SettlementBuilder b = builder();
        b.addGross("GRAIN", "Corn", d("100"), d("4.00"));
        b.referenceTicket("A-1").referenceTicket("A-1").referenceTicket("A-2");
        b.referenceContract("F-1").referenceContract("F-1");
        Settlement statement = b.build();
        assertThat(statement.ticketNumbers()).containsExactly("A-1", "A-2");
        assertThat(statement.contractNumbers()).containsExactly("F-1");
    }

    @Test
    void runningTotalsAreVisibleWhileBuilding() {
        SettlementBuilder b = builder();
        b.addGross("GRAIN", "Corn", d("1000"), d("4.00"));
        b.addCharge(LineKind.DISCOUNT, "TW", "Test weight", d("20.00"));
        assertThat(b.runningGross()).isEqualTo(d("4000.00"));
        assertThat(b.runningDiscounts()).isEqualTo(d("20.00"));
    }

    @Test
    void subtotalsByKind() {
        Settlement statement = fullStatement();
        assertThat(statement.subtotal(LineKind.DRYING)).isEqualTo(d("139.93"));
        assertThat(statement.subtotal(LineKind.DEDUCTION)).isEqualTo(d("0.00"));
    }

    @Test
    void aTamperedStatementFailsItsOwnBalanceCheck() {
        Settlement statement = fullStatement();
        Settlement broken = new Settlement(
                statement.number(),
                statement.settledOn(),
                statement.partyId(),
                statement.locationCode(),
                statement.lines().subList(0, statement.lines().size() - 1));
        broken.checkBalances();
        assertThat(broken.net()).isEqualTo(d("3848.01"));
    }

    @Test
    void splitAcrossACropShareLease() {
        Settlement statement = fullStatement();
        Map<String, BigDecimal> parts = Settlements.split(statement, LEASE, LeaseTerms.SHARED);
        assertThat(sum(List.copyOf(parts.values()))).isEqualByComparingTo(statement.net());
        assertThat(parts.get("BROCKMAN")).isEqualTo(d("712.00"));
        assertThat(parts.get("MERRICK")).isEqualTo(d("2136.01"));
    }

    @Test
    void theTenantsAdvanceComesOffTheTenantsCheque() {
        Settlement statement = fullStatement();
        Map<String, BigDecimal> parts = Settlements.split(statement, LEASE, LeaseTerms.CROP_SHARE);
        assertThat(sum(List.copyOf(parts.values()))).isEqualByComparingTo(statement.net());
        // a quarter of everything but the advance, which is the tenant's alone
        assertThat(parts.get("BROCKMAN")).isEqualTo(d("962.00"));
        assertThat(parts.get("MERRICK")).isEqualTo(d("1886.01"));
        assertThat(Settlements.split(statement, LEASE).get("BROCKMAN")).isEqualTo(d("712.00"));
    }

    @Test
    void aLeaseCanPutStorageOnTheLandlord() {
        LeaseTerms terms = LeaseTerms.SHARED.bearing(LineKind.STORAGE, SplitRole.LANDLORD);
        assertThat(terms.bearerOf(LineKind.STORAGE)).contains(SplitRole.LANDLORD);
        assertThat(terms.bearerOf(LineKind.DRYING)).isEmpty();
        Map<String, BigDecimal> parts = Settlements.split(fullStatement(), LEASE, terms);
        Map<String, BigDecimal> shared = Settlements.split(fullStatement(), LEASE);
        // the landlord takes the whole 42.00 of storage instead of 10.50 of it
        assertThat(shared.get("BROCKMAN").subtract(parts.get("BROCKMAN"))).isEqualByComparingTo(d("31.50"));
    }

    @Test
    void twoLandlordsBearALandlordsLineBetweenThem() {
        List<SplitShare> shares = List.of(
                new SplitShare("BROCKMAN", d("20"), SplitRole.LANDLORD),
                new SplitShare("HOLLIS", d("20"), SplitRole.LANDLORD),
                new SplitShare("MERRICK", d("60"), SplitRole.TENANT));
        SettlementBuilder b = builder();
        b.addGross("GRAIN", "Corn", d("1000.0000"), d("4.0000"));
        b.addCharge(LineKind.STORAGE, "STOR", "Storage", d("100.01"));
        Map<String, BigDecimal> parts =
                Settlements.split(b.build(), shares, LeaseTerms.SHARED.bearing(LineKind.STORAGE, SplitRole.LANDLORD));
        assertThat(parts).containsEntry("BROCKMAN", d("749.99")).containsEntry("HOLLIS", d("750.00"));
        assertThat(parts).containsEntry("MERRICK", d("2400.00"));
    }

    @Test
    void aLeaseThatNamesARoleNobodyHoldsIsRefused() {
        List<SplitShare> owners = List.of(new SplitShare("A", d("50")), new SplitShare("B", d("50")));
        assertThatThrownBy(() -> Settlements.split(fullStatement(), owners, LeaseTerms.CROP_SHARE))
                .isInstanceOf(SettlementError.class)
                .hasMessageContaining("ADV on the tenant");
        assertThatThrownBy(() -> LeaseTerms.SHARED.bearing(LineKind.GROSS, SplitRole.TENANT))
                .isInstanceOf(SettlementError.class);
    }

    @Test
    void splitOfAnOddPennyStillAddsToTheNet() {
        SettlementBuilder b = builder();
        b.addGross("GRAIN", "Corn", d("1000.0000"), d("4.0001"));
        Settlement statement = b.build();
        List<SplitShare> shares = List.of(new SplitShare("A", d("50")), new SplitShare("B", d("50")));
        Map<String, BigDecimal> parts = Settlements.split(statement, shares);
        assertThat(sum(List.copyOf(parts.values()))).isEqualByComparingTo(statement.net());
    }

    @Test
    void splittingAcrossNobodyIsRefused() {
        assertThatThrownBy(() -> Settlements.split(fullStatement(), List.of())).isInstanceOf(SettlementError.class);
    }

    @Test
    void combiningStatementsAddsTheNets() {
        assertThat(Settlements.combine(List.of(fullStatement(), fullStatement())))
                .isEqualTo(d("5696.02"));
    }
}
