package io.github.rkaushikdev.bushelworks.services.binyard;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.bins.Bin;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.BinReadingRow;
import io.github.rkaushikdev.bushelworks.persistence.models.BinRow;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Putting grain away and getting it back out.
 *
 * <p>The bin yard is the only place that knows where anything physically is. The position report says
 * the house owns four hundred thousand bushels of corn; this says which nine bins they are in and
 * what each one tests, which is what the loader operator needs at seven in the morning.
 *
 * <p>Nothing here decides what a load is worth. It decides where it goes, and the choice matters: a
 * wet load into a bin of dry corn spoils the average and a bin of No. 3 mixed into a bin of No. 2
 * costs the difference on every bushel.
 */
public final class BinYard {

    /** Above this many degrees a bin is working and somebody has to look at it that day. */
    public static final BigDecimal WARM_CELSIUS = BinReadingRow.WARM;

    private static final Logger LOG = HouseLog.getLogger("binyard");

    private static final BigDecimal NO_ROOM = new BigDecimal("0.0000");

    /** A blended moisture, test weight or damage reading is kept to hundredths. */
    private static final BigDecimal BLEND_PLACES = new BigDecimal("0.01");

    private BinYard() {}

    /**
     * One bin at one site.
     *
     * @throws BinNotFound when the site has no bin by that id
     */
    public static BinRow binAt(UnitOfWork uow, String locationCode, String binId) {
        return uow
                .session()
                .createQuery("select b from BinRow b where b.locationCode = :location and b.binId = :bin", BinRow.class)
                .setParameter("location", locationCode)
                .setParameter("bin", binId)
                .getResultList()
                .stream()
                .findFirst()
                .orElseThrow(() -> new BinNotFound("no bin " + binId + " at " + locationCode));
    }

    /** Every bin at a site, by bin id. */
    public static List<BinRow> binsAt(UnitOfWork uow, String locationCode) {
        return binsAt(uow, locationCode, null);
    }

    /** The bins at a site holding one commodity, by bin id; every bin when the commodity is null. */
    public static List<BinRow> binsAt(UnitOfWork uow, String locationCode, String commodityCode) {
        TypedQuery<BinRow> query = uow.session()
                .createQuery(
                        "select b from BinRow b where b.locationCode = :location"
                                + (commodityCode == null ? "" : " and b.commodityCode = :commodity")
                                + " order by b.binId",
                        BinRow.class)
                .setParameter("location", locationCode);
        if (commodityCode != null) {
            query.setParameter("commodity", commodityCode);
        }
        return query.getResultList();
    }

    /** What can still go in before the bin is called full, never less than nothing. */
    public static BigDecimal workingHeadroom(BinRow row) {
        BigDecimal room = row.getCapacityBushels().multiply(Bin.WORKING_FILL).subtract(row.getBushels());
        return room.signum() > 0 ? Units.quantize(room, Units.BUSHEL_PLACES) : NO_ROOM;
    }

    /**
     * Where to put a load.
     *
     * <p>Into a bin already holding the commodity before an empty one, and the fullest of those first,
     * so partly filled bins get finished rather than the yard ending up with nine bins a third full.
     * Failing that, the smallest empty bin that takes it. A bin out of service is never chosen.
     *
     * @return the bin, or empty when nowhere at the site has the room
     */
    public static Optional<BinRow> chooseBin(
            UnitOfWork uow, String locationCode, String commodityCode, BigDecimal bushels) {
        List<BinRow> candidates = binsAt(uow, locationCode).stream()
                .filter(row -> !row.isOutOfService())
                .filter(row -> takes(row, commodityCode))
                .filter(row -> workingHeadroom(row).compareTo(bushels) >= 0)
                .toList();
        List<BinRow> same = candidates.stream()
                .filter(row ->
                        row.getCommodityCode().filter(commodityCode::equals).isPresent())
                .toList();
        if (!same.isEmpty()) {
            return same.stream().max(Comparator.comparing(BinRow::getBushels).thenComparing(BinRow::getBinId));
        }
        return candidates.stream()
                .min(Comparator.comparing(BinRow::getCapacityBushels).thenComparing(BinRow::getBinId));
    }

