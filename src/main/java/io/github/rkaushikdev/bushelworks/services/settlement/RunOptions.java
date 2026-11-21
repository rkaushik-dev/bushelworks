package io.github.rkaushikdev.bushelworks.services.settlement;

import io.github.rkaushikdev.bushelworks.services.audit.Audit;
import io.github.rkaushikdev.bushelworks.services.charges.Advance;
import io.github.rkaushikdev.bushelworks.services.charges.Deduction;
import io.github.rkaushikdev.bushelworks.services.charges.DiscountBasis;
import io.github.rkaushikdev.bushelworks.services.charges.StorageCharge;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a producer settlement takes beyond who, where, when and at what spot price.
 *
 * <pre>{@code
 * SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, settledOn, spot,
 *         RunOptions.defaults().withAdvances(List.of(advance)).withActor("office"));
 * }</pre>
 *
 * @param discountBasis which bushel count quality discounts are extended over
 * @param advances advances recovered on the statement
 * @param storage storage bills charged on it
 * @param deductions anything else taken off
 * @param ticketNumbers only these tickets, when given; every waiting ticket otherwise
 * @param actor who is settling, for the audit trail
 */
public record RunOptions(
        DiscountBasis discountBasis,
        List<Advance> advances,
        List<StorageCharge> storage,
        List<Deduction> deductions,
        Optional<List<String>> ticketNumbers,
        String actor) {

    public RunOptions {
        Objects.requireNonNull(discountBasis, "discountBasis");
        Objects.requireNonNull(actor, "actor");
        advances = List.copyOf(advances);
        storage = List.copyOf(storage);
        deductions = List.copyOf(deductions);
        ticketNumbers = ticketNumbers.map(List::copyOf);
    }

    /** Discounts on the dry bushels, nothing else charged, every waiting ticket, by the system. */
    public static RunOptions defaults() {
        return new RunOptions(DiscountBasis.DRY, List.of(), List.of(), List.of(), Optional.empty(), Audit.SYSTEM);
    }

    public RunOptions withDiscountBasis(DiscountBasis value) {
        return new RunOptions(value, advances, storage, deductions, ticketNumbers, actor);
    }

    public RunOptions withAdvances(List<Advance> value) {
        return new RunOptions(discountBasis, value, storage, deductions, ticketNumbers, actor);
    }

    public RunOptions withStorage(List<StorageCharge> value) {
        return new RunOptions(discountBasis, advances, value, deductions, ticketNumbers, actor);
    }

    public RunOptions withDeductions(List<Deduction> value) {
        return new RunOptions(discountBasis, advances, storage, value, ticketNumbers, actor);
    }

    /** Null means every waiting ticket. */
    public RunOptions withTicketNumbers(List<String> value) {
        return new RunOptions(discountBasis, advances, storage, deductions, Optional.ofNullable(value), actor);
    }

    public RunOptions withActor(String value) {
        return new RunOptions(discountBasis, advances, storage, deductions, ticketNumbers, value);
    }
}
