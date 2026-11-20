package io.github.rkaushikdev.bushelworks.domain.fleet;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FleetTest {

    static final LocalDate WEIGHED = LocalDate.of(2024, 9, 20);
    static final LocalDate HARVEST = LocalDate.of(2024, 10, 3);

    static Tare aTare(String pounds, LocalDate weighedOn) {
        return new Tare(d(pounds), weighedOn, Optional.of("A-10400"));
    }

    static Tare aTare() {
        return aTare("26180", WEIGHED);
    }

    static Truck aTruck() {
        return Truck.of("T-14", "MERRICK", "Peterbilt hopper");
    }

    static Truck aTruck(String truckId) {
        return Truck.of(truckId, "MERRICK", "Peterbilt hopper");
    }

    @Test
    void anImplausibleTareIsRefused() {
        assertThatThrownBy(() -> aTare("5000", WEIGHED)).isInstanceOf(FleetError.class);
        assertThatThrownBy(() -> aTare("90000", WEIGHED)).isInstanceOf(FleetError.class);
    }

    @Test
    void aTareIsCurrentForAMonth() {
        Tare tare = aTare();
        assertThat(tare.expiresOn()).isEqualTo(WEIGHED.plusDays(Tare.TARE_LIFE_DAYS));
        assertThat(tare.isCurrentOn(HARVEST)).isTrue();
        assertThat(tare.isCurrentOn(LocalDate.of(2024, 12, 1))).isFalse();
    }

    @Test
    void aTareIsNotCurrentBeforeItWasTaken() {
        assertThat(aTare().isCurrentOn(LocalDate.of(2024, 9, 1))).isFalse();
    }

    @Test
    void theAgeOfATareIsCountedInDays() {
        assertThat(aTare().ageDays(HARVEST)).isEqualTo(13);
    }

    @Test
    void aTruckNeedsAnIdAndAnOwner() {
        assertThatThrownBy(() -> Truck.of("", "MERRICK", "")).isInstanceOf(FleetError.class);
        assertThatThrownBy(() -> Truck.of("T-14", "", "")).isInstanceOf(FleetError.class);
    }

    @Test
    void aNewTruckHasNoTare() {
        Truck truck = aTruck();
        assertThat(truck.latestTare()).isEmpty();
        assertThat(truck.tareOn(HARVEST)).isEmpty();
        assertThat(truck.tareIsStale(HARVEST)).isTrue();
    }

    @Test
    void weighingEmptyGivesTheTruckATare() {
        Truck truck = aTruck().weighEmpty(aTare());
        assertThat(truck.latestTare().map(Tare::pounds)).contains(d("26180"));
        assertThat(truck.tareIsStale(HARVEST)).isFalse();
    }

    @Test
    void theMostRecentCurrentTareIsUsed() {
        Truck truck = aTruck().weighEmpty(aTare()).weighEmpty(aTare("26420", LocalDate.of(2024, 10, 1)));
        assertThat(truck.tareOn(HARVEST).map(Tare::pounds)).contains(d("26420"));
    }

    @Test
    void anOldTareGoesStale() {
        Truck truck = aTruck().weighEmpty(aTare());
        assertThat(truck.tareIsStale(LocalDate.of(2024, 12, 1))).isTrue();
        assertThat(truck.latestTare()).isPresent();
    }

    @Test
    void weighingTheSameTruckTwiceInADayIsAConflict() {
        Truck truck = aTruck().weighEmpty(aTare());
        assertThatThrownBy(() -> truck.weighEmpty(aTare("26200", WEIGHED))).isInstanceOf(ConflictError.class);
    }

    @Test
    void aRetiredTruckCannotBeWeighed() {
        Truck truck = aTruck().retire(LocalDate.of(2024, 11, 1));
        assertThat(truck.isRetired()).isTrue();
        assertThatThrownBy(() -> truck.weighEmpty(aTare())).isInstanceOf(ConflictError.class);
    }

    @Test
    void retiringTwiceIsAConflict() {
        Truck truck = aTruck().retire(LocalDate.of(2024, 11, 1));
        assertThatThrownBy(() -> truck.retire(LocalDate.of(2024, 11, 2))).isInstanceOf(ConflictError.class);
    }

    @Test
    void oneTareShowsNoDrift() {
        assertThat(aTruck().weighEmpty(aTare()).drift()).isEmpty();
    }

    @Test
    void driftIsMeasuredFromTheFirstWeighing() {
        Truck truck = aTruck().weighEmpty(aTare()).weighEmpty(aTare("27380", LocalDate.of(2024, 10, 1)));
        assertThat(truck.drift()).contains(d("1200"));
    }

    @Test
    void aFleetHoldsItsTrucksInOrder() {
        Fleet fleet = new Fleet();
        fleet.add(aTruck("T-15"));
        fleet.add(aTruck());
        assertThat(fleet.active()).extracting(Truck::truckId).containsExactly("T-14", "T-15");
    }

    @Test
    void theSameTruckTwiceIsAConflict() {
        Fleet fleet = new Fleet();
        fleet.add(aTruck());
        assertThatThrownBy(() -> fleet.add(aTruck())).isInstanceOf(ConflictError.class);
    }

    @Test
    void aTruckNotOnTheFleetSaysSo() {
        assertThatThrownBy(() -> new Fleet().get("T-99")).isInstanceOf(FleetError.class);
    }

    @Test
    void aRetiredTruckDropsOffTheActiveList() {
        Fleet fleet = new Fleet();
        fleet.add(aTruck());
        fleet.replace(fleet.get("T-14").retire(LocalDate.of(2024, 11, 1)));
        assertThat(fleet.active()).isEmpty();
    }

    @Test
    void trucksCanBeFoundByOwner() {
        Fleet fleet = new Fleet();
        fleet.add(aTruck());
        fleet.add(Truck.of("T-20", "HOLLIS", "Peterbilt hopper"));
        assertThat(fleet.forParty("HOLLIS")).extracting(Truck::truckId).containsExactly("T-20");
    }

    @Test
    void theMorningListIsTheTrucksNeedingATare() {
        Fleet fleet = new Fleet();
        fleet.add(aTruck());
        fleet.add(aTruck("T-15"));
        fleet.replace(fleet.get("T-14").weighEmpty(aTare()));
        assertThat(fleet.needingATare(HARVEST)).extracting(Truck::truckId).containsExactly("T-15");
    }

    @Test
    void aStaleTarePutsATruckBackOnTheMorningList() {
        Fleet fleet = new Fleet();
        fleet.add(aTruck());
        fleet.replace(fleet.get("T-14").weighEmpty(aTare()));
        assertThat(fleet.needingATare(LocalDate.of(2024, 12, 1))).isNotEmpty();
    }

    @Test
    void aDriftingTruckIsFound() {
        Fleet fleet = new Fleet();
        fleet.add(aTruck());
        fleet.replace(fleet.get("T-14").weighEmpty(aTare()).weighEmpty(aTare("30180", LocalDate.of(2024, 10, 1))));
        assertThat(fleet.drifting()).extracting(Truck::truckId).containsExactly("T-14");
    }

    @Test
    void aSettledTruckIsNotDrifting() {
        Fleet fleet = new Fleet();
        fleet.add(aTruck());
        fleet.replace(fleet.get("T-14").weighEmpty(aTare()).weighEmpty(aTare("26380", LocalDate.of(2024, 10, 1))));
        assertThat(fleet.drifting()).isEmpty();
    }

    @Test
    void theAverageTareOfNothingIsNothing() {
        assertThat(Tare.average(List.of())).isEmpty();
    }

    @Test
    void theAverageTareRoundsToThePound() {
        assertThat(Tare.average(List.of(aTare(), aTare("26420", LocalDate.of(2024, 10, 1)))))
                .contains(d("26300"));
    }
}
