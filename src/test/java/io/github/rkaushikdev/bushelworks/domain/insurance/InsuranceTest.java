package io.github.rkaushikdev.bushelworks.domain.insurance;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InsuranceTest {

    static Unit aUnit(String unitNumber, String acres, String approvedYield) {
        return new Unit(
                unitNumber, "MERRICK", "CORN", 2024, d(acres), d(approvedYield), Set.of("HOME-PLACE", "NORTH-EIGHTY"));
    }

    static Unit aUnit() {
        return aUnit("0001-0001", "640", "165");
    }

    static Delivery aDelivery(String number, String farm, String bushels) {
        return new Delivery(number, farm, LocalDate.of(2024, 10, 3), d(bushels));
    }

    static Delivery aDelivery() {
        return aDelivery("A-10441", "HOME-PLACE", "20000");
    }

    @Test
    void aUnitNeedsANumberAcresAndAYield() {
        assertThatThrownBy(() -> aUnit("", "640", "165")).isInstanceOf(InsuranceError.class);
        assertThatThrownBy(() -> aUnit("0001-0001", "0", "165")).isInstanceOf(InsuranceError.class);
        assertThatThrownBy(() -> aUnit("0001-0001", "640", "0")).isInstanceOf(InsuranceError.class);
    }

    @Test
    void theGuaranteeIsAcresTimesTheApprovedYield() {
        assertThat(aUnit().guaranteedBushels()).isEqualTo(d("105600.0000"));
    }

    @Test
    void aUnitCoversTheFarmsOnIt() {
        Unit unit = aUnit();
        assertThat(unit.covers("HOME-PLACE")).isTrue();
        assertThat(unit.covers("SOUTH-QUARTER")).isFalse();
    }

    @Test
    void aDeliveryOfNegativeBushelsIsRefused() {
        assertThatThrownBy(() -> aDelivery("A-10441", "HOME-PLACE", "-1")).isInstanceOf(InsuranceError.class);
    }

    @Test
    void aDeliveryFromAFarmTheUnitDoesNotCoverIsRefused() {
        Production production = new Production(aUnit());
        assertThatThrownBy(() -> production.add(aDelivery("A-10441", "SOUTH-QUARTER", "20000")))
                .isInstanceOf(ConflictError.class);
    }

    @Test
    void theSameTicketTwiceIsRefused() {
        Production production = new Production(aUnit());
        production.add(aDelivery());
        assertThatThrownBy(() -> production.add(aDelivery())).isInstanceOf(ConflictError.class);
    }

    @Test
    void theDeliveredBushelsAddUp() {
        Production production =
                Insurance.build(aUnit(), List.of(aDelivery(), aDelivery("A-10442", "NORTH-EIGHTY", "30000")));
        assertThat(production.delivered()).isEqualTo(d("50000.0000"));
    }

    @Test
    void deliveriesFromOtherFarmsAreLeftOut() {
        Production production =
                Insurance.build(aUnit(), List.of(aDelivery(), aDelivery("A-10442", "SOUTH-QUARTER", "30000")));
        assertThat(production.delivered()).isEqualTo(d("20000.0000"));
    }

    @Test
    void farmStoredGrainCountsTowardProduction() {
        Production production = Insurance.build(aUnit(), List.of(aDelivery()), d("40000"));
        assertThat(production.total()).isEqualTo(d("60000.0000"));
        assertThat(production.delivered()).isEqualTo(d("20000.0000"));
    }

    @Test
    void theYieldPerAcreIsOfEverythingProduced() {
        Production production = Insurance.build(aUnit(), List.of(aDelivery("A-1", "HOME-PLACE", "64000")));
        assertThat(production.yieldPerAcre()).isEqualTo(d("100.00"));
    }

    @Test
    void aShortYearHasAClaim() {
        Production production = Insurance.build(aUnit(), List.of(aDelivery("A-1", "HOME-PLACE", "60000")));
        assertThat(production.hasAClaim()).isTrue();
        assertThat(production.shortfall()).isEqualTo(d("45600.0000"));
    }

    @Test
    void aGoodYearHasNoClaim() {
        Production production = Insurance.build(aUnit(), List.of(aDelivery("A-1", "HOME-PLACE", "120000")));
        assertThat(production.hasAClaim()).isFalse();
        assertThat(production.shortfall()).isEqualTo(d("0.0000"));
    }

    @Test
    void aYearExactlyOnTheGuaranteeHasNoClaim() {
        assertThat(Insurance.build(aUnit(), List.of(aDelivery("A-1", "HOME-PLACE", "105600")))
                        .hasAClaim())
                .isFalse();
    }

    @Test
    void thePercentageOfTheGuaranteeIsReported() {
        Production production = Insurance.build(aUnit(), List.of(aDelivery("A-1", "HOME-PLACE", "52800")));
        assertThat(production.percentOfGuarantee()).isEqualTo(d("50.0"));
    }

    @Test
    void aUnitWithNothingDeliveredIsWhollyShort() {
        Production production = Insurance.build(aUnit(), List.of());
        assertThat(production.shortfall()).isEqualTo(aUnit().guaranteedBushels());
        assertThat(production.percentOfGuarantee()).isEqualByComparingTo(d("0.0"));
    }

    @Test
    void theReportHasARowPerUnit() {
        Production first = Insurance.build(aUnit(), List.of(aDelivery()));
        Production second = Insurance.build(aUnit("0001-0002", "640", "165"), List.of());
        String[] rows = Insurance.toCsv(List.of(first, second)).strip().split("\n");
        assertThat(rows[0]).startsWith("unit_number,party_code");
        assertThat(rows).hasSize(3);
    }

    @Test
    void theReportCarriesTheGuaranteeAndTheShortfall() {
        String text = Insurance.toCsv(List.of(Insurance.build(aUnit(), List.of(aDelivery()))));
        assertThat(text).contains("105600.0000").contains("85600.0000");
    }

    @Test
    void theClaimsAreBiggestShortfallFirst() {
        Production small = Insurance.build(aUnit(), List.of(aDelivery("A-1", "HOME-PLACE", "100000")));
        Production large = Insurance.build(aUnit("0001-0002", "640", "165"), List.of());
        assertThat(Insurance.claims(List.of(small, large)))
                .extracting(item -> item.unit().unitNumber())
                .containsExactly("0001-0002", "0001-0001");
    }

    @Test
    void aBookWithNoClaimsComesBackEmpty() {
        assertThat(Insurance.claims(
                        List.of(Insurance.build(aUnit(), List.of(aDelivery("A-1", "HOME-PLACE", "120000"))))))
                .isEmpty();
    }

    @Test
    void reconcilingAgainstTheSettlementsShouldComeOutAtNothing() {
        assertThat(Insurance.reconcile(Insurance.build(aUnit(), List.of(aDelivery())), d("20000")))
                .isEqualTo(d("0.0000"));
    }

    @Test
    void aTicketWithTheWrongFarmShowsUpAsAGap() {
        assertThat(Insurance.reconcile(Insurance.build(aUnit(), List.of(aDelivery())), d("50000")))
                .isEqualTo(d("-30000.0000"));
    }
}
