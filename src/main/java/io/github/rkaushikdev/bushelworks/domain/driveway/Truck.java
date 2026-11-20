package io.github.rkaushikdev.bushelworks.domain.driveway;

import io.github.rkaushikdev.bushelworks.ConflictError;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * One truck in the queue.
 *
 * @param truckId the truck
 * @param partyCode whose grain it carries
 * @param commodityCode what it carries
 * @param arrivedAt when it pulled onto the driveway
 * @param stage where it is in the process
 * @param isCompany whether it is the house's own truck on a round trip
 * @param probeId the sample drawn from it, once probed
 */
public record Truck(
        String truckId,
        String partyCode,
        String commodityCode,
        LocalDateTime arrivedAt,
        Stage stage,
        boolean isCompany,
        Optional<String> probeId) {

    /** The order the queue is worked in: priority, then arrival, then truck id. */
    public static final Comparator<Truck> WORKING_ORDER = Comparator.comparing(Truck::priority)
            .thenComparing(Truck::arrivedAt)
            .thenComparing(Truck::truckId);

    public Truck {
        Objects.requireNonNull(truckId, "truckId");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(arrivedAt, "arrivedAt");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(probeId, "probeId");
        if (truckId.isEmpty()) {
            throw new DrivewayError("a truck needs an id");
        }
        if (stage == Stage.PROBED && probeId.filter(id -> !id.isEmpty()).isEmpty()) {
            throw new DrivewayError(truckId + ": probed but no sample id");
        }
    }

    /** A producer's truck just arrived. */
    public static Truck arrived(String truckId, String partyCode, String commodityCode, LocalDateTime at) {
        return new Truck(truckId, partyCode, commodityCode, at, Stage.ARRIVED, false, Optional.empty());
    }

    public Truck asCompany() {
        return new Truck(truckId, partyCode, commodityCode, arrivedAt, stage, true, probeId);
    }

    /** The same truck said to be at another stage, without the move being checked. */
    public Truck atStage(Stage value) {
        return new Truck(truckId, partyCode, commodityCode, arrivedAt, value, isCompany, probeId);
    }

    public Priority priority() {
        if (stage == Stage.ON_SCALE || stage == Stage.DUMPING) {
            return Priority.ON_SCALE;
        }
        if (stage == Stage.PROBED) {
            return Priority.PROBED;
        }
        return isCompany ? Priority.COMPANY : Priority.WAITING;
    }

    public Duration waitingFor(LocalDateTime now) {
        if (now.isBefore(arrivedAt)) {
            throw new DrivewayError(truckId + ": " + now + " is before it arrived");
        }
        return Duration.between(arrivedAt, now);
    }

    /** Move on a stage with nothing new to record. */
    public Truck advance(Stage to) {
        return advance(to, null);
    }

    /** Move on a stage; probing records the sample drawn, which every later stage keeps. */
    public Truck advance(Stage to, String sampleId) {
        if (!stage.next().contains(to)) {
            throw new ConflictError(truckId + " is " + stage.value() + " and cannot go to " + to.value());
        }
        boolean named = sampleId != null && !sampleId.isEmpty();
        if (to == Stage.PROBED && !named) {
            throw new DrivewayError(truckId + ": a probe needs a sample id");
        }
        return new Truck(
                truckId, partyCode, commodityCode, arrivedAt, to, isCompany, named ? Optional.of(sampleId) : probeId);
    }
}
