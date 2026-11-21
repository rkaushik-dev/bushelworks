package io.github.rkaushikdev.bushelworks.services.tax;

import io.github.rkaushikdev.bushelworks.Csv;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the house has to tell the revenue service about each producer.
 *
 * <p>Grain purchases go on a 1099-MISC as crop insurance and other income, and the number that goes
 * in the box is not the settlement net. Three things move it:
 *
 * <ul>
 *   <li>storage, drying and quality discounts are part of the purchase price and stay in the
 *       reported figure, because they reduce what was paid rather than being a separate service
 *       billed to the producer
 *   <li>checkoff withheld is reported, because the producer paid it
 *   <li>an advance is not income when it is advanced and is not income again when it is recovered,
 *       so it nets out and must not be counted twice
 * </ul>
 *
 * <p>The threshold is applied per payee per year, and a payee under it still gets a row in the
 * working file so the office can see the ones that nearly made it.
 */
public final class Tax {

    /** The smallest reportable total that has to be filed. */
    public static final BigDecimal REPORTING_THRESHOLD = new BigDecimal("600.00");

    /** How close {@link #nearThreshold(UnitOfWork, int)} looks below the threshold. */
    public static final BigDecimal NEAR_THRESHOLD = new BigDecimal("100.00");

    /**
     * The line kinds that are part of what the producer was paid for his grain. Advances are left out
     * on purpose: they are a timing arrangement, not income.
     */
    public static final Set<String> REPORTABLE_REDUCTIONS = Set.of(
            LineKind.DISCOUNT.value(),
            LineKind.DRYING.value(),
            LineKind.STORAGE.value(),
            LineKind.SERVICE.value(),
            LineKind.ASSESSMENT.value());

    /** The working file's columns. */
    public static final List<String> WORKING_FILE_COLUMNS = List.of(
            "party_code",
            "year",
            "settlements",
            "gross",
            "reductions",
            "reportable",
            "advances_recovered",
            "is_reportable");

    private Tax() {}

    /**
     * One producer, one year, from the live statements as written.
     *
     * @throws TaxError for a year before 2000 or after 2100
     */
    public static PayeeYear payeeYear(UnitOfWork uow, String partyCode, int year) {
        if (year < 2000 || year > 2100) {
            throw new TaxError("implausible reporting year " + year);
        }

        List<SettlementRow> rows =
                uow.settlements().inWindow(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), partyCode, null);
        Map<String, BigDecimal> totals = uow.settlements()
                .totalsByKind(rows.stream().map(SettlementRow::getId).toList());
        BigDecimal gross = totals.getOrDefault(LineKind.GROSS.value(), BigDecimal.ZERO);
        BigDecimal reductions = Money.total(totals.entrySet().stream()
                .filter(total -> REPORTABLE_REDUCTIONS.contains(total.getKey()))
                .map(Map.Entry::getValue)
                .toList());
        BigDecimal advances = totals.getOrDefault(LineKind.ADVANCE.value(), BigDecimal.ZERO);
        return new PayeeYear(
                partyCode, year, Money.amount(gross), Money.amount(reductions), Money.amount(advances), rows.size());
    }

    /** Everybody the house settled with in a year, the biggest reportable figure first. */
    public static List<PayeeYear> payees(UnitOfWork uow, int year) {
        List<SettlementRow> settled = uow.settlements().inWindow(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        TreeSet<String> codes = new TreeSet<>();
        settled.forEach(row -> codes.add(row.getPartyCode()));
        return codes.stream()
                .map(code -> payeeYear(uow, code, year))
                .sorted(Comparator.comparing(PayeeYear::reportable, Comparator.reverseOrder())
                        .thenComparing(PayeeYear::partyCode))
                .toList();
    }

    /** The payees that reach the threshold, biggest first. */
    public static List<PayeeYear> reportable(UnitOfWork uow, int year) {
        return payees(uow, year).stream().filter(PayeeYear::isReportable).toList();
    }

    /** The payees that fell short of the threshold by a hundred dollars or less. */
    public static List<PayeeYear> nearThreshold(UnitOfWork uow, int year) {
        return nearThreshold(uow, year, NEAR_THRESHOLD);
    }

    /**
     * The payees that fell short of the threshold by no more than {@code within}.
     *
     * <p>The office looks at these because a late settlement keyed in January often belongs to the
     * previous year and pushes one of them over.
     */
    public static List<PayeeYear> nearThreshold(UnitOfWork uow, int year, BigDecimal within) {
        return payees(uow, year).stream()
                .filter(payee -> !payee.isReportable() && payee.shortfall().compareTo(within) <= 0)
                .toList();
    }

    /** Comma separated text with a row for every payee, whether or not they reach the threshold. */
    public static String workingFile(UnitOfWork uow, int year) {
        StringBuilder out = new StringBuilder(Csv.row(WORKING_FILE_COLUMNS));
        for (PayeeYear payee : payees(uow, year)) {
            out.append(Csv.row(
                    payee.partyCode(),
                    payee.year(),
                    payee.settlements(),
                    payee.gross(),
                    payee.reductions(),
                    payee.reportable(),
                    payee.advancesRecovered(),
                    payee.isReportable() ? "yes" : "no"));
        }
        return out.toString();
    }
}
