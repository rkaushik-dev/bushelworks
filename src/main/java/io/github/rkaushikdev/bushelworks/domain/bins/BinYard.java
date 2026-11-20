package io.github.rkaushikdev.bushelworks.domain.bins;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Every bin at one site.
 *
 * <p>The position report says what the house owns. This says where it is, which is a different
 * question and the one that matters when a buyer wants a hundred thousand bushels of No. 2 loaded
 * by Friday.
 */
public final class BinYard {

    private final String locationCode;
    private final TreeMap<String, Bin> bins = new TreeMap<>();

    public BinYard(String locationCode) {
        this(locationCode, List.of());
    }

    public BinYard(String locationCode, List<Bin> initial) {
        this.locationCode = locationCode;
        initial.forEach(this::add);
    }

    public String locationCode() {
        return locationCode;
    }

    public void add(Bin one) {
        if (bins.containsKey(one.binId())) {
            throw new ConflictError(one.binId() + " is already in the yard");
        }
        if (!one.locationCode().equals(locationCode)) {
            throw new BinError(one.binId() + " is at " + one.locationCode() + ", not " + locationCode);
        }
        bins.put(one.binId(), one);
    }

    public Bin get(String binId) {
        Bin found = bins.get(binId);
        if (found == null) {
            throw new BinError("no bin " + binId + " at " + locationCode);
        }
        return found;
    }

    /** Put back a bin whose contents changed. It has to be in the yard already. */
    public void replace(Bin one) {
        get(one.binId());
        bins.put(one.binId(), one);
    }

    /** Every bin, by bin id. */
    public List<Bin> bins() {
        return List.copyOf(bins.values());
    }

    public List<Bin> holding(String commodityCode) {
        return bins.values().stream()
                .filter(one -> one.commodityCode().filter(commodityCode::equals).isPresent())
                .toList();
    }

    /** Everything in the yard. */
    public BigDecimal totalBushels() {
        return total(bins());
    }

    /** Everything of one commodity in the yard. */
    public BigDecimal totalBushels(String commodityCode) {
        return total(holding(commodityCode));
    }

    private static BigDecimal total(List<Bin> which) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Bin one : which) {
            sum = sum.add(one.bushels());
        }
        return Units.quantize(sum, Units.BUSHEL_PLACES);
    }

    private static boolean canHold(Bin one, String commodityCode) {
        return one.commodityCode().isEmpty() || one.commodityCode().get().equals(commodityCode);
    }

    /** Everywhere a load of this commodity could go. */
    public BigDecimal roomFor(String commodityCode) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Bin one : bins.values()) {
            if (!one.outOfService() && canHold(one, commodityCode)) {
                sum = sum.add(one.headroom());
            }
        }
        return Units.quantize(sum, Units.BUSHEL_PLACES);
    }

    /**
     * Where to put a load.
     *
     * <p>Into an open bin of the same commodity before an empty one, and the fullest of those first,
     * so partly filled bins get finished rather than the yard ending up with nine bins a third
     * full. With none of those, the smallest empty bin that takes it.
     */
    public Optional<Bin> bestBinFor(String commodityCode, BigDecimal amount) {
        List<Bin> candidates = bins.values().stream()
                .filter(one -> one.accepts(commodityCode, amount))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<Bin> same = candidates.stream()
                .filter(one -> one.commodityCode().filter(commodityCode::equals).isPresent())
                .toList();
        if (!same.isEmpty()) {
            return same.stream().max(Comparator.comparing(Bin::bushels).thenComparing(Bin::binId));
        }
        return candidates.stream()
                .min(Comparator.comparing(Bin::capacityBushels).thenComparing(Bin::binId));
    }
}
