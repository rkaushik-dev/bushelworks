package io.github.rkaushikdev.bushelworks.domain.application;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractError;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractStatus;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Applying delivered bushels against open contracts.
 *
 * <p>A load arrives and something has to decide which contract it fills. When the driver names a
 * contract number there is nothing to decide. When he does not, and most of them do not, the house
 * applies a {@link SelectionRule}, and the rule it picks changes what the producer is paid rather
 * than merely which row is written.
 *
 * <p>What happens past the last contract is a second, separate decision, and the two are
 * deliberately not folded together: a house can be strict about which contract fills first and
 * generous about what happens to the overage.
 */
public final class Applications {

    private Applications() {}

    /** Open contracts this delivery could legally land on, in the order given. */
    public static List<PurchaseContract> eligible(
            Collection<PurchaseContract> contracts,
            String commodityCode,
            String partyId,
            LocalDate day,
            ApplicationLedger ledger) {
        List<PurchaseContract> out = new ArrayList<>();
        for (PurchaseContract contract : contracts) {
            if (contract.status() != ContractStatus.OPEN
                    || !contract.commodityCode().equals(commodityCode)
                    || !contract.partyId().equals(partyId)
                    || !contract.window().contains(day)
                    || ledger.remaining(contract).signum() <= 0) {
                continue;
            }
            out.add(contract);
        }
        return out;
    }

    /** Put candidate contracts in the order the rule wants them filled, ties by number. */
    public static List<PurchaseContract> orderFor(SelectionRule rule, Collection<PurchaseContract> contracts) {
        Comparator<PurchaseContract> order =
                switch (rule) {
                    case WINDOW ->
                        Comparator.comparing((PurchaseContract c) -> c.window().closes());
                    case OLDEST -> Comparator.comparing(PurchaseContract::writtenOn);
                    case BEST_PRICE -> Comparator.comparing(Applications::priceOrZero, Comparator.reverseOrder());
                };
        return contracts.stream()
                .sorted(order.thenComparing(PurchaseContract::number))
                .toList();
    }

    /**
     * An unpriced contract sorts last under {@link SelectionRule#BEST_PRICE}.
     *
     * <p>It has no price to compare, and filling it early would hand the producer a number nobody
     * has agreed to yet.
     */
    private static BigDecimal priceOrZero(PurchaseContract contract) {
        try {
            return contract.unitPrice();
        } catch (ContractError unpriced) {
            return BigDecimal.ZERO;
        }
    }

    /** Split a delivery across contracts in window order, storing what is left over. */
    public static List<Application> applyDelivery(
            String ticketNumber,
            BigDecimal bushels,
            Collection<PurchaseContract> contracts,
            ApplicationLedger ledger,
            LocalDate day) {
        return applyDelivery(ticketNumber, bushels, contracts, ledger, day, SelectionRule.WINDOW, OverDelivery.STORE);
    }

    /** Split a delivery across contracts and say what happened to the rest. */
    public static List<Application> applyDelivery(
            String ticketNumber,
            BigDecimal bushels,
            Collection<PurchaseContract> contracts,
            ApplicationLedger ledger,
            LocalDate day,
            SelectionRule rule,
            OverDelivery overDelivery) {
        BigDecimal remaining = Units.quantize(bushels, Units.BUSHEL_PLACES);
        if (remaining.signum() <= 0) {
            throw new ApplicationError(ticketNumber + ": nothing to apply");
        }
        List<Application> applications = new ArrayList<>();
        for (PurchaseContract contract : orderFor(rule, contracts)) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal room = ledger.remaining(contract);
            if (room.signum() <= 0) {
                continue;
            }
            BigDecimal take = Decimals.min(room, remaining);
            Application application = Application.onContract(
                    ticketNumber, contract.number(), Units.quantize(take, Units.BUSHEL_PLACES), day);
            ledger.record(application);
            applications.add(application);
            remaining = Units.quantize(remaining.subtract(take), Units.BUSHEL_PLACES);
        }
        if (remaining.signum() > 0) {
            applications.add(overage(ticketNumber, remaining, day, overDelivery));
        }
        return applications;
    }

    private static Application overage(String ticketNumber, BigDecimal bushels, LocalDate day, OverDelivery policy) {
        if (policy == OverDelivery.REFUSE) {
            throw new ApplicationError(
                    ticketNumber + ": " + bushels + " bushels over contract and the house refuses it");
        }
        return new Application(
                ticketNumber, Optional.empty(), bushels, day, policy == OverDelivery.SPOT ? "spot" : "store");
    }
}
