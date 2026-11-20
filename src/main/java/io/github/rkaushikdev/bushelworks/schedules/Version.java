package io.github.rkaushikdev.bushelworks.schedules;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One filed version of a schedule.
 *
 * @param effectiveFrom the first day it applies
 * @param payload the schedule itself
 * @param effectiveTo the last day it applies, or nothing while it is open ended
 * @param locationCode the elevator it was filed for, or {@link ScheduleRegistry#COMPANY_WIDE}
 * @param note anything the office wrote on the filing
 * @param <T> the kind of schedule
 */
public record Version<T>(
        LocalDate effectiveFrom, T payload, Optional<LocalDate> effectiveTo, String locationCode, String note) {

    public Version {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(effectiveTo, "effectiveTo");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(note, "note");
        if (effectiveTo.isPresent() && effectiveTo.get().isBefore(effectiveFrom)) {
            throw new OverlappingVersions("version starting " + effectiveFrom + " ends before it begins");
        }
    }

    /** An open ended, company wide version. */
    public static <T> Version<T> of(LocalDate effectiveFrom, T payload) {
        return new Version<>(effectiveFrom, payload, Optional.empty(), ScheduleRegistry.COMPANY_WIDE, "");
    }

    /** The same version, closing on {@code day}. */
    public Version<T> endingOn(LocalDate day) {
        return new Version<>(effectiveFrom, payload, Optional.of(day), locationCode, note);
    }

    /** The same version, filed for one elevator. */
    public Version<T> at(String location) {
        return new Version<>(effectiveFrom, payload, effectiveTo, location, note);
    }

    /** Whether the version applies on {@code asOf}, both ends included. */
    public boolean covers(LocalDate asOf) {
        if (asOf.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo.isEmpty() || !asOf.isAfter(effectiveTo.get());
    }

    public boolean isCompanyWide() {
        return locationCode.equals(ScheduleRegistry.COMPANY_WIDE);
    }

    /** Versions with the newest effective date first. */
    public static <T> List<Version<T>> newestFirst(List<Version<T>> versions) {
        return versions.stream()
                .sorted(Comparator.comparing((Version<T> version) -> version.effectiveFrom())
                        .reversed())
                .toList();
    }
}
