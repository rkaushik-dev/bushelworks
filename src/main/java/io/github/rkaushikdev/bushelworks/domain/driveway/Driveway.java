package io.github.rkaushikdev.bushelworks.domain.driveway;

import io.github.rkaushikdev.bushelworks.ConflictError;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The queue on the driveway at one site.
 *
 * <p>Four trucks deep is a normal Tuesday and forty is the third week of October. The order they
 * are worked is not first come first served, and pretending it is makes the queue longer rather
 * than fairer:
 *
 * <ul>
 *   <li>a truck already on the scale finishes first, always
 *   <li>a probe that is already drawn is worked before one that is not, because the sample is
 *       sitting on the bench going warm
 *   <li>a company truck coming back for another load beats a producer's truck, because it is on a
 *       round trip the house is paying for by the hour
 *   <li>otherwise the oldest arrival wins
 * </ul>
 *
 * <p>The last rule is the one everybody thinks is the whole rule.
 */
public final class Driveway {

    private final String locationCode;
    private final Map<String, Truck> trucks = new LinkedHashMap<>();

    public Driveway(String locationCode) {
        this.locationCode = locationCode;
    }

    public String locationCode() {
        return locationCode;
    }

    public void arrive(Truck truck) {
        if (trucks.containsKey(truck.truckId())) {
            throw new ConflictError(truck.truckId() + " is already on the driveway");
        }
        trucks.put(truck.truckId(), truck);
    }

    public Truck get(String truckId) {
        Truck found = trucks.get(truckId);
        if (found == null) {
            throw new DrivewayError(truckId + " is not on the driveway");
        }
        return found;
    }

    public Truck advance(String truckId, Stage to) {
        return advance(truckId, to, null);
    }

    /** Move a truck on a stage. Only one truck fits on the scale, and a finished truck leaves. */
    public Truck advance(String truckId, Stage to, String probeId) {
        Truck moved = get(truckId).advance(to, probeId);
        if (to == Stage.ON_SCALE) {
            Optional<Truck> occupant = onScale();
            if (occupant.isPresent()) {
                throw new ConflictError(occupant.get().truckId() + " is already on the scale");
            }
        }
        trucks.put(truckId, moved);
        if (moved.stage() == Stage.DONE) {
            trucks.remove(truckId);
        }
        return moved;
    }

    /** Pull a truck out of the queue without finishing it. */
    public Truck leave(String truckId) {
        Truck truck = get(truckId);
        trucks.remove(truckId);
        return truck;
    }

    public Optional<Truck> onScale() {
        return trucks.values().stream()
                .filter(truck -> truck.stage() == Stage.ON_SCALE || truck.stage() == Stage.DUMPING)
                .findFirst();
    }

    /** Everybody waiting, in the order they will be worked. */
    public List<Truck> queue() {
        return trucks.values().stream().sorted(Truck.WORKING_ORDER).toList();
    }

    public Optional<Truck> nextUp() {
        return queue().stream().findFirst();
    }

    public int depth() {
        return trucks.size();
    }

    public Optional<Truck> waitingLongest(LocalDateTime now) {
        return trucks.values().stream()
                .min(Comparator.comparing(Truck::arrivedAt).thenComparing(Truck::truckId));
    }

    /**
     * Trucks that have been on the driveway longer than the house allows, in working order.
     *
     * <p>The manager watches this number. A queue with three trucks over forty minutes is a queue
     * that is about to become forty trucks.
     */
    public List<Truck> overWait(LocalDateTime now, Duration limit) {
        return queue().stream()
                .filter(truck -> truck.waitingFor(now).compareTo(limit) > 0)
                .toList();
    }

    /** Trucks on the driveway per commodity, by commodity code. */
    public Map<String, Integer> byCommodity() {
        Map<String, Integer> counts = new TreeMap<>();
        trucks.values().forEach(truck -> counts.merge(truck.commodityCode(), 1, Integer::sum));
        return counts;
    }
}
