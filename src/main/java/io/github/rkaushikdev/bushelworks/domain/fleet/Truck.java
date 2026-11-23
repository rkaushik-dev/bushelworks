package io.github.rkaushikdev.bushelworks.domain.fleet;

import io.github.rkaushikdev.bushelworks.ConflictError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One truck the house weighs, with every tare taken of it.
 *
 * <p>A stale tare is not refused, because at four in the morning refusing it means the driver
 * waits; it is flagged, and the flag is on the ticket.
 *
 * @param truckId the truck
 * @param partyCode who it belongs to
 * @param description what it is
 * @param isCompany whether it is the house's own
 * @param tares every empty weighing, in the order they were taken
 * @param retiredOn when it stopped coming, if it has
 * @param rig what kind of outfit it is, which decides the tares it can plausibly weigh
 */
public record Truck(
        String truckId,
        String partyCode,
        String description,
        boolean isCompany,
        List<Tare> tares,
        Optional<LocalDate> retiredOn,
        Rig rig) {

    public Truck {
        Objects.requireNonNull(truckId, "truckId");
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(retiredOn, "retiredOn");
        tares = List.copyOf(tares);
        if (truckId.isEmpty()) {
            throw new FleetError("a truck needs an identifier");
        }
        if (partyCode.isEmpty()) {
            throw new FleetError(truckId + ": a truck belongs to somebody");
        }
        for (Tare tare : tares) {
            checkPlausible(truckId, rig, tare);
        }
    }

    /** A producer's hopper bottom with no tare on file. */
    public static Truck of(String truckId, String partyCode, String description) {
        return of(truckId, partyCode, description, Rig.HOPPER_BOTTOM);
    }

    /** A producer's truck of a given rig, with no tare on file. */
    public static Truck of(String truckId, String partyCode, String description, Rig rig) {
        return new Truck(truckId, partyCode, description, false, List.of(), Optional.empty(), rig);
    }

    private static void checkPlausible(String truckId, Rig rig, Tare tare) {
        if (!rig.isPlausibleTare(tare.pounds())) {
            throw new FleetError(truckId + ": a tare of " + tare.pounds() + " lb is not a " + rig.value()
                    + ", which weighs " + rig.lightest() + " to " + rig.heaviest() + " empty");
        }
    }

    public boolean isRetired() {
        return retiredOn.isPresent();
    }

    private static final Comparator<Tare> BY_DAY = Comparator.comparing(Tare::weighedOn);

    public Optional<Tare> latestTare() {
        return tares.stream().max(BY_DAY);
    }

    /** The most recent tare that is still current on a day. */
    public Optional<Tare> tareOn(LocalDate day) {
        return tares.stream().filter(tare -> tare.isCurrentOn(day)).max(BY_DAY);
    }

    /** Whether the scale house should weigh this truck empty again. */
    public boolean tareIsStale(LocalDate day) {
        return tareOn(day).isEmpty();
    }

    public Truck weighEmpty(Tare tare) {
        if (isRetired()) {
            throw new ConflictError(truckId + " was retired on " + retiredOn.get());
        }
        if (tares.stream().anyMatch(item -> item.weighedOn().equals(tare.weighedOn()))) {
            throw new ConflictError(truckId + " was already weighed empty on " + tare.weighedOn());
        }
        checkPlausible(truckId, rig, tare);
        List<Tare> more = new ArrayList<>(tares);
        more.add(tare);
        return new Truck(truckId, partyCode, description, isCompany, more, retiredOn, rig);
    }

    public Truck retire(LocalDate on) {
        if (isRetired()) {
            throw new ConflictError(truckId + " is already retired");
        }
        return new Truck(truckId, partyCode, description, isCompany, tares, Optional.of(on), rig);
    }

    /**
     * How far the tare has moved since the first weighing.
     *
     * <p>Twelve hundred pounds over a season is a tarp and a toolbox. Four thousand is a different
     * truck wearing the same number, which happens.
     */
    public Optional<BigDecimal> drift() {
        if (tares.size() < 2) {
            return Optional.empty();
        }
        List<Tare> ordered = tares.stream().sorted(BY_DAY).toList();
        return Optional.of(
                ordered.get(ordered.size() - 1).pounds().subtract(ordered.get(0).pounds()));
    }

    /** Whether the tare has moved as far as this rig's threshold, either way. */
    public boolean isDrifting() {
        return isDrifting(rig.driftThreshold());
    }

    public boolean isDrifting(BigDecimal threshold) {
        return drift().filter(moved -> moved.abs().compareTo(threshold) >= 0).isPresent();
    }
}