    /** Put a load into whichever bin {@link #chooseBin} picks, blending it with what is there. */
    public static Placed putAway(
            UnitOfWork uow,
            String locationCode,
            String commodityCode,
            BigDecimal bushels,
            Map<Factor, BigDecimal> sample) {
        return putAway(uow, locationCode, commodityCode, bushels, sample, null);
    }

    /**
     * Put a load into a bin, blending it with what is already there. Logged as {@code bin.filled}.
     *
     * <p>The bin's moisture, test weight and damage become the bushel weighted average of what was in
     * it and what the load tested, to hundredths. A factor the load was not tested for leaves the
     * bin's reading as it was.
     *
     * @param binId the bin to use; null or empty to let the yard choose
     * @throws BinYardError when there is nothing to put away, nowhere to put it, or not the room
     * @throws ConflictError when the bin is out of service or holds another commodity
     * @throws BinNotFound when the bin named is not at the site
     */
    public static Placed putAway(
            UnitOfWork uow,
            String locationCode,
            String commodityCode,
            BigDecimal bushels,
            Map<Factor, BigDecimal> sample,
            String binId) {
        BigDecimal wanted = Units.quantize(bushels, Units.BUSHEL_PLACES);
        if (wanted.signum() <= 0) {
            throw new BinYardError("nothing to put away");
        }

        BinRow row = binId != null && !binId.isEmpty()
                ? binAt(uow, locationCode, binId)
                : chooseBin(uow, locationCode, commodityCode, wanted)
                        .orElseThrow(() -> new BinYardError("nowhere at " + locationCode + " will take " + wanted
                                + " bushels of " + commodityCode));
        if (row.isOutOfService()) {
            throw new ConflictError(
                    row + " is out of service: " + row.getOutOfServiceReason().orElse("None"));
        }
        if (!takes(row, commodityCode)) {
            throw new ConflictError(row + " holds " + row.getCommodityCode().orElse("None") + ", not " + commodityCode);
        }
        if (workingHeadroom(row).compareTo(wanted) < 0) {
            throw new BinYardError(row + ": only " + workingHeadroom(row) + " bushels of room, asked for " + wanted);
        }

        BigDecimal existing = row.getBushels();
        row.setCommodityCode(commodityCode);
        row.setMoisture(blend(row.getMoisture(), existing, sample.get(Factor.MOISTURE), wanted));
        row.setTestWeight(blend(row.getTestWeight(), existing, sample.get(Factor.TEST_WEIGHT), wanted));
        row.setDamage(blend(row.getDamage(), existing, sample.get(Factor.DAMAGE_TOTAL), wanted));
        row.setBushels(Units.quantize(existing.add(wanted), Units.BUSHEL_PLACES));
        uow.flush();

        HouseLog.event(
                LOG,
                "bin.filled",
                field("bin", row.toString()),
                field("commodity", commodityCode),
                field("bushels", wanted),
                field("holding", row.getBushels()));
        return new Placed(row.getBinId(), wanted, commodityCode, workingHeadroom(row));
    }

    /** Whether a bin is empty of any commodity or already holds this one. */
    private static boolean takes(BinRow row, String commodityCode) {
        return row.getCommodityCode().map(commodityCode::equals).orElse(true);
    }

    /** The bushel weighted average, or what was known already when either side was never measured. */
    private static BigDecimal blend(
            Optional<BigDecimal> existingValue,
            BigDecimal existingBushels,
            BigDecimal incomingValue,
            BigDecimal incomingBushels) {
        if (incomingValue == null) {
            return existingValue.orElse(null);
        }
        if (existingValue.isEmpty() || existingBushels.signum() <= 0) {
            return incomingValue;
        }
        BigDecimal total = existingBushels.add(incomingBushels);
        BigDecimal weighted =
                existingValue.get().multiply(existingBushels).add(incomingValue.multiply(incomingBushels));
        return Units.quantize(Decimals.divide(weighted, total), BLEND_PLACES);
    }

