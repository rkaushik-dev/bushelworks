package io.github.rkaushikdev.bushelworks.schedules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Effective dated versions of one kind of schedule, keyed by commodity: the registry that decides
 * which published table a ticket settles on.
 *
 * <p>Discount tables, shrink factors, drying rates and storage rates all change, and they change on
 * a date rather than on a deploy. A ticket written in October has to settle on October's table
 * however many times it is reprinted, so nothing here reads the wall clock. Every lookup takes the
 * date it is asking about.
 *
 * <p>Two dimensions resolve here and they resolve in a fixed order:
 *
 * <ol>
 *   <li><b>Location.</b> A schedule filed against a specific elevator beats the one filed for the
 *       whole house. There is no blending and no fallback beyond one step: either the branch has
 *       its own table or it uses the company one.
 *   <li><b>Date.</b> Among the versions that apply to the resolved location, the one with the latest
 *       effective date not after the date being asked about wins.
 * </ol>
 *
 * <p>The order matters. A branch table filed in March does not lose to a company table filed in
 * April, because location is decided before date is looked at.
 *
 * <p>Resolution is cached. A settlement run resolves the same shrink table once per ticket, and the
 * registry is filed at start up with nothing in a request path adding to it. Filing invalidates the
 * cache, so a filing made mid run is still seen.
 *
 * @param <T> the kind of schedule
 */
public final class ScheduleRegistry<T> {

    /** The location code a company wide filing carries. */
    public static final String COMPANY_WIDE = "*";

    private record Key(String commodityCode, LocalDate asOf, String locationCode) {}

    private final String kind;
    private final Map<String, List<Version<T>>> versions = new TreeMap<>();
    private final Map<Key, Version<T>> resolved = new HashMap<>();
    private final Set<Key> misses = new HashSet<>();

    public ScheduleRegistry(String kind) {
        this.kind = kind;
    }

    /** What the registry holds, as an error message names it: shrink, drying, storage. */
    public String kind() {
        return kind;
    }

    /**
     * Add a version.
     *
     * <p>Filing a table that starts later than an open ended one closes the open one the day before,
     * which is what the office means when it says a new schedule "takes over" on the first.
     * Anything else that would put two payloads on one day is refused, because there is no rule that
     * says which of them a ticket should settle on.
     */
    public synchronized void file(String commodityCode, Version<T> version) {
        List<Version<T>> bucket = versions.computeIfAbsent(commodityCode, code -> new ArrayList<>());
        for (int index = 0; index < bucket.size(); index++) {
            Version<T> existing = bucket.get(index);
            if (!existing.locationCode().equals(version.locationCode()) || !overlaps(existing, version)) {
                continue;
            }
            if (existing.effectiveTo().isEmpty() && existing.effectiveFrom().isBefore(version.effectiveFrom())) {
                bucket.set(index, existing.endingOn(version.effectiveFrom().minusDays(1)));
                continue;
            }
            throw new OverlappingVersions(kind + "/" + commodityCode + " at " + version.locationCode() + ": "
                    + version.effectiveFrom() + " overlaps a version already filed");
        }
        bucket.add(version);
        bucket.sort(
                Comparator.comparing((Version<T> each) -> each.effectiveFrom()).thenComparing(Version::locationCode));
        invalidate();
    }

    /** Throw the resolution cache away. Called on every filing. */
    public synchronized void invalidate() {
        resolved.clear();
        misses.clear();
    }

    public void fileAll(String commodityCode, List<Version<T>> filings) {
        filings.forEach(version -> file(commodityCode, version));
    }

    /** The company wide payload in effect. */
    public T resolve(String commodityCode, LocalDate asOf) {
        return resolve(commodityCode, asOf, COMPANY_WIDE);
    }

    /** The payload in effect at a location, or a refusal saying exactly what was missing. */
    public T resolve(String commodityCode, LocalDate asOf, String locationCode) {
        return resolveVersion(commodityCode, asOf, locationCode).payload();
    }

    public synchronized Version<T> resolveVersion(String commodityCode, LocalDate asOf, String locationCode) {
        Key key = new Key(commodityCode, asOf, locationCode);
        Version<T> cached = resolved.get(key);
        if (cached != null) {
            return cached;
        }
        if (misses.contains(key)) {
            throw missing(key);
        }
        Version<T> found = resolveUncached(key);
        resolved.put(key, found);
        return found;
    }

    private Version<T> resolveUncached(Key key) {
        List<Version<T>> bucket = versions.getOrDefault(key.commodityCode(), List.of());
        Optional<Version<T>> local = latest(bucket.stream()
                .filter(v -> v.locationCode().equals(key.locationCode()) && v.covers(key.asOf()))
                .toList());
        if (local.isPresent()) {
            return local.get();
        }
        if (!key.locationCode().equals(COMPANY_WIDE)) {
            Optional<Version<T>> company = latest(bucket.stream()
                    .filter(v -> v.isCompanyWide() && v.covers(key.asOf()))
                    .toList());
            if (company.isPresent()) {
                return company.get();
            }
        }
        misses.add(key);
        throw missing(key);
    }

    /** The version with the latest effective date; the first of several on the same day. */
    private Optional<Version<T>> latest(List<Version<T>> candidates) {
        Version<T> best = null;
        for (Version<T> candidate : candidates) {
            if (best == null || candidate.effectiveFrom().isAfter(best.effectiveFrom())) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private NoScheduleInEffect missing(Key key) {
        return new NoScheduleInEffect(
                "no " + kind + " filed for " + key.commodityCode() + " at " + key.locationCode() + " on " + key.asOf());
    }

    /** How many answers the cache is holding. */
    public synchronized int cached() {
        return resolved.size() + misses.size();
    }

    public Optional<T> maybeResolve(String commodityCode, LocalDate asOf) {
        return maybeResolve(commodityCode, asOf, COMPANY_WIDE);
    }

    /** The payload in effect, or nothing when none is filed. */
    public Optional<T> maybeResolve(String commodityCode, LocalDate asOf, String locationCode) {
        try {
            return Optional.of(resolve(commodityCode, asOf, locationCode));
        } catch (NoScheduleInEffect nothingFiled) {
            return Optional.empty();
        }
    }

    /** Every version filed for a commodity, oldest first. */
    public synchronized List<Version<T>> history(String commodityCode) {
        return List.copyOf(versions.getOrDefault(commodityCode, List.of()));
    }

    /** Every commodity with something filed, sorted. */
    public synchronized List<String> commodities() {
        return List.copyOf(versions.keySet());
    }

    /** Every location a commodity has a filing for, sorted. */
    public synchronized List<String> locations(String commodityCode) {
        return versions.getOrDefault(commodityCode, List.of()).stream()
                .map(Version::locationCode)
                .distinct()
                .sorted()
                .toList();
    }

    private static <T> boolean overlaps(Version<T> left, Version<T> right) {
        if (left.effectiveTo().isPresent()
                && right.effectiveFrom().isAfter(left.effectiveTo().get())) {
            return false;
        }
        return right.effectiveTo().isEmpty()
                || !left.effectiveFrom().isAfter(right.effectiveTo().get());
    }
}
