package io.github.rkaushikdev.bushelworks.domain.application;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApplicationTest {

    static final LocalDate DAY = LocalDate.of(2024, 10, 15);

    static PurchaseContract.Builder contract(String number, String bushels, LocalDate closes, String price) {
        return PurchaseContract.builder()
                .number(number)
                .kind(ContractKind.FORWARD)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 1))
                .bushels(d(bushels))
                .window(new DeliveryWindow(LocalDate.of(2024, 9, 1), closes))
                .flatPrice(d(price));
    }

    static final PurchaseContract EARLY = contract("F-01", "10000", LocalDate.of(2024, 10, 31), "4.50")
            .writtenOn(LocalDate.of(2024, 6, 1))
            .build();
    static final PurchaseContract LATE = contract("F-02", "10000", LocalDate.of(2024, 12, 31), "4.80")
            .writtenOn(LocalDate.of(2024, 4, 1))
            .build();

    static List<String> numbers(List<Application> applications) {
        return applications.stream().map(a -> a.contractNumber().orElse(null)).toList();
    }

    @Test
    void ledgerStartsEmpty() {
        ApplicationLedger ledger = new ApplicationLedger();
        assertThat(ledger.applied("F-01")).isEqualTo(d("0.0000"));
        assertThat(ledger.remaining(EARLY)).isEqualTo(d("10200.0000"));
        assertThat(ledger.isFilled(EARLY)).isFalse();
        assertThat(ledger.shortfall(EARLY)).isEqualTo(d("9800.0000"));
    }

    @Test
    void windowRuleFillsTheContractClosingFirst() {
        ApplicationLedger ledger = new ApplicationLedger();
        List<Application> applications =
                Applications.applyDelivery("A-1", d("5000"), List.of(LATE, EARLY), ledger, DAY);
        assertThat(numbers(applications)).containsExactly("F-01");
        assertThat(ledger.applied("F-01")).isEqualTo(d("5000.0000"));
    }

    @Test
    void bestPriceRuleFillsTheDearestContractFirst() {
        List<Application> applications = Applications.applyDelivery(
                "A-1",
                d("5000"),
                List.of(EARLY, LATE),
                new ApplicationLedger(),
                DAY,
                SelectionRule.BEST_PRICE,
                OverDelivery.STORE);
        assertThat(numbers(applications)).containsExactly("F-02");
    }

    @Test
    void oldestRuleFillsByTheDateWritten() {
        List<Application> applications = Applications.applyDelivery(
                "A-1",
                d("5000"),
                List.of(EARLY, LATE),
                new ApplicationLedger(),
                DAY,
                SelectionRule.OLDEST,
                OverDelivery.STORE);
        assertThat(numbers(applications)).containsExactly("F-02");
    }

    @Test
    void aDeliverySpillsOntoTheNextContract() {
        List<Application> applications =
                Applications.applyDelivery("A-1", d("12000"), List.of(EARLY, LATE), new ApplicationLedger(), DAY);
        assertThat(numbers(applications)).containsExactly("F-01", "F-02");
        assertThat(applications.get(0).bushels()).isEqualTo(d("10200.0000"));
        assertThat(applications.get(1).bushels()).isEqualTo(d("1800.0000"));
    }

    @Test
    void theToleranceCeilingIsWhatAContractWillTake() {
        ApplicationLedger ledger = new ApplicationLedger();
        Applications.applyDelivery("A-1", d("10200"), List.of(EARLY), ledger, DAY);
        assertThat(ledger.remaining(EARLY)).isEqualTo(d("0.0000"));
    }

    @Test
    void overageGoesToStorageByDefault() {
        List<Application> applications =
                Applications.applyDelivery("A-1", d("11000"), List.of(EARLY), new ApplicationLedger(), DAY);
        Application last = applications.get(applications.size() - 1);
        assertThat(last.contractNumber()).isEmpty();
        assertThat(last.disposition()).isEqualTo("store");
        assertThat(last.bushels()).isEqualTo(d("800.0000"));
    }

    @Test
    void overageCanBeSoldSpotInstead() {
        List<Application> applications = Applications.applyDelivery(
                "A-1",
                d("11000"),
                List.of(EARLY),
                new ApplicationLedger(),
                DAY,
                SelectionRule.WINDOW,
                OverDelivery.SPOT);
        assertThat(applications.get(applications.size() - 1).disposition()).isEqualTo("spot");
    }

    @Test
    void aHouseThatRefusesOverageSaysSo() {
        assertThatThrownBy(() -> Applications.applyDelivery(
                        "A-1",
                        d("11000"),
                        List.of(EARLY),
                        new ApplicationLedger(),
                        DAY,
                        SelectionRule.WINDOW,
                        OverDelivery.REFUSE))
                .isInstanceOf(ApplicationError.class);
    }

    @Test
    void aContractIsFilledInsideItsTolerance() {
        ApplicationLedger ledger = new ApplicationLedger();
        Applications.applyDelivery("A-1", d("9800"), List.of(EARLY), ledger, DAY);
        assertThat(ledger.isFilled(EARLY)).isTrue();
        assertThat(ledger.shortfall(EARLY)).isEqualTo(d("0.0000"));
    }

    @Test
    void nothingToApplyIsAnError() {
        assertThatThrownBy(
                        () -> Applications.applyDelivery("A-1", d("0"), List.of(EARLY), new ApplicationLedger(), DAY))
                .isInstanceOf(ApplicationError.class);
    }

    @Test
    void eligibilityFiltersPartyCommodityWindowAndRoom() {
        PurchaseContract otherParty = contract("F-03", "5000", LocalDate.of(2024, 12, 31), "4.70")
                .partyId("HOLLIS")
                .build();
        PurchaseContract otherCrop = contract("F-04", "5000", LocalDate.of(2024, 12, 31), "11.20")
                .commodityCode("SOYB")
                .build();
        PurchaseContract closedWindow =
                contract("F-05", "5000", LocalDate.of(2024, 9, 30), "4.55").build();
        List<PurchaseContract> found = Applications.eligible(
                List.of(EARLY, LATE, otherParty, otherCrop, closedWindow),
                "CORN",
                "MERRICK",
                DAY,
                new ApplicationLedger());
        assertThat(found).extracting(PurchaseContract::number).containsExactly("F-01", "F-02");
    }

    @Test
    void aFullContractDropsOutOfEligibility() {
        ApplicationLedger ledger = new ApplicationLedger();
        Applications.applyDelivery("A-1", d("10200"), List.of(EARLY), ledger, DAY);
        List<PurchaseContract> found = Applications.eligible(List.of(EARLY, LATE), "CORN", "MERRICK", DAY, ledger);
        assertThat(found).extracting(PurchaseContract::number).containsExactly("F-02");
    }

    @Test
    void aCancelledContractIsNotEligible() {
        List<PurchaseContract> found =
                Applications.eligible(List.of(EARLY.cancel(), LATE), "CORN", "MERRICK", DAY, new ApplicationLedger());
        assertThat(found).extracting(PurchaseContract::number).containsExactly("F-02");
    }

    @Test
    void anUnpricedContractSortsLastUnderBestPrice() {
        PurchaseContract unpriced = PurchaseContract.builder()
                .number("H-09")
                .kind(ContractKind.HTA)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 4, 1))
                .bushels(d("5000"))
                .window(new DeliveryWindow(LocalDate.of(2024, 9, 1), LocalDate.of(2024, 12, 31)))
                .futuresMonth(new FuturesMonth("CORN", "Z", 2024))
                .futuresPrice(d("4.85"))
                .build();
        List<PurchaseContract> ordered =
                Applications.orderFor(SelectionRule.BEST_PRICE, List.of(unpriced, EARLY, LATE));
        assertThat(ordered).extracting(PurchaseContract::number).containsExactly("F-02", "F-01", "H-09");
    }

    @Test
    void anApplicationOfNothingIsRefused() {
        assertThatThrownBy(() -> new Application("A-1", Optional.of("F-01"), d("0"), DAY, "contract"))
                .isInstanceOf(ApplicationError.class);
    }

    @Test
    void ledgerReportsWhichContractsWereTouched() {
        ApplicationLedger ledger = new ApplicationLedger();
        Applications.applyDelivery("A-1", d("12000"), List.of(EARLY, LATE), ledger, DAY);
        assertThat(ledger.contractsTouched()).containsExactly("F-01", "F-02");
    }
}