    /**
     * Take grain out. What is left tests the same as what came out; an emptied bin holds nothing and
     * tests for nothing.
     *
     * @throws BinYardError when there is nothing to draw or not that much in the bin
     * @throws BinNotFound when the site has no bin by that id
     */
    public static BinRow drawFrom(UnitOfWork uow, String locationCode, String binId, BigDecimal bushels) {
        BinRow row = binAt(uow, locationCode, binId);
        BigDecimal wanted = Units.quantize(bushels, Units.BUSHEL_PLACES);
        if (wanted.signum() <= 0) {
            throw new BinYardError(row + ": nothing to draw");
        }
        if (wanted.compareTo(row.getBushels()) > 0) {
            throw new BinYardError(row + ": only " + row.getBushels() + " bushels in it, asked for " + wanted);
        }
        BigDecimal left = Units.quantize(row.getBushels().subtract(wanted), Units.BUSHEL_PLACES);
        row.setBushels(left);
        if (left.signum() == 0) {
            row.setCommodityCode(null);
            row.setMoisture(null);
            row.setTestWeight(null);
            row.setDamage(null);
        }
        uow.flush();
        return row;
    }

    /** Log a reading off cable one with the fan off, warning if the bin is working. */
    public static BinReadingRow recordReading(
            UnitOfWork uow, String locationCode, String binId, OffsetDateTime takenAt, BigDecimal temperatureC) {
        return recordReading(uow, locationCode, binId, takenAt, temperatureC, ReadingOptions.defaults());
    }

    /**
     * Log a cable reading. One above {@link #WARM_CELSIUS} is logged as a {@code bin.warm} warning.
     *
     * @throws BinNotFound when the site has no bin by that id
     */
    public static BinReadingRow recordReading(
            UnitOfWork uow,
            String locationCode,
            String binId,
            OffsetDateTime takenAt,
            BigDecimal temperatureC,
            ReadingOptions options) {
        BinRow row = binAt(uow, locationCode, binId);
        BinReadingRow reading = new BinReadingRow();
        reading.setTakenAt(takenAt);
        reading.setCable(options.cable());
        reading.setTemperatureC(temperatureC);
        reading.setMoisture(options.moisture().orElse(null));
        reading.setFanRunning(options.fanRunning());
        reading.setNote(options.note().orElse(null));
        row.addReading(reading);
        uow.session().persist(reading);
        uow.flush();
        if (temperatureC.compareTo(WARM_CELSIUS) > 0) {
            HouseLog.warn(
                    LOG,
                    "bin.warm",
                    field("bin", row.toString()),
                    field("cable", options.cable()),
                    field("temperature", temperatureC),
                    field("fan", options.fanRunning()));
        }
        return reading;
    }

    /**
     * The bins at a site with a warm reading taken at or after {@code since}, by bin id. Moments are
     * compared as instants, whatever offset each was written with.
     */
    public static List<BinRow> warmBins(UnitOfWork uow, String locationCode, OffsetDateTime since) {
        return binsAt(uow, locationCode).stream()
                .filter(row -> row.getReadings().stream()
                        .anyMatch(reading -> !reading.getTakenAt().isBefore(since) && reading.isWarm()))
                .toList();
    }

    /** Everything in the bins at a site. */
    public static BigDecimal yardTotal(UnitOfWork uow, String locationCode) {
        return yardTotal(uow, locationCode, null);
    }

    /** Everything of one commodity in the bins at a site; every commodity when it is null. */
    public static BigDecimal yardTotal(UnitOfWork uow, String locationCode, String commodityCode) {
        BigDecimal total = BigDecimal.ZERO;
        for (BinRow row : binsAt(uow, locationCode, commodityCode)) {
            total = total.add(row.getBushels());
        }
        return Units.quantize(total, Units.BUSHEL_PLACES);
    }

    /**
     * Stop a bin taking grain, and say why.
     *
     * @param on the day it came out, recorded as the day it was last turned
     * @throws BinYardError when no reason is given
     * @throws ConflictError when the bin is out of service already
     * @throws BinNotFound when the site has no bin by that id
     */
    public static BinRow takeOutOfService(
            UnitOfWork uow, String locationCode, String binId, String reason, LocalDate on) {
        BinRow row = binAt(uow, locationCode, binId);
        if (reason.isEmpty()) {
            throw new BinYardError(row + ": needs a reason");
        }
        if (row.isOutOfService()) {
            throw new ConflictError(row + " is already out of service");
        }
        row.setOutOfService(true);
        row.setOutOfServiceReason(reason);
        row.setLastTurnedOn(on);
        uow.flush();
        return row;
    }
}
