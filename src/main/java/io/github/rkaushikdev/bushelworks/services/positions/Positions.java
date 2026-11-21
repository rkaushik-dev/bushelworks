package io.github.rkaushikdev.bushelworks.services.positions;

import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractStatus;
import io.github.rkaushikdev.bushelworks.domain.position.PositionLine;
import io.github.rkaushikdev.bushelworks.domain.position.PositionReport;
import io.github.rkaushikdev.bushelworks.persistence.Columns;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ContractRow;
import io.github.rkaushikdev.bushelworks.persistence.models.StorageLotRow;
import io.github.rkaushikdev.bushelworks.services.shipping.Shipping;
import io.github.rkaushikdev.bushelworks.services.warehousing.Warehousing;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Building the daily position out of what is on file.
 *
 * <p>The position report is the one number the manager looks at before he answers the telephone to
 * the terminal, and it is assembled from five places that each know part of it:
 *
 * <ul>
 *   <li>tickets received, less what has been shipped out, is what is physically on hand
 *   <li>open storage lots are what the house owes producers in grain
 *   <li>open deferred price lots are what the house owns but has not paid for
 *   <li>open purchase contracts are bushels bought and not yet delivered
 *   <li>open sale contracts are bushels sold and not yet loaded
 * </ul>
 *
 * <p>Getting the sign of the third one wrong is the classic mistake, and the reason {@link
 * PositionLine} splits {@code owned} from {@code netLong}.
 */
public final class Positions {

    /** The location code of a line rolled up across every site. */
    public static final String EVERY_SITE = "*";

    private Positions() {}

    /** Everything of a commodity across the scale at every site through a day, in bushels, less voids. */
    public static BigDecimal receivedBushels(UnitOfWork uow, String commodityCode, LocalDate through) {
        return receivedBushels(uow, commodityCode, through, null);
    }

    /**
     * Everything of a commodity across the scale through a day, net pounds converted at its standard
     * test weight, less voided tickets.
     *
     * @param locationCode one site, or null or empty for every site
     */
    public static BigDecimal receivedBushels(
            UnitOfWork uow, String commodityCode, LocalDate through, String locationCode) {
        boolean bySite = locationCode != null && !locationCode.isEmpty();
        TypedQuery<BigDecimal> query = uow.session()
                .createQuery(
                        "select sum(t.grossPounds - t.tarePounds) from ScaleTicketRow t"
                                + " where t.commodityCode = :commodity and t.scaleDate <= :through"
                                + " and t.voided = false"
                                + (bySite ? " and t.locationCode = :location" : ""),
                        BigDecimal.class)
                .setParameter("commodity", commodityCode)
                .setParameter("through", through);
        if (bySite) {
            query.setParameter("location", locationCode);
        }
        BigDecimal pounds = Columns.asDecimal(query.getSingleResult());
        if (pounds.signum() == 0) {
            return Units.quantize(BigDecimal.ZERO, Units.BUSHEL_PLACES);
        }
        return Units.poundsToBushels(pounds, Commodities.get(commodityCode).standardTestWeight());
    }

    /**
     * What is still in the lots of a commodity stored by {@code through}, storage or deferred price.
     *
     * @param locationCode one site, or null for every site
     */
    private static BigDecimal openLotBushels(
            UnitOfWork uow, String commodityCode, LocalDate through, boolean deferred, String locationCode) {
        TypedQuery<StorageLotRow> query = uow.session()
                .createQuery(
                        "select l from StorageLotRow l where l.commodityCode = :commodity"
                                + " and l.storedOn <= :through and l.deferredPrice = :deferred"
                                + (locationCode == null ? "" : " and l.locationCode = :location")
                                + " order by l.id",
                        StorageLotRow.class)
                .setParameter("commodity", commodityCode)
                .setParameter("through", through)
                .setParameter("deferred", deferred);
        if (locationCode != null) {
            query.setParameter("location", locationCode);
        }
        return Units.quantize(Warehousing.openBushelsOf(query.getResultList()), Units.BUSHEL_PLACES);
    }

    /** Bushels bought forward across every site that have not arrived yet. */
    public static BigDecimal contractedBushels(UnitOfWork uow, String commodityCode, LocalDate through) {
        return contractedBushels(uow, commodityCode, through, null);
    }

    /**
     * Bushels bought forward that have not arrived yet: each open contract written by {@code
     * through} and still taking delivery on it, less what has been applied to it, floored at zero. A
     * contract delivered past its face amount does not turn into a short.
     *
     * <p>Two statements however many contracts are open, and one when none is.
     *
     * @param locationCode one site, or null for every site
     */
    public static BigDecimal contractedBushels(
            UnitOfWork uow, String commodityCode, LocalDate through, String locationCode) {
        String site = locationCode == null || locationCode.isEmpty() ? null : locationCode;
        TypedQuery<ContractRow> query = uow.session()
                .createQuery(
                        "select c from ContractRow c where c.commodityCode = :commodity and c.status = :status"
                                + " and c.writtenOn <= :through and c.windowCloses >= :through"
                                + (site == null ? "" : " and c.locationCode = :location")
                                + " order by c.id",
                        ContractRow.class)
                .setParameter("commodity", commodityCode)
                .setParameter("status", ContractStatus.OPEN.value())
                .setParameter("through", through);
        if (site != null) {
            query.setParameter("location", site);
        }
        List<ContractRow> rows = query.getResultList();
        if (rows.isEmpty()) {
            return Units.quantize(BigDecimal.ZERO, Units.BUSHEL_PLACES);
        }

        Map<Integer, BigDecimal> applied =
                appliedByContract(uow, rows.stream().map(ContractRow::getId).toList());
        BigDecimal outstanding = BigDecimal.ZERO;
        for (ContractRow row : rows) {
            BigDecimal remaining = row.getBushels().subtract(applied.getOrDefault(row.getId(), BigDecimal.ZERO));
            if (remaining.signum() > 0) {
                outstanding = outstanding.add(remaining);
            }
        }
        return Units.quantize(outstanding, Units.BUSHEL_PLACES);
    }

