package io.github.rkaushikdev.bushelworks.domain.driveway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DrivewayTest {

    static final LocalDateTime EIGHT = LocalDateTime.of(2024, 10, 3, 8, 0);

    static Truck aTruck(String truckId, int minutes) {
        return Truck.arrived(truckId, "MERRICK", "CORN", EIGHT.plusMinutes(minutes));
    }

    static Truck aTruck() {
        return aTruck("T-01", 0);
    }

    static Driveway aDriveway() {
        Driveway yard = new Driveway("HOME");
        yard.arrive(aTruck("T-01", 0));
        yard.arrive(aTruck("T-02", 5));
        yard.arrive(aTruck("T-03", 10).asCompany());
        return yard;
    }

    @Test
    void aTruckNeedsAnId() {
        assertThatThrownBy(() -> aTruck("", 0)).isInstanceOf(DrivewayError.class);
    }

    @Test
    void aProbedTruckNeedsASampleId() {
        assertThatThrownBy(() -> aTruck().atStage(Stage.PROBED)).isInstanceOf(DrivewayError.class);
    }

    @Test
    void aNewTruckIsWaiting() {
        assertThat(aTruck().priority()).isSameAs(Priority.WAITING);
    }

    @Test
    void aCompanyTruckOutranksAWaitingOne() {
        assertThat(aTruck().asCompany().priority()).isSameAs(Priority.COMPANY);
        assertThat(Priority.COMPANY).isLessThan(Priority.WAITING);
    }

    @Test
    void aProbedTruckOutranksACompanyOne() {
        Truck probed = aTruck().advance(Stage.PROBED, "P-1");
        assertThat(probed.priority()).isSameAs(Priority.PROBED);
        assertThat(Priority.PROBED).isLessThan(Priority.COMPANY);
    }

    @Test
    void aTruckOnTheScaleOutranksEverything() {
        Truck onScale = aTruck().advance(Stage.PROBED, "P-1").advance(Stage.ON_SCALE);
        assertThat(onScale.priority()).isSameAs(Priority.ON_SCALE);
    }

    @Test
    void aTruckCannotSkipAStage() {
        assertThatThrownBy(() -> aTruck().advance(Stage.ON_SCALE)).isInstanceOf(ConflictError.class);
    }

    @Test
    void aTruckCannotGoBackwards() {
        Truck probed = aTruck().advance(Stage.PROBED, "P-1");
        assertThatThrownBy(() -> probed.advance(Stage.ARRIVED)).isInstanceOf(ConflictError.class);
    }

    @Test
    void probingNeedsASampleId() {
        assertThatThrownBy(() -> aTruck().advance(Stage.PROBED)).isInstanceOf(DrivewayError.class);
    }

    @Test
    void theSampleIdIsKeptThroughTheLaterStages() {
        Truck truck = aTruck().advance(Stage.PROBED, "P-1").advance(Stage.ON_SCALE);
        assertThat(truck.probeId()).contains("P-1");
    }

    @Test
    void waitingTimeIsMeasuredFromArrival() {
        assertThat(aTruck().waitingFor(EIGHT.plusMinutes(25))).isEqualTo(Duration.ofMinutes(25));
    }

    @Test
    void aClockBeforeArrivalIsRefused() {
        assertThatThrownBy(() -> aTruck("T-01", 10).waitingFor(EIGHT)).isInstanceOf(DrivewayError.class);
    }

    @Test
    void theQueueIsDeepAsTheTrucksOnIt() {
        assertThat(aDriveway().depth()).isEqualTo(3);
    }

    @Test
    void theSameTruckTwiceIsAConflict() {
        Driveway yard = aDriveway();
        assertThatThrownBy(() -> yard.arrive(aTruck("T-01", 0))).isInstanceOf(ConflictError.class);
    }

    @Test
    void aTruckNotOnTheDrivewaySaysSo() {
        assertThatThrownBy(() -> aDriveway().get("T-99")).isInstanceOf(DrivewayError.class);
    }

    @Test
    void theCompanyTruckGoesBeforeTheOlderProducers() {
        assertThat(aDriveway().nextUp().map(Truck::truckId)).contains("T-03");
    }

    @Test
    void aProbedTruckGoesBeforeTheCompanyOne() {
        Driveway yard = aDriveway();
        yard.advance("T-02", Stage.PROBED, "P-2");
        assertThat(yard.nextUp().map(Truck::truckId)).contains("T-02");
    }

    @Test
    void twoProbedTrucksGoOldestFirst() {
        Driveway yard = aDriveway();
        yard.advance("T-02", Stage.PROBED, "P-2");
        yard.advance("T-01", Stage.PROBED, "P-1");
        assertThat(yard.queue().subList(0, 2)).extracting(Truck::truckId).containsExactly("T-01", "T-02");
    }

    @Test
    void theTruckOnTheScaleIsFirstOfAll() {
        Driveway yard = aDriveway();
        yard.advance("T-02", Stage.PROBED, "P-2");
        yard.advance("T-02", Stage.ON_SCALE);
        assertThat(yard.nextUp().map(Truck::truckId)).contains("T-02");
        assertThat(yard.onScale().map(Truck::truckId)).contains("T-02");
    }

    @Test
    void onlyOneTruckFitsOnTheScale() {
        Driveway yard = aDriveway();
        yard.advance("T-02", Stage.PROBED, "P-2");
        yard.advance("T-02", Stage.ON_SCALE);
        yard.advance("T-01", Stage.PROBED, "P-1");
        assertThatThrownBy(() -> yard.advance("T-01", Stage.ON_SCALE)).isInstanceOf(ConflictError.class);
    }

    @Test
    void anEmptyDrivewayHasNobodyOnTheScale() {
        assertThat(new Driveway("HOME").onScale()).isEmpty();
        assertThat(new Driveway("HOME").nextUp()).isEmpty();
    }

    @Test
    void aFinishedTruckLeavesTheQueue() {
        Driveway yard = aDriveway();
        yard.advance("T-01", Stage.PROBED, "P-1");
        yard.advance("T-01", Stage.ON_SCALE);
        yard.advance("T-01", Stage.DUMPING);
        yard.advance("T-01", Stage.DONE);
        assertThat(yard.depth()).isEqualTo(2);
        assertThatThrownBy(() -> yard.get("T-01")).isInstanceOf(DrivewayError.class);
    }

    @Test
    void aTruckCanBePulledOutOfTheQueue() {
        Driveway yard = aDriveway();
        Truck pulled = yard.leave("T-02");
        assertThat(pulled.truckId()).isEqualTo("T-02");
        assertThat(yard.depth()).isEqualTo(2);
    }

    @Test
    void pullingATruckThatIsNotThereIsRefused() {
        assertThatThrownBy(() -> aDriveway().leave("T-99")).isInstanceOf(DrivewayError.class);
    }

    @Test
    void theLongestWaitIsTheOldestArrival() {
        assertThat(aDriveway().waitingLongest(EIGHT.plusMinutes(30)).map(Truck::truckId))
                .contains("T-01");
    }

    @Test
    void anEmptyDrivewayHasNobodyWaiting() {
        assertThat(new Driveway("HOME").waitingLongest(EIGHT)).isEmpty();
    }

    @Test
    void trucksOverTheWaitLimitAreNamed() {
        assertThat(aDriveway().overWait(EIGHT.plusMinutes(40), Duration.ofMinutes(30)))
                .extracting(Truck::truckId)
                .containsExactly("T-01", "T-02");
    }

    @Test
    void nobodyIsOverTheLimitOnAQuietMorning() {
        assertThat(aDriveway().overWait(EIGHT.plusMinutes(12), Duration.ofMinutes(35)))
                .isEmpty();
    }

    @Test
    void theQueueIsCountedByCommodity() {
        Driveway yard = aDriveway();
        yard.arrive(Truck.arrived("T-04", "MERRICK", "SOYB", EIGHT.plusMinutes(12)));
        assertThat(yard.byCommodity()).isEqualTo(Map.of("CORN", 3, "SOYB", 1));
    }
}
