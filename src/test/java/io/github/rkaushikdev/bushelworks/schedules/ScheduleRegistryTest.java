package io.github.rkaushikdev.bushelworks.schedules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleRegistryTest {

    static LocalDate day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth);
    }

    static ScheduleRegistry<String> registry() {
        ScheduleRegistry<String> reg = new ScheduleRegistry<>("discount");
        reg.file("CORN", Version.of(day(2024, 1, 1), "company-jan"));
        reg.file("CORN", Version.of(day(2024, 9, 1), "company-sep"));
        reg.file("CORN", Version.of(day(2024, 3, 1), "dorrance-mar").at("DORRANCE"));
        return reg;
    }

    @Test
    void resolvesTheLatestVersionNotAfterTheDate() {
        ScheduleRegistry<String> reg = registry();
        assertThat(reg.resolve("CORN", day(2024, 8, 31))).isEqualTo("company-jan");
        assertThat(reg.resolve("CORN", day(2024, 9, 1))).isEqualTo("company-sep");
    }

    @Test
    void aBranchTableBeatsANewerCompanyTable() {
        assertThat(registry().resolve("CORN", day(2024, 10, 5), "DORRANCE")).isEqualTo("dorrance-mar");
    }

    @Test
    void aBranchWithNoTableOfItsOwnFallsBackToTheCompany() {
        assertThat(registry().resolve("CORN", day(2024, 10, 5), "HOME")).isEqualTo("company-sep");
    }

    @Test
    void nothingInEffectBeforeTheFirstFiling() {
        assertThatThrownBy(() -> registry().resolve("CORN", day(2023, 12, 31))).isInstanceOf(NoScheduleInEffect.class);
    }

    @Test
    void anUnknownCommodityRaisesTheSameWay() {
        assertThatThrownBy(() -> registry().resolve("SOYB", day(2024, 5, 1))).isInstanceOf(NoScheduleInEffect.class);
    }

    @Test
    void maybeResolveReturnsNothingInsteadOfRaising() {
        ScheduleRegistry<String> reg = registry();
        assertThat(reg.maybeResolve("SOYB", day(2024, 5, 1))).isEmpty();
        assertThat(reg.maybeResolve("CORN", day(2024, 5, 1))).contains("company-jan");
    }

    @Test
    void aClosedVersionStopsApplyingAfterItsEndDate() {
        ScheduleRegistry<String> reg = new ScheduleRegistry<>("shrink");
        reg.file("CORN", Version.of(day(2024, 1, 1), "first-half").endingOn(day(2024, 6, 30)));
        assertThat(reg.resolve("CORN", day(2024, 6, 30))).isEqualTo("first-half");
        assertThatThrownBy(() -> reg.resolve("CORN", day(2024, 7, 1))).isInstanceOf(NoScheduleInEffect.class);
    }

    @Test
    void aLaterFilingClosesTheOpenOneTheDayBefore() {
        ScheduleRegistry<String> reg = new ScheduleRegistry<>("shrink");
        reg.file("CORN", Version.of(day(2024, 1, 1), "a"));
        reg.file("CORN", Version.of(day(2024, 5, 1), "b"));
        Version<String> closed = reg.history("CORN").get(0);
        assertThat(closed.payload()).isEqualTo("a");
        assertThat(closed.effectiveTo()).contains(day(2024, 4, 30));
        assertThat(reg.resolve("CORN", day(2024, 4, 30))).isEqualTo("a");
        assertThat(reg.resolve("CORN", day(2024, 5, 1))).isEqualTo("b");
    }

    @Test
    void twoVersionsMayNotStartOnTheSameDay() {
        ScheduleRegistry<String> reg = new ScheduleRegistry<>("shrink");
        reg.file("CORN", Version.of(day(2024, 1, 1), "a"));
        assertThatThrownBy(() -> reg.file("CORN", Version.of(day(2024, 1, 1), "b")))
                .isInstanceOf(OverlappingVersions.class);
    }

    @Test
    void aFilingCannotBeBackdatedUnderAnOpenOne() {
        ScheduleRegistry<String> reg = new ScheduleRegistry<>("shrink");
        reg.file("CORN", Version.of(day(2024, 5, 1), "a"));
        assertThatThrownBy(() -> reg.file("CORN", Version.of(day(2024, 1, 1), "b")))
                .isInstanceOf(OverlappingVersions.class);
    }

    @Test
    void aClosedVersionLeavesRoomForTheNextOne() {
        ScheduleRegistry<String> reg = new ScheduleRegistry<>("shrink");
        reg.file("CORN", Version.of(day(2024, 1, 1), "a").endingOn(day(2024, 6, 30)));
        reg.file("CORN", Version.of(day(2024, 7, 1), "b"));
        assertThat(reg.resolve("CORN", day(2024, 7, 2))).isEqualTo("b");
    }

    @Test
    void versionsForDifferentSitesDoNotCollide() {
        ScheduleRegistry<String> reg = new ScheduleRegistry<>("shrink");
        reg.file("CORN", Version.of(day(2024, 1, 1), "company"));
        reg.file("CORN", Version.of(day(2024, 1, 1), "branch").at("LURAY"));
        assertThat(reg.resolve("CORN", day(2024, 2, 1))).isEqualTo("company");
        assertThat(reg.resolve("CORN", day(2024, 2, 1), "LURAY")).isEqualTo("branch");
    }

    @Test
    void aVersionMayNotEndBeforeItStarts() {
        assertThatThrownBy(() -> Version.of(day(2024, 6, 1), "nope").endingOn(day(2024, 5, 1)))
                .isInstanceOf(OverlappingVersions.class);
    }

    @Test
    void coversIsInclusiveAtBothEnds() {
        Version<String> version = Version.of(day(2024, 1, 1), "jan").endingOn(day(2024, 1, 31));
        assertThat(version.covers(day(2024, 1, 1))).isTrue();
        assertThat(version.covers(day(2024, 1, 31))).isTrue();
        assertThat(version.covers(day(2024, 2, 1))).isFalse();
        assertThat(version.isCompanyWide()).isTrue();
    }

    @Test
    void registryReportsWhatItHolds() {
        ScheduleRegistry<String> reg = registry();
        assertThat(reg.commodities()).containsExactly("CORN");
        assertThat(reg.locations("CORN")).containsExactly("*", "DORRANCE");
        assertThat(reg.history("CORN")).hasSize(3);
        assertThat(reg.kind()).isEqualTo("discount");
    }

    @Test
    void aResolutionIsCached() {
        ScheduleRegistry<String> reg = registry();
        assertThat(reg.cached()).isZero();
        reg.resolve("CORN", day(2024, 5, 1));
        assertThat(reg.cached()).isEqualTo(1);
        reg.resolve("CORN", day(2024, 5, 1));
        assertThat(reg.cached()).isEqualTo(1);
    }

    @Test
    void theCacheAnswersTheSameThing() {
        ScheduleRegistry<String> reg = registry();
        String first = reg.resolve("CORN", day(2024, 5, 1));
        String second = reg.resolve("CORN", day(2024, 5, 1));
        assertThat(first).isEqualTo(second);
    }

    @Test
    void aMissIsCachedToo() {
        ScheduleRegistry<String> reg = registry();
        assertThatThrownBy(() -> reg.resolve("SOYB", day(2024, 5, 1))).isInstanceOf(NoScheduleInEffect.class);
        assertThat(reg.cached()).isEqualTo(1);
        assertThatThrownBy(() -> reg.resolve("SOYB", day(2024, 5, 1))).isInstanceOf(NoScheduleInEffect.class);
        assertThat(reg.cached()).isEqualTo(1);
    }

    @Test
    void aCachedMissStillNamesWhatWasMissing() {
        ScheduleRegistry<String> reg = registry();
        assertThatThrownBy(() -> reg.resolve("SOYB", day(2024, 5, 1))).isInstanceOf(NoScheduleInEffect.class);
        assertThatThrownBy(() -> reg.resolve("SOYB", day(2024, 5, 1)))
                .isInstanceOf(NoScheduleInEffect.class)
                .hasMessageContaining("SOYB");
    }

    @Test
    void differentDaysAreCachedSeparately() {
        ScheduleRegistry<String> reg = registry();
        reg.resolve("CORN", day(2024, 5, 1));
        reg.resolve("CORN", day(2024, 10, 1));
        assertThat(reg.cached()).isEqualTo(2);
    }

    @Test
    void differentSitesAreCachedSeparately() {
        ScheduleRegistry<String> reg = registry();
        reg.resolve("CORN", day(2024, 10, 1), "DORRANCE");
        reg.resolve("CORN", day(2024, 10, 1), "HOME");
        assertThat(reg.cached()).isEqualTo(2);
    }

    @Test
    void filingThrowsTheCacheAway() {
        ScheduleRegistry<String> reg = registry();
        reg.resolve("CORN", day(2024, 10, 1));
        assertThat(reg.cached()).isEqualTo(1);
        reg.file("SOYB", Version.of(day(2024, 1, 1), "beans"));
        assertThat(reg.cached()).isZero();
    }

    @Test
    void aFilingChangesWhatACachedKeyResolvesTo() {
        ScheduleRegistry<String> reg = registry();
        assertThat(reg.resolve("CORN", day(2024, 12, 1))).isEqualTo("company-sep");
        reg.file("CORN", Version.of(day(2024, 11, 1), "company-nov"));
        assertThat(reg.resolve("CORN", day(2024, 12, 1))).isEqualTo("company-nov");
    }

    @Test
    void aFilingClearsACachedMiss() {
        ScheduleRegistry<String> reg = registry();
        assertThatThrownBy(() -> reg.resolve("SOYB", day(2024, 5, 1))).isInstanceOf(NoScheduleInEffect.class);
        reg.file("SOYB", Version.of(day(2024, 1, 1), "beans"));
        assertThat(reg.resolve("SOYB", day(2024, 5, 1))).isEqualTo("beans");
    }

    @Test
    void maybeResolveUsesTheSameCache() {
        ScheduleRegistry<String> reg = registry();
        assertThat(reg.maybeResolve("SOYB", day(2024, 5, 1))).isEmpty();
        assertThat(reg.cached()).isEqualTo(1);
        assertThat(reg.maybeResolve("SOYB", day(2024, 5, 1))).isEmpty();
    }

    @Test
    void newestFirstOrdersByEffectiveDate() {
        List<Version<String>> ordered = Version.newestFirst(registry().history("CORN"));
        assertThat(ordered.get(0).effectiveFrom()).isEqualTo(day(2024, 9, 1));
        assertThat(ordered.get(ordered.size() - 1).effectiveFrom()).isEqualTo(day(2024, 1, 1));
        assertThat(ScheduleRegistry.COMPANY_WIDE).isEqualTo("*");
    }
}