    /**
     * Bushels already applied and not reversed, per contract, in one statement. A contract nothing
     * is applied to is left out, and no contracts asked about is no statement at all.
     *
     * <p>This used to be one query per open contract. On a house with two hundred open contracts the
     * daily position was two hundred and six statements for a number that is one group by, and over
     * the office network share that was most of the four seconds the manager waited for his screen.
     */
    public static Map<Integer, BigDecimal> appliedByContract(UnitOfWork uow, Collection<Integer> contractIds) {
        if (contractIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, BigDecimal> applied = new LinkedHashMap<>();
        for (Object[] total : uow.session()
                .createQuery(
                        "select a.contractId, sum(a.bushels) from ApplicationRow a"
                                + " where a.contractId in :contracts and a.reversedOn is null"
                                + " group by a.contractId order by a.contractId",
                        Object[].class)
                .setParameter("contracts", contractIds)
                .getResultList()) {
            applied.put((Integer) total[0], Columns.asDecimal(total[1]));
        }
        return applied;
    }

    /** One commodity across every site on one day, with nothing in transit. */
    public static PositionLine lineFor(UnitOfWork uow, String commodityCode, LocalDate asOf) {
        return lineFor(uow, commodityCode, asOf, EVERY_SITE, BigDecimal.ZERO);
    }

    /** One commodity at one site on one day, with nothing in transit. */
    public static PositionLine lineFor(UnitOfWork uow, String commodityCode, LocalDate asOf, String locationCode) {
        return lineFor(uow, commodityCode, asOf, locationCode, BigDecimal.ZERO);
    }

    /**
     * One commodity at one site on one day, assembled whole from what is on file.
     *
     * <p>The sale side used to come in as arguments because sales lived on the terminal's paperwork.
     * They are on file now, so the caller cannot hand the line a figure that disagrees with the sale
     * contracts. {@code inTransit} is still an argument: grain on a barge between two of the house's
     * own sites has no document in this database. On hand is what was received less what was shipped,
     * never below zero.
     *
     * @param locationCode one site, or {@link #EVERY_SITE}
     */
    public static PositionLine lineFor(
            UnitOfWork uow, String commodityCode, LocalDate asOf, String locationCode, BigDecimal inTransit) {
        String site = locationCode.equals(EVERY_SITE) ? null : locationCode;
        BigDecimal received = receivedBushels(uow, commodityCode, asOf, site);
        BigDecimal shipped = Shipping.shippedBushels(uow, commodityCode, asOf, site);
        BigDecimal onHand = Units.quantize(received.subtract(shipped), Units.BUSHEL_PLACES);
        return new PositionLine(
                asOf,
                commodityCode,
                locationCode,
                onHand.signum() > 0 ? onHand : BigDecimal.ZERO,
                openLotBushels(uow, commodityCode, asOf, false, site),
                openLotBushels(uow, commodityCode, asOf, true, site),
                contractedBushels(uow, commodityCode, asOf, site),
                Shipping.soldForward(uow, commodityCode, asOf, site),
                inTransit);
    }

    /** The whole board for one day, every commodity, across every site. */
    public static PositionReport buildReport(UnitOfWork uow, LocalDate asOf) {
        return buildReport(uow, asOf, List.of(), EVERY_SITE);
    }

    /** The board for one day, these commodities, across every site. */
    public static PositionReport buildReport(UnitOfWork uow, LocalDate asOf, List<String> commodities) {
        return buildReport(uow, asOf, commodities, EVERY_SITE);
    }

    /**
     * The board for one day: a line per commodity, leaving out any with nothing on hand and nothing
     * bought forward.
     *
     * @param commodities the commodities to look at; empty for every one, in code order
     * @param locationCode one site, or {@link #EVERY_SITE}
     */
    public static PositionReport buildReport(
            UnitOfWork uow, LocalDate asOf, List<String> commodities, String locationCode) {
        PositionReport report = new PositionReport(asOf);
        for (String code : commodities.isEmpty() ? Commodities.codes() : commodities) {
            PositionLine line = lineFor(uow, code, asOf, locationCode);
            if (line.onHand().signum() == 0 && line.purchasedForward().signum() == 0) {
                continue;
            }
            report.add(line);
        }
        return report;
    }

    /**
     * The gap between the position and what the broker says is on, per commodity on the report. A
     * commodity with no hedge on shows its whole net long.
     */
    public static Map<String, BigDecimal> unhedged(PositionReport report, Map<String, BigDecimal> hedged) {
        Map<String, BigDecimal> gaps = new LinkedHashMap<>();
        for (String code : report.commodities()) {
            PositionLine line = report.byCommodity(code);
            gaps.put(
                    code,
                    Units.quantize(
                            line.netLong().subtract(hedged.getOrDefault(code, BigDecimal.ZERO)), Units.BUSHEL_PLACES));
        }
        return gaps;
    }
}
