package io.github.rkaushikdev.bushelworks.reports.statement;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.reports.statement.Statements.WIDTH;
import static io.github.rkaushikdev.bushelworks.reports.statement.Statements.amountRow;
import static io.github.rkaushikdev.bushelworks.reports.statement.Statements.render;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.QuickStatements;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import io.github.rkaushikdev.bushelworks.services.charges.Advance;
import io.github.rkaushikdev.bushelworks.services.charges.StorageCharge;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The statement form, compared character for character.
 *
 * <p>The other statement tests assert that a number appears somewhere on the page. These assert the
 * page. That is the difference between knowing the drying charge was calculated and knowing it
 * printed in the column the producer reads it in.
 *
 * <p>The expected pages are the {@code .txt} files beside this class: a wet load with drying and an
 * advance, a dry load with nothing but the checkoff, and a seven figure settlement where the amount
 * column is at its widest. A failure here is not automatically a bug. It means the form moved, and
 * somebody has to look at the diff and decide whether it was meant to. To regenerate after a
 * deliberate layout change, print the rendered statement for each page and paste it back; do not
 * hand edit them to make a test pass.
 */
class StatementGoldenTest {

    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);
    static final Advance ADVANCE = new Advance("2024-0912", d("1500.00"), LocalDate.of(2024, 9, 12));
    static final StorageCharge STORAGE = new StorageCharge("L-1", d("412.50"), LocalDate.of(2024, 10, 20));

    RuleBook rules;

    @BeforeEach
    void fileTheHouse() {
        rules = HouseFixtures.rules();
    }

    static Map<Factor, BigDecimal> wetSample() {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.MOISTURE, d("18.2"));
        sample.put(Factor.TEST_WEIGHT, d("56.5"));
        sample.put(Factor.BCFM, d("1.2"));
        sample.put(Factor.DAMAGE_TOTAL, d("1.0"));
        return sample;
    }

    static Map<Factor, BigDecimal> drySample() {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.MOISTURE, d("13.5"));
        sample.put(Factor.TEST_WEIGHT, d("58.0"));
        sample.put(Factor.BCFM, d("0.8"));
        sample.put(Factor.DAMAGE_TOTAL, d("0.5"));
        return sample;
    }

    static ScaleTicket.Builder aTicket() {
        return ScaleTicket.builder()
                .ticketNumber("A-10441")
                .scaleDate(LocalDate.of(2024, 10, 3))
                .locationCode("HOME")
                .commodityCode("CORN")
                .grossPounds(d("78420"))
                .tarePounds(d("26180"))
                .disposition(Disposition.CASH_SALE)
                .splits(List.of(new SplitShare("MERRICK", d("100"))))
                .sample(wetSample())
                .truckId("T-14")
                .driver("R. Merrick")
                .farm("Home place");
    }

    String wetPage() {
        return render(QuickStatements.statement(rules, aTicket().build())
                .settledOn(SETTLED)
                .advances(ADVANCE)
                .contractNumber("F-2024-118")
                .build());
    }

    String dryPage() {
        return render(
                QuickStatements.statement(rules, aTicket().sample(drySample()).build())
                        .settledOn(SETTLED)
                        .build());
    }

    String bigPage() {
        return render(QuickStatements.statement(
                        rules, aTicket().grossPounds(d("80000")).build())
                .unitPrice(d("1450.0000"))
                .settledOn(SETTLED)
                .storage(STORAGE)
                .build());
    }

    Map<String, Function<StatementGoldenTest, String>> pages() {
        return Map.of(
                "wet", StatementGoldenTest::wetPage,
                "dry", StatementGoldenTest::dryPage,
                "big", StatementGoldenTest::bigPage);
    }

    String page(String name) {
        return pages().get(name).apply(this);
    }

    static String expected(String name) {
        try (InputStream in = StatementGoldenTest.class.getResourceAsStream(name + ".txt")) {
            Objects.requireNonNull(in, () -> "no golden page " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    static String withoutFinalNewlines(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        return text.substring(0, end);
    }

    static List<String> lines(String text) {
        return Arrays.asList(text.split("\n", -1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"big", "dry", "wet"})
    void thePageIsExactlyWhatItWas(String name) {
        assertThat(page(name)).isEqualTo(withoutFinalNewlines(expected(name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"big", "dry", "wet"})
    void noLineRunsPastTheForm(String name) {
        assertThat(lines(page(name)))
                .allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(WIDTH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"big", "dry", "wet"})
    void thePageHasNoTrailingWhitespace(String name) {
        assertThat(lines(page(name)))
                .filteredOn(line -> !line.equals(line.stripTrailing()))
                .isEmpty();
    }

    @Test
    void theWetPageCarriesTheDryingCharge() {
        assertThat(wetPage()).contains("drying 3.2 points");
    }

    @Test
    void theDryPageHasNoDryingSection() {
        assertThat(dryPage()).doesNotContain("DRYING");
    }

    @Test
    void theDryPageStillWithholdsTheCheckoff() {
        assertThat(dryPage()).contains("Kansas Corn Commission");
    }

    @Test
    void theBigPageKeepsTheAmountColumnOnTheForm() {
        String line = lines(bigPage()).stream()
                .filter(row -> row.contains("NET PAYABLE"))
                .findFirst()
                .orElseThrow();
        assertThat(line).hasSize(WIDTH);
        assertThat(line).endsWith("1,340,405.71");
    }

    /**
     * The bug the golden pages caught.
     *
     * <p>{@code ADV-2024-0912} is thirteen characters and the code column is twelve, so it filled the
     * column exactly and the description started with no space in front of it. The line read {@code
     * ADV-2024-091advance of 2024-09-12}, which looks like one word and hides that the code was cut
     * short.
     */
    @Test
    void aThirteenCharacterCodeStillLeavesAGap() {
        String row = amountRow("ADV-2024-0912", "advance of 2024-09-12", d("-1500.00"));
        assertThat(row).contains("ADV-2024-09 advance");
    }

    @Test
    void aShortCodeIsPaddedTheSameWay() {
        assertThat(amountRow("DRY", "drying 3.2 points", d("-102.33"))).startsWith("  DRY         drying");
    }

    @Test
    void aCodeIsNeverLongerThanItsColumn() {
        String row = amountRow("A".repeat(40), "something", d("-1.00"));
        assertThat(row.substring(2, 14).strip()).isEqualTo("A".repeat(11));
    }

    @Test
    void theRowIsTheFormWidthWhateverTheCode() {
        for (String code : List.of("D", "ADV-2024-0912", "Z".repeat(40))) {
            assertThat(amountRow(code, "a description", d("-1.00"))).as(code).hasSize(WIDTH);
        }
    }

    /**
     * A guard on the fixtures themselves.
     *
     * <p>Three pages that quietly became one page would still pass every comparison above and prove
     * nothing at all.
     */
    @Test
    void thePagesAreNotAllTheSame() {
        assertThat(new HashSet<>(List.of(expected("wet"), expected("dry"), expected("big"))))
                .hasSize(3);
    }
}
