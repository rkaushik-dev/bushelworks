package io.github.rkaushikdev.bushelworks.services.seeding;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodity;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.BinRow;
import io.github.rkaushikdev.bushelworks.persistence.models.CommodityRow;
import io.github.rkaushikdev.bushelworks.persistence.models.Location;
import io.github.rkaushikdev.bushelworks.services.parties.Parties;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/**
 * A house with something in it, for a demonstration or a fresh install.
 *
 * <p>An empty database is hard to look at. This puts one real looking elevator in it: two sites,
 * six producers, the commodity master, a yard of bins and a week of harvest. It is deterministic, so
 * two people running it get the same numbers and can talk about the same ticket.
 *
 * <p>Nothing here is fixture data for the tests. The tests build exactly what they need; this is
 * for the screen the manager is shown before he buys anything, and for a developer who wants to see
 * a statement without keying nine loads.
 */
public final class Seeding {

    /** The first day of the seeded harvest week. */
    public static final LocalDate SEASON_OPENS = LocalDate.of(2024, 9, 16);

    /** The two sites: code, name, warehouse licence and licensed capacity in bushels. */
    public static final List<Site> SITES = List.of(
            new Site("HOME", "Home elevator", "KS-1174", 1_450_000),
            new Site("LURAY", "Luray branch", "KS-1175", 620_000));

    /** The six producers: code and name. */
    public static final List<Producer> PRODUCERS = List.of(
            new Producer("MERRICK", "Merrick Farms LLC"),
            new Producer("BROCKMAN", "Ellen Brockman"),
            new Producer("HOLLIS", "Hollis Brothers"),
            new Producer("STROH", "Stroh Cattle Co"),
            new Producer("VOSS", "Voss Land and Grain"),
            new Producer("KREHBIEL", "Krehbiel Seed Farms"));

    /** The yard: site, bin and capacity in bushels. */
    public static final List<Bin> BINS = List.of(
            new Bin("HOME", "B-01", 40_000),
            new Bin("HOME", "B-02", 100_000),
            new Bin("HOME", "B-03", 250_000),
            new Bin("HOME", "B-04", 250_000),
            new Bin("LURAY", "B-11", 80_000),
            new Bin("LURAY", "B-12", 120_000));

    /** One week of harvest, deterministic and a little varied. */
    public static final List<Haul> LOADS = List.of(
            new Haul("MERRICK", 78420, 26180, "18.2", "56.5", "1.0"),
            new Haul("MERRICK", 79100, 26180, "17.4", "56.1", "1.4"),
            new Haul("HOLLIS", 77980, 26240, "15.8", "55.2", "2.6"),
            new Haul("VOSS", 78650, 26180, "14.6", "57.0", "0.8"),
            new Haul("BROCKMAN", 76400, 25900, "16.9", "53.4", "4.2"),
            new Haul("KREHBIEL", 79220, 26180, "13.9", "56.8", "1.1"),
            new Haul("MERRICK", 78040, 26180, "15.1", "55.9", "1.9"),
            new Haul("STROH", 77500, 26000, "19.4", "54.0", "3.1"),
            new Haul("HOLLIS", 78880, 26240, "14.2", "56.4", "0.9"));

    private static final Logger LOG = HouseLog.getLogger("seeding");

    /**
     * One seeded site.
     *
     * @param code the site's code
     * @param name what the office calls it
     * @param licence its state warehouse licence
     * @param capacity the bushels it is licensed for
     */
    public record Site(String code, String name, String licence, int capacity) {}

    /**
     * One seeded producer.
     *
     * @param code the producer's code
     * @param name the name cheques are written to
     */
    public record Producer(String code, String name) {}

    /**
     * One seeded bin.
     *
     * @param locationCode the site it stands at
     * @param binId its number in the yard
     * @param capacity its capacity in bushels
     */
    public record Bin(String locationCode, String binId, int capacity) {}

    /**
     * One seeded load of corn.
     *
     * @param party who hauled it
     * @param gross the loaded weight in pounds
     * @param tare the empty weight in pounds
     * @param moisture the moisture reading
     * @param testWeight the test weight reading
     * @param damage the total damage reading
     */
    public record Haul(String party, int gross, int tare, String moisture, String testWeight, String damage) {}

    private Seeding() {}

