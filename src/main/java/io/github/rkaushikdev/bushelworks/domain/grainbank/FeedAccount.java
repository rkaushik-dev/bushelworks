package io.github.rkaushikdev.bushelworks.domain.grainbank;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One cattleman's grain bank account at one site: a feed account, not a storage account.
 *
 * <p>A cattleman hauls corn in and takes ground feed out. He is not selling and the house is not
 * buying: it is holding his bushels and giving them back processed, minus a processing charge and a
 * shrink for what the mill loses. Storage bushels can be priced and turned into a cheque; grain bank
 * bushels can only be taken back as feed, and a producer who wants money has to convert the account
 * to storage first, which is a decision with a date on it.
 *
 * @param partyCode the cattleman
 * @param commodityCode the grain banked
 * @param locationCode the site
 * @param openedOn when the account was opened
 * @param movements every deposit and withdrawal, in order
 * @param convertedOn when the account was converted to storage, if it has been
 */
public record FeedAccount(
        String partyCode,
        String commodityCode,
        String locationCode,
        LocalDate openedOn,
        List<Movement> movements,
        Optional<LocalDate> convertedOn) {

    private static final BigDecimal TON_PLACES = new BigDecimal("0.001");

    public FeedAccount {
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(openedOn, "openedOn");
        Objects.requireNonNull(convertedOn, "convertedOn");
        movements = List.copyOf(movements);
    }

    /** An account with nothing in it. */
    public static FeedAccount open(String partyCode, String commodityCode, String locationCode, LocalDate on) {
        return new FeedAccount(partyCode, commodityCode, locationCode, on, List.of(), Optional.empty());
    }

    public BigDecimal balance() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Movement item : movements) {
            sum = sum.add(item.bushels());
        }
        return Units.quantize(sum, Units.BUSHEL_PLACES);
    }

    public boolean isConverted() {
        return convertedOn.isPresent();
    }

    public BigDecimal charges() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Movement item : movements) {
            sum = sum.add(item.charge());
        }
        return Money.amount(sum);
    }

    private void refuseIfConverted() {
        if (isConverted()) {
            throw new ConflictError(partyCode + ": the account was converted on " + convertedOn.get());
        }
    }

    private FeedAccount with(Movement movement) {
        List<Movement> more = new ArrayList<>(movements);
        more.add(movement);
        return new FeedAccount(partyCode, commodityCode, locationCode, openedOn, more, convertedOn);
    }

    public FeedAccount deposit(String reference, BigDecimal bushels, LocalDate on) {
        refuseIfConverted();
        BigDecimal wanted = Units.quantize(bushels, Units.BUSHEL_PLACES);
        if (wanted.signum() <= 0) {
            throw new GrainBankError(reference + ": a deposit has to be positive");
        }
        return with(Movement.deposit(reference, on, wanted));
    }

    /** Make feed out of the account: the ration's bushels come out and its processing is charged. */
    public FeedAccount withdraw(String reference, Ration ration, BigDecimal tons, LocalDate on) {
        refuseIfConverted();
        BigDecimal needed = ration.bushelsFor(tons);
        if (needed.compareTo(balance()) > 0) {
            throw new GrainBankError(reference + ": " + needed + " bushels needed and only " + balance() + " on hand");
        }
        return with(new Movement(
                reference,
                on,
                needed.negate(),
                Optional.of(tons),
                Optional.of(ration.code()),
                ration.processingCharge(tons)));
    }

    /**
     * Close the feed account so the balance can be priced.
     *
     * <p>A decision with a date, because it changes what the bushels are: before it they are feed and
     * after it they are grain the producer can sell.
     */
    public FeedAccount convertToStorage(LocalDate on) {
        if (isConverted()) {
            throw new ConflictError(partyCode + ": already converted");
        }
        if (balance().signum() <= 0) {
            throw new GrainBankError(partyCode + ": nothing in the account to convert");
        }
        return new FeedAccount(partyCode, commodityCode, locationCode, openedOn, movements, Optional.of(on));
    }

    public BigDecimal tonsTaken() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Movement item : movements) {
            if (item.tons().isPresent()) {
                sum = sum.add(item.tons().get());
            }
        }
        return Units.quantize(sum, TON_PLACES);
    }

    public List<Movement> activity(LocalDate since) {
        return movements.stream().filter(item -> !item.on().isBefore(since)).toList();
    }
}
