package io.github.rkaushikdev.bushelworks.services.posting;

import io.github.rkaushikdev.bushelworks.domain.ledger.ChartOfAccounts;
import io.github.rkaushikdev.bushelworks.domain.ledger.Side;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementLine;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which account each kind of settlement line lands in.
 *
 * <p>{@code discount} defaulting to the inventory account is the deliberate one. A discount is money
 * the house did not pay for grain it did buy, so it belongs against the cost of that grain. Booking
 * it to margin makes the year end show a bushel cost the house never paid. A house that does book
 * discounts to margin files a different map:
 *
 * <pre>{@code
 * PostingMap theirs = PostingMap.DEFAULT.withDiscount("4400");
 * }</pre>
 *
 * <p>Checkoffs are money held for somebody else, and each commission is remitted separately and
 * reconciles its own return against the house's books. A house that keeps a payable per
 * commission files the levy codes against their accounts; any levy it does not name posts to the
 * map's assessment account, which is where all of them go by default.
 *
 * @param byKind the account for each kind of line
 * @param payable what the house owes the producer, credited the net
 * @param levyAccounts the account a checkoff with this levy code posts to, in place of the
 *     assessment account
 */
public record PostingMap(Map<LineKind, String> byKind, String payable, Map<String, String> levyAccounts) {

    /** The house's standard map, discounts against the grain. */
    public static final PostingMap DEFAULT = new PostingMap(
            Map.of(
                    LineKind.GROSS, "1210",
                    LineKind.DISCOUNT, "1210",
                    LineKind.DRYING, "4200",
                    LineKind.STORAGE, "4100",
                    LineKind.SERVICE, "4300",
                    LineKind.ASSESSMENT, "2200",
                    LineKind.ADVANCE, "1300",
                    LineKind.DEDUCTION, "1400",
                    LineKind.ADJUSTMENT, "4400"),
            "2100",
            Map.of());

    public PostingMap {
        Objects.requireNonNull(payable, "payable");
        EnumMap<LineKind, String> every = new EnumMap<>(LineKind.class);
        every.putAll(byKind);
        for (LineKind kind : LineKind.values()) {
            if (every.get(kind) == null) {
                throw new PostingError("the posting map names no account for " + kind.value() + " lines");
            }
        }
        byKind = Collections.unmodifiableMap(every);
        levyAccounts = Map.copyOf(new TreeMap<>(levyAccounts));
    }

    /** The account a line of this kind posts to. */
    public String accountFor(LineKind kind) {
        return byKind.get(kind);
    }

    /** The account this line posts to: its levy's own account for a checkoff that has one. */
    public String accountFor(SettlementLine line) {
        if (line.kind() == LineKind.ASSESSMENT) {
            return levyAccounts.getOrDefault(line.code(), accountFor(LineKind.ASSESSMENT));
        }
        return accountFor(line.kind());
    }

    /** Where the gross value of the grain is debited. */
    public String inventory() {
        return accountFor(LineKind.GROSS);
    }

    /** A line that reduces the producer's net is money the house keeps, so it is a credit. */
    public Side sideFor(LineKind kind) {
        return kind.reducing() ? Side.CREDIT : Side.DEBIT;
    }

    /** Every account the map names, the payable and the levy accounts included, once each and in code order. */
    public List<String> accounts() {
        Set<String> codes = new TreeSet<>(byKind.values());
        codes.addAll(levyAccounts.values());
        codes.add(payable);
        return List.copyOf(codes);
    }

    /**
     * Refuse a map naming an account a leg cannot name.
     *
     * @throws io.github.rkaushikdev.bushelworks.domain.ledger.LedgerError when an account is a heading
     * @throws io.github.rkaushikdev.bushelworks.domain.ledger.AccountNotFound when one is not on the
     *     chart
     */
    public void checkAgainst(ChartOfAccounts chart) {
        for (String code : accounts()) {
            chart.postable(code);
        }
    }

    /** The same map with lines of {@code kind} posting to {@code account}. */
    public PostingMap withAccount(LineKind kind, String account) {
        Map<LineKind, String> changed = new EnumMap<>(byKind);
        changed.put(kind, account);
        return new PostingMap(changed, payable, levyAccounts);
    }

    /** The same map with the checkoff under {@code levyCode} posting to its own {@code account}. */
    public PostingMap withLevyAccount(String levyCode, String account) {
        Map<String, String> changed = new TreeMap<>(levyAccounts);
        changed.put(levyCode, account);
        return new PostingMap(byKind, payable, changed);
    }

    public PostingMap withInventory(String value) {
        return withAccount(LineKind.GROSS, value);
    }

    public PostingMap withPayable(String value) {
        return new PostingMap(byKind, value, levyAccounts);
    }

    public PostingMap withDiscount(String value) {
        return withAccount(LineKind.DISCOUNT, value);
    }

    public PostingMap withDrying(String value) {
        return withAccount(LineKind.DRYING, value);
    }

    public PostingMap withStorage(String value) {
        return withAccount(LineKind.STORAGE, value);
    }

    public PostingMap withService(String value) {
        return withAccount(LineKind.SERVICE, value);
    }

    public PostingMap withAssessment(String value) {
        return withAccount(LineKind.ASSESSMENT, value);
    }

    public PostingMap withAdvance(String value) {
        return withAccount(LineKind.ADVANCE, value);
    }

    public PostingMap withDeduction(String value) {
        return withAccount(LineKind.DEDUCTION, value);
    }

    public PostingMap withAdjustment(String value) {
        return withAccount(LineKind.ADJUSTMENT, value);
    }
}