    /** Whether there is a producer file here already. Seeding twice is refused. */
    public static boolean isSeeded(UnitOfWork uow) {
        Long parties = uow.session()
                .createQuery("select count(p) from Party p", Long.class)
                .getSingleResult();
        return parties > 0;
    }

    /**
     * Fill an empty database with one week of a working elevator.
     *
     * @throws ConflictError when there is a producer file here already
     */
    public static Seeded seed(UnitOfWork uow, RuleBook rules) {
        return seed(uow, rules, false);
    }

    /**
     * Fill a database with one week of a working elevator, the loads graded by {@code rules}.
     *
     * @param force seed even when there is a producer file here already
     * @throws ConflictError when there is a producer file here already and {@code force} is false
     */
    public static Seeded seed(UnitOfWork uow, RuleBook rules, boolean force) {
        if (isSeeded(uow) && !force) {
            throw new ConflictError("there is already a producer file here; seed a fresh database");
        }

        for (Site site : SITES) {
            Location row = new Location();
            row.setCode(site.code());
            row.setName(site.name());
            row.setStateCode("KS");
            row.setWarehouseLicence(site.licence());
            row.setLicensedCapacity(site.capacity());
            uow.session().persist(row);
        }

        for (String code : Commodities.codes()) {
            Commodity one = Commodities.get(code);
            CommodityRow row = new CommodityRow();
            row.setCode(one.code());
            row.setName(one.name());
            row.setStandardTestWeight(one.standardTestWeight());
            row.setBaseMoisture(one.baseMoisture());
            row.setPriceUnit(one.priceUnit().value());
            uow.session().persist(row);
        }

        for (Producer producer : PRODUCERS) {
            Parties.openAccount(uow, producer.code(), producer.name(), LocalDate.of(2014, 3, 1));
        }

        for (Bin bin : BINS) {
            BinRow row = new BinRow();
            row.setBinId(bin.binId());
            row.setLocationCode(bin.locationCode());
            row.setCapacityBushels(BigDecimal.valueOf(bin.capacity()));
            row.setAerated(true);
            uow.session().persist(row);
        }
        uow.flush();

        Parties.fileAgreement(
                uow,
                "HOME-PLACE",
                List.of(
                        new SplitShare("BROCKMAN", new BigDecimal("25"), SplitRole.LANDLORD),
                        new SplitShare("MERRICK", new BigDecimal("75"), SplitRole.TENANT)),
                LocalDate.of(2024, 3, 1));

        int written = 0;
        for (int index = 0; index < LOADS.size(); index++) {
            Haul haul = LOADS.get(index);
            Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
            sample.put(Factor.TEST_WEIGHT, new BigDecimal(haul.testWeight()));
            sample.put(Factor.MOISTURE, new BigDecimal(haul.moisture()));
            sample.put(Factor.BCFM, new BigDecimal("1.2"));
            sample.put(Factor.DAMAGE_TOTAL, new BigDecimal(haul.damage()));
            ScaleTicket ticket = ScaleTicket.builder()
                    .ticketNumber("A-" + (10441 + index))
                    .scaleDate(SEASON_OPENS.plusDays(index / 2))
                    .locationCode(index % 3 != 0 ? "HOME" : "LURAY")
                    .commodityCode("CORN")
                    .grossPounds(BigDecimal.valueOf(haul.gross()))
                    .tarePounds(BigDecimal.valueOf(haul.tare()))
                    .disposition(Disposition.CASH_SALE)
                    .splits(List.of(new SplitShare(haul.party(), new BigDecimal("100"))))
                    .truckId("T-" + (10 + index))
                    .farm(haul.party().equals("MERRICK") ? "Home place" : "")
                    .sample(sample)
                    .build();
            ScaleHouse.recordLoad(uow, ticket, haul.party(), rules, "seed");
            written++;
        }

        uow.flush();
        Seeded result = new Seeded(PRODUCERS.size(), SITES.size(), BINS.size(), written);
        HouseLog.event(
                LOG,
                "house.seeded",
                field("parties", result.parties()),
                field("locations", result.locations()),
                field("bins", result.bins()),
                field("tickets", result.tickets()));
        return result;
    }

    /** One line for the console. */
    public static String describe(Seeded result) {
        return result.locations() + " sites, " + result.parties() + " producers, " + result.bins() + " bins, "
                + result.tickets() + " tickets";
    }
}
