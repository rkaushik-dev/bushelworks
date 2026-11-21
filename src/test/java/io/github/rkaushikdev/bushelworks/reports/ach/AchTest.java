package io.github.rkaushikdev.bushelworks.reports.ach;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The bank's payment file, record by record. */
class AchTest {

    static final Originator HOUSE =
            new Originator("BUSHELWORKS ELEVATOR", "1481003377", "101000187", "FARMERS STATE BANK");

    static final LocalDate CREATED = LocalDate.of(2024, 10, 24);
    static final LocalDate EFFECTIVE = LocalDate.of(2024, 10, 25);

    static Credit aCredit() {
        return aCredit("MERRICK", d("2848.01"), "4417002391");
    }

    static Credit aCredit(String partyCode, BigDecimal dollars, String accountNumber) {
        return new Credit(partyCode, "MERRICK FARMS LLC", "101000187", accountNumber, dollars, "S-2024-0417");
    }

    static Credit hollis(String dollars) {
        return aCredit("HOLLIS", d(dollars), "4417002391");
    }

    static String build(List<Credit> credits) {
        return Ach.build(HOUSE, credits, CREATED, EFFECTIVE, "101000187");
    }

    static List<String> lines(String text) {
        return Arrays.asList(text.strip().split("\n"));
    }

    @Test
    void theCheckDigitComesOutOfTheFirstEight() {
        assertThat(Ach.checkDigit("101000187")).isEqualTo(7);
        assertThat(Ach.routingIsValid("101000187")).isTrue();
    }

    @Test
    void aBadCheckDigitIsCaught() {
        assertThat(Ach.routingIsValid("101000188")).isFalse();
    }

    @Test
    void aShortRoutingNumberIsNotValid() {
        assertThat(Ach.routingIsValid("10100018")).isFalse();
        assertThat(Ach.routingIsValid("abcdefghi")).isFalse();
    }

    @Test
    void anOriginatorNeedsANineDigitRoutingNumber() {
        assertThatThrownBy(() -> new Originator("X", "1481003377", "1010001", "X"))
                .isInstanceOf(AchError.class)
                .hasMessage("'1010001' is not a routing number");
    }

    @Test
    void anOriginatorCompanyIdIsTenCharacters() {
        assertThatThrownBy(() -> new Originator("X", "148100", "101000187", "X"))
                .isInstanceOf(AchError.class)
                .hasMessage("a company id is ten characters");
    }

    @Test
    void aCreditHasToBePositive() {
        assertThatThrownBy(() -> aCredit("MERRICK", d("0"), "4417002391"))
                .isInstanceOf(AchError.class)
                .hasMessage("MERRICK: an ACH credit has to be positive");
    }

    @Test
    void aCreditNeedsAnAccountNumber() {
        assertThatThrownBy(() -> aCredit("MERRICK", d("2848.01"), ""))
                .isInstanceOf(AchError.class)
                .hasMessage("MERRICK: no account number");
    }

    @Test
    void aCreditCarriesItsAmountInCents() {
        assertThat(aCredit().cents()).isEqualTo(284801);
    }

    @Test
    void everyRecordIsNinetyFourCharacters() {
        assertThat(lines(build(List.of(aCredit())))).allMatch(line -> line.length() == Ach.RECORD_LENGTH);
    }

    @Test
    void theFileIsBlockedToTenRecords() {
        assertThat(lines(build(List.of(aCredit()))).size() % Ach.BLOCKING_FACTOR)
                .isZero();
    }

    @Test
    void thePaddingIsAllNines() {
        List<String> lines = lines(build(List.of(aCredit())));
        assertThat(lines.get(lines.size() - 1)).isEqualTo("9".repeat(Ach.RECORD_LENGTH));
    }

    @Test
    void theFileStartsWithAHeaderRecord() {
        String text = build(List.of(aCredit()));
        assertThat(text).startsWith("101").contains("FARMERS STATE BANK", "BUSHELWORKS ELEVATOR");
    }

    @Test
    void theBatchHeaderNamesTheEffectiveDay() {
        assertThat(build(List.of(aCredit()))).contains("241025");
    }

