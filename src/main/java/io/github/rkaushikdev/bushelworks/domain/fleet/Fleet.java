package io.github.rkaushikdev.bushelworks.domain.fleet;

import io.github.rkaushikdev.bushelworks.ConflictError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every truck the house knows about. */
public final class Fleet {

    private static final BigDecimal DRIFT_THRESHOLD = new BigDecimal("2000");

    private final Map<String, Truck> trucks = new LinkedHashMap<>();

    public void add(Truck truck) {
        if (trucks.containsKey(truck.truckId())) {
            throw new ConflictError(truck.truckId() + " is already on the fleet");
        }
        trucks.put(truck.truckId(), truck);
    }

    public Truck get(String truckId) {
        Truck found = trucks.get(truckId);
        if (found == null) {
            throw new FleetError(truckId + " is not on the fleet");
        }
        return found;
    }

    /** Put back a truck whose record changed. It has to be on the fleet already. */
    public void replace(Truck truck) {
        get(truck.truckId());
        trucks.put(truck.truckId(), truck);
    }

    /** The trucks still coming, by truck id. */
    public List<Truck> active() {
        return trucks.values().stream()
                .filter(truck -> !truck.isRetired())
                .sorted(Comparator.comparing(Truck::truckId))
                .toList();
    }

    public List<Truck> forParty(String partyCode) {
        return active().stream()
                .filter(truck -> truck.partyCode().equals(partyCode))
                .toList();
    }

    /** The list the scale house works down first thing in the morning. */
    public List<Truck> needingATare(LocalDate day) {
        return active().stream().filter(truck -> truck.tareIsStale(day)).toList();
    }

    /** Trucks whose tare has moved two thousand pounds or more. */
    public List<Truck> drifting() {
        return drifting(DRIFT_THRESHOLD);
    }

    public List<Truck> drifting(BigDecimal threshold) {
        return active().stream()
                .filter(truck -> truck.drift()
                        .filter(drift -> drift.abs().compareTo(threshold) >= 0)
                        .isPresent())
                .toList();
    }
}
