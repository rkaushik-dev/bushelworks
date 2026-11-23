package io.github.rkaushikdev.bushelworks.domain.advances;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Money paid to a producer before his grain is settled, with what has been recovered against it.
 *
 * <p>Both kinds are recovered out of settlements. What differs is the interest and the limit: a
 * settlement advance is capped at a percentage of what the grain is worth, and a crop advance is
 * capped at whatever the manager agreed.
 *
 * <p>Interest is simple, not compound, on actual days over three hundred and sixty five. That is
 * what the note says and the note is what a court reads. It runs on the balance actually out each
 * day: a recovery takes the balance down from the day it was made, not from the day the advance
 * was drawn, and a change of rate applies from its own day forward.
 *
 * @param reference the advance's reference
 * @param partyCode the producer
 * @param kind settlement or crop
 * @param principal what was paid out
 * @param drawnOn when
 * @param annualRatePercent simple interest a year, zero for a settlement advance
 * @param dueOn when it falls due, if it does
 * @param repayments what has been recovered so far, in order
 * @param rateChanges the rate changes on the note, in the order of their days
 */
public record Advance(
        String reference,
        String partyCode,
        AdvanceKind kind,
        BigDecimal principal,
        LocalDate drawnOn,
        BigDecimal annualRatePercent,
        Optional<LocalDate> dueOn,
        List<Repayment> repayments,
        List<RateChange> rateChanges) {

    /** Interest is on actual days over this many. */
    public static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");

    public Advance {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(drawnOn, "drawnOn");
        Objects.requireNonNull(dueOn, "dueOn");
        repayments = List.copyOf(repayments);
        rateChanges = rateChanges.stream()
                .sorted(Comparator.comparing(RateChange::effectiveOn))
                .toList();
        if (reference.isEmpty()) {
            throw new AdvanceError("an advance needs a reference");
        }
        if (principal.signum() <= 0) {
            throw new AdvanceError(reference + ": an advance has to be positive");
        }
        if (annualRatePercent.signum() < 0) {
            throw new AdvanceError(reference + ": a rate cannot be negative");
        }
        if (kind == AdvanceKind.SETTLEMENT && (annualRatePercent.signum() != 0 || !rateChanges.isEmpty())) {
            throw new AdvanceError(reference + ": a settlement advance carries no interest");
        }
        if (dueOn.isPresent() && dueOn.get().isBefore(drawnOn)) {
            throw new AdvanceError(reference + ": due before it was drawn");
        }
        if (rateChanges.stream().anyMatch(change -> !change.effectiveOn().isAfter(drawnOn))) {
            throw new AdvanceError(reference + ": a rate change has to come after the advance was drawn");
        }
        if (rateChanges.stream().map(RateChange::effectiveOn).distinct().count() != rateChanges.size()) {
            throw new AdvanceError(reference + ": two rate changes on the same day");
        }
    }

    /** An advance with no interest, no due date and nothing repaid. */
    public static Advance of(String reference, String partyCode, AdvanceKind kind, BigDecimal principal, LocalDate on) {
        return new Advance(
                reference, partyCode, kind, principal, on, BigDecimal.ZERO, Optional.empty(), List.of(), List.of());
    }

    public Advance withAnnualRatePercent(BigDecimal value) {
        return new Advance(reference, partyCode, kind, principal, drawnOn, value, dueOn, repayments, rateChanges);
    }

    public Advance withDueOn(LocalDate value) {
        return new Advance(
                reference,
                partyCode,
                kind,
                principal,
                drawnOn,
                annualRatePercent,
                Optional.of(value),
                repayments,
                rateChanges);
    }

    public Advance withPrincipal(BigDecimal value) {
        return new Advance(
                reference, partyCode, kind, value, drawnOn, annualRatePercent, dueOn, repayments, rateChanges);
    }

    public Advance withDrawnOn(LocalDate value) {
        return new Advance(
                reference, partyCode, kind, principal, value, annualRatePercent, dueOn, repayments, rateChanges);
    }

    public Advance withReference(String value) {
        return new Advance(
                value, partyCode, kind, principal, drawnOn, annualRatePercent, dueOn, repayments, rateChanges);
    }

    /** The advance with the rate changing to {@code annualRatePercent} from {@code effectiveOn}. */
    public Advance withRateChange(LocalDate effectiveOn, BigDecimal annualRatePercent) {
        List<RateChange> more = new ArrayList<>(rateChanges);
        more.add(new RateChange(effectiveOn, annualRatePercent));
        return new Advance(
                reference, partyCode, kind, principal, drawnOn, this.annualRatePercent, dueOn, repayments, more);
    }

    /** The rate charged on a day: the written rate, or the last change on or before it. */
    public BigDecimal rateOn(LocalDate day) {
        BigDecimal rate = annualRatePercent;
        for (RateChange change : rateChanges) {
            if (!change.effectiveOn().isAfter(day)) {
                rate = change.annualRatePercent();
            }
        }
        return rate;
    }

    public BigDecimal repaid() {
        return Money.total(repayments.stream().map(Repayment::dollars).toList());
    }

    /** What is still owed on the principal, never below zero. */
    public BigDecimal outstanding() {
        BigDecimal left = Money.amount(principal.subtract(repaid()));
        return left.signum() > 0 ? left : Money.amount("0.00");
    }

    public boolean isSettled() {
        return outstanding().signum() == 0;
    }

    public int daysOut(LocalDate on) {
        if (on.isBefore(drawnOn)) {
            throw new AdvanceError(reference + ": " + on + " is before it was drawn");
        }
        return (int) ChronoUnit.DAYS.between(drawnOn, on);
    }

    /**
     * Simple interest from the day the advance was drawn to a date, on the balance out each day at
     * the rate in force that day, rounded to the cent once.
     */
    public BigDecimal interestTo(LocalDate on) {
        if (daysOut(on) <= 0) {
            return Money.amount("0.00");
        }
        TreeSet<LocalDate> turns = new TreeSet<>();
        repayments.forEach(repayment -> turns.add(repayment.repaidOn()));
        rateChanges.forEach(change -> turns.add(change.effectiveOn()));
        BigDecimal accrued = BigDecimal.ZERO;
        LocalDate from = drawnOn;
        for (LocalDate turn : turns.subSet(drawnOn, false, on, false)) {
            accrued = accrued.add(accrual(from, turn));
            from = turn;
        }
        accrued = accrued.add(accrual(from, on));
        return Units.quantize(accrued, Money.AMOUNT_PLACES);
    }

    /** Interest over {@code from} to {@code to} on the balance and at the rate of {@code from}. */
    private BigDecimal accrual(LocalDate from, LocalDate to) {
        BigDecimal days = BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to));
        BigDecimal yearly = Decimals.divide(balanceOn(from).multiply(rateOn(from)), Decimals.HUNDRED);
        return Decimals.divide(yearly.multiply(days), DAYS_IN_YEAR);
    }

    /** The principal out at the end of a day, every recovery made on or before it taken off. */
    public BigDecimal balanceOn(LocalDate day) {
        BigDecimal recovered = Money.total(repayments.stream()
                .filter(repayment -> !repayment.repaidOn().isAfter(day))
                .map(Repayment::dollars)
                .toList());
        BigDecimal left = Money.amount(principal.subtract(recovered));
        return left.signum() > 0 ? left : Money.amount("0.00");
    }

    /** What clears the advance on a date: what is outstanding and the interest on it. */
    public BigDecimal payoff(LocalDate on) {
        return Money.amount(outstanding().add(interestTo(on)));
    }

    public boolean isOverdue(LocalDate on) {
        if (dueOn.isEmpty() || isSettled()) {
            return false;
        }
        return on.isAfter(dueOn.get());
    }

    /** The advance after a recovery out of a settlement. */
    public Advance repay(String settlementNumber, BigDecimal dollars, LocalDate on) {
        BigDecimal wanted = Money.amount(dollars);
        if (wanted.signum() <= 0) {
            throw new AdvanceError(reference + ": cannot repay " + wanted);
        }
        if (isSettled()) {
            throw new ConflictError(reference + " is already repaid");
        }
        if (wanted.compareTo(outstanding()) > 0) {
            throw new AdvanceError(reference + ": " + wanted + " is more than the " + outstanding() + " outstanding");
        }
        List<Repayment> more = new ArrayList<>(repayments);
        more.add(new Repayment(settlementNumber, wanted, on));
        return new Advance(reference, partyCode, kind, principal, drawnOn, annualRatePercent, dueOn, more, rateChanges);
    }

    /**
     * How much principal a settlement takes off this advance.
     *
     * <p>Principal only. Interest is a charge on the statement in its own right and recovering it
     * through here would make the repayment larger than the principal, which then reads as an
     * overpayment on the note. A settlement never goes negative to service an advance; whatever it
     * cannot cover stays outstanding and comes off the next one.
     */
    public BigDecimal recoverableFrom(BigDecimal net, LocalDate on) {
        BigDecimal available = Money.amount(net);
        if (available.signum() <= 0) {
            return Money.amount("0.00");
        }
        return Money.amount(Decimals.min(outstanding(), available));
    }
}