    @Test
    void anEntryCarriesTheProducerAndTheAmount() {
        String entry = lines(build(List.of(aCredit()))).stream()
                .filter(line -> line.startsWith("622"))
                .findFirst()
                .orElseThrow();
        assertThat(entry).contains("MERRICK", "0000284801");
    }

    @Test
    void theBatchControlTotalsTheEntries() {
        List<Credit> credits = List.of(aCredit(), hollis("1000.00"));
        String control = lines(build(credits)).stream()
                .filter(line -> line.startsWith("8220"))
                .findFirst()
                .orElseThrow();
        assertThat(control).contains(String.format(Locale.ROOT, "%012d", Ach.totalCents(credits)));
        assertThat(control.substring(4, 10)).isEqualTo("000002");
    }

    @Test
    void theFileControlTotalsTheSameAmount() {
        List<Credit> credits = List.of(aCredit(), hollis("1000.00"));
        List<String> controls = lines(build(credits)).stream()
                .filter(line -> line.startsWith("9") && line.chars().distinct().count() > 1)
                .toList();
        assertThat(controls.get(controls.size() - 1))
                .contains(String.format(Locale.ROOT, "%012d", Ach.totalCents(credits)));
    }

    @Test
    void entriesAreNumberedInOrder() {
        List<String> entries = lines(build(List.of(aCredit(), hollis("2848.01")))).stream()
                .filter(line -> line.startsWith("622"))
                .toList();
        assertThat(entries.get(0)).endsWith("0000001");
        assertThat(entries.get(1)).endsWith("0000002");
    }

    @Test
    void anEmptyFileIsRefused() {
        assertThatThrownBy(() -> build(List.of())).isInstanceOf(AchError.class);
    }

    @Test
    void theFileEndsWithANewline() {
        assertThat(build(List.of(aCredit()))).endsWith("\n");
    }

    @Test
    void totalCentsAddsTheCredits() {
        assertThat(Ach.totalCents(List.of(aCredit(), aCredit("MERRICK", d("0.99"), "4417002391"))))
                .isEqualTo(284801 + 99);
    }

    @Test
    void theFileIsLaidOutFieldByField() {
        Credit hollis = new Credit(
                "HOLLIS",
                "HOLLIS AND SONS GRAIN PARTNERSHIP OF KANSAS",
                "091000019",
                "77",
                d("1000.005"),
                "S-2024-0418");
        String filler = "9".repeat(94);
        assertThat(build(List.of(aCredit(), hollis)))
                .isEqualTo(String.join(
                                "\n",
                                "101 101000187 1481003372410240000A094101FARMERS STATE BANK     BUSHELWORKS ELEVATOR"
                                        + "           ",
                                "5220BUSHELWORKS ELEV                    1481003377PPDGRAIN PAY       241025   "
                                        + "1101000180000001",
                                "6221010001874417002391       0000284801MERRICK        MERRICK FARMS LLC       "
                                        + "0101000180000001",
                                "62209100001977               0000100000HOLLIS         HOLLIS AND SONS GRAIN   "
                                        + "0101000180000002",
                                "822000000200192000190000000000000000003848011481003377                         "
                                        + "101000180000001",
                                "9000001000001000000060019200019000000000000000000384801" + " ".repeat(39),
                                filler,
                                filler,
                                filler,
                                filler)
                        + "\n");
    }

    @Test
    void aHalfCentGoesToTheEvenCent() {
        assertThat(aCredit("MERRICK", d("0.005"), "1").cents()).isZero();
        assertThat(aCredit("MERRICK", d("0.015"), "1").cents()).isEqualTo(2);
    }

    @Test
    void aRoutingNumberWithAQuoteInItIsQuotedTheOtherWay() {
        assertThatThrownBy(() -> new Originator("X", "1481003377", "a'b", "X"))
                .hasMessage("\"a'b\" is not a routing number");
        assertThatThrownBy(() -> new Originator("X", "1481003377", "a\"b", "X"))
                .hasMessage("'a\"b' is not a routing number");
        assertThatThrownBy(() -> Ach.checkDigit("1234")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Ach.checkDigit("091000019")).isEqualTo(9);
    }
}
