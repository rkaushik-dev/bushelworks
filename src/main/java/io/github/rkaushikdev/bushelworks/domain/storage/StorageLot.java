package io.github.rkaushikdev.bushelworks.domain.storage;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bushels of one producer's grain, received on one day.
 *
 * @param lotId the lot's identifier
 * @param partyId the producer
 * @param commodityCode the commodity
 * @param locationCode where it is stored
 * @param bushels how many came in
 * @param storedOn the day they came in
 * @param withdrawn how many have gone out since
 */
public record StorageLot(
        String lotId,
        String partyId,
        String commodityCode,
        String locationCode,
        BigDecimal bushels,
        LocalDate storedOn,
        BigDecimal withdrawn) {

    public StorageLot {
        Objects.requireNonNull(lotId, "lotId");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(storedOn, "storedOn");
        if (bushels.signum() <= 0) {
            throw new StorageError(lotId + ": a storage lot needs bushels");
        }
        if (withdrawn.signum() < 0) {
            throw new StorageError(lotId + ": withdrawals cannot be negative");
        }
        if (withdrawn.compareTo(bushels) > 0) {
            throw new StorageError(lotId + ": withdrawn " + withdrawn + " exceeds the lot");
        }
    }

    /** A lot nothing has been withdrawn from. */
    public StorageLot(
            String lotId,
            String partyId,
            String commodityCode,
            String locationCode,
            BigDecimal bushels,
            LocalDate storedOn) {
        this(lotId, partyId, commodityCode, locationCode, bushels, storedOn, BigDecimal.ZERO);
    }

    /** What is still in the lot. */
    public BigDecimal openBushels() {
        return Units.quantize(bushels.subtract(withdrawn), Units.BUSHEL_PLACES);
    }

    public boolean isEmpty() {
        return openBushels().signum() == 0;
    }

    /** The lot after some of it goes out. */
    public StorageLot withdraw(BigDecimal amount) {
        BigDecimal wanted = Units.quantize(amount, Units.BUSHEL_PLACES);
        if (wanted.signum() <= 0) {
            throw new StorageError(lotId + ": cannot withdraw " + wanted + " bushels");
        }
        if (wanted.compareTo(openBushels()) > 0) {
            throw new StorageError(lotId + ": only " + openBushels() + " bushels are open");
        }
        return new StorageLot(
                lotId,
                partyId,
                commodityCode,
                locationCode,
                bushels,
                storedOn,
                Units.quantize(withdrawn.add(wanted), Units.BUSHEL_PLACES));
    }

    /** Storage owed on what is still open, as of {@code through}. */
    public BigDecimal accrued(StorageRate rate, LocalDate through) {
        return rate.charge(openBushels(), storedOn, through);
    }

    /**
     * Bushels taken from one lot by a draw down.
     *
     * @param lotId the lot
     * @param bushels how many came out of it
     */
    public record Taken(String lotId, BigDecimal bushels) {}

    /**
     * What a draw down left: every lot in the order given, and what came out of which.
     *
     * @param lots the lots after the draw, in the order they were handed in
     * @param taken what came out, lot by lot, in the order they were drawn
     */
    public record DrawDown(List<StorageLot> lots, List<Taken> taken) {

        public DrawDown {
            lots = List.copyOf(lots);
            taken = List.copyOf(taken);
        }
    }

    /** Take bushels out of a producer's lots, oldest first. */
    public static DrawDown drawDown(List<StorageLot> lots, BigDecimal bushels) {
        return drawDown(lots, bushels, true);
    }

    /**
     * Take bushels out of a producer's lots.
     *
     * <p>First in first out is what the warehouse licence assumes and what keeps the storage bill
     * honest. A house that draws newest first is charging storage on bushels that left, and the
     * state auditor calls that what it is.
     */
    public static DrawDown drawDown(List<StorageLot> lots, BigDecimal bushels, boolean oldestFirst) {
        BigDecimal wanted = Units.quantize(bushels, Units.BUSHEL_PLACES);
        if (wanted.signum() <= 0) {
            throw new StorageError("nothing to draw down");
        }
        BigDecimal available = BigDecimal.ZERO;
        for (StorageLot lot : lots) {
            available = available.add(lot.openBushels());
        }
        if (wanted.compareTo(available) > 0) {
            throw new StorageError("only " + available + " bushels on hand, asked for " + wanted);
        }
        Comparator<StorageLot> byAge = Comparator.comparing(StorageLot::storedOn);
        List<StorageLot> ordered =
                lots.stream().sorted(oldestFirst ? byAge : byAge.reversed()).toList();
        List<Taken> taken = new ArrayList<>();
        Map<String, StorageLot> updated = new LinkedHashMap<>();
        lots.forEach(lot -> updated.put(lot.lotId(), lot));
        for (StorageLot lot : ordered) {
            if (wanted.signum() <= 0) {
                break;
            }
            StorageLot current = updated.get(lot.lotId());
            BigDecimal take = Decimals.min(current.openBushels(), wanted);
            if (take.signum() <= 0) {
                continue;
            }
            updated.put(lot.lotId(), current.withdraw(take));
            taken.add(new Taken(lot.lotId(), take));
            wanted = Units.quantize(wanted.subtract(take), Units.BUSHEL_PLACES);
        }
        return new DrawDown(lots.stream().map(lot -> updated.get(lot.lotId())).toList(), taken);
    }
}
