package io.github.rkaushikdev.bushelworks.services.posting;

import io.github.rkaushikdev.bushelworks.domain.ledger.ChartOfAccounts;
import io.github.rkaushikdev.bushelworks.domain.ledger.Side;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 * @param inventory where the gross value of the grain is debited
 * @param payable what the house owes the producer, credited the net
 * @param discount quality discounts
 * @param drying drying charges
 * @param storage storage charges
 * @param service other service charges
 * @param assessment checkoffs collected for a commission
 * @param advance advances recovered
 * @param deduction anything else taken off
 * @param adjustment corrections, either way
 */
public record PostingMap(
        String inventory,
        String payable,
        String discount,
        String drying,
        String storage,
        String service,
        String assessment,
        String advance,
        String deduction,
        String adjustment) {

    /** The house's standard map, discounts against the grain. */
    public static final PostingMap DEFAULT =
            new PostingMap("1210", "2100", "1210", "4200", "4100", "4300", "2200", "1300", "1400", "4400");

    public PostingMap {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(payable, "payable");
        Objects.requireNonNull(discount, "discount");
        Objects.requireNonNull(drying, "drying");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(advance, "advance");
        Objects.requireNonNull(deduction, "deduction");
        Objects.requireNonNull(adjustment, "adjustment");
    }

    /** The account a line of this kind posts to. */
    public String accountFor(LineKind kind) {
        return switch (kind) {
            case GROSS -> inventory;
            case DISCOUNT -> discount;
            case DRYING -> drying;
            case STORAGE -> storage;
            case SERVICE -> service;
            case ASSESSMENT -> assessment;
            case ADVANCE -> advance;
            case DEDUCTION -> deduction;
            case ADJUSTMENT -> adjustment;
        };
    }

    /** A line that reduces the producer's net is money the house keeps, so it is a credit. */
    public Side sideFor(LineKind kind) {
        return kind.reducing() ? Side.CREDIT : Side.DEBIT;
    }

    /** Every account the map names, the payable included, once each and in code order. */
    public List<String> accounts() {
        Set<String> codes = new TreeSet<>();
        for (LineKind kind : LineKind.values()) {
            codes.add(accountFor(kind));
        }
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

    public PostingMap withInventory(String value) {
        return new PostingMap(
                value, payable, discount, drying, storage, service, assessment, advance, deduction, adjustment);
    }

    public PostingMap withPayable(String value) {
        return new PostingMap(
                inventory, value, discount, drying, storage, service, assessment, advance, deduction, adjustment);
    }

    public PostingMap withDiscount(String value) {
        return new PostingMap(
                inventory, payable, value, drying, storage, service, assessment, advance, deduction, adjustment);
    }

    public PostingMap withDrying(String value) {
        return new PostingMap(
                inventory, payable, discount, value, storage, service, assessment, advance, deduction, adjustment);
    }

    public PostingMap withStorage(String value) {
        return new PostingMap(
                inventory, payable, discount, drying, value, service, assessment, advance, deduction, adjustment);
    }

    public PostingMap withService(String value) {
        return new PostingMap(
                inventory, payable, discount, drying, storage, value, assessment, advance, deduction, adjustment);
    }

    public PostingMap withAssessment(String value) {
        return new PostingMap(
                inventory, payable, discount, drying, storage, service, value, advance, deduction, adjustment);
    }

    public PostingMap withAdvance(String value) {
        return new PostingMap(
                inventory, payable, discount, drying, storage, service, assessment, value, deduction, adjustment);
    }

    public PostingMap withDeduction(String value) {
        return new PostingMap(
                inventory, payable, discount, drying, storage, service, assessment, advance, value, adjustment);
    }

    public PostingMap withAdjustment(String value) {
        return new PostingMap(
                inventory, payable, discount, drying, storage, service, assessment, advance, deduction, value);
    }
}
