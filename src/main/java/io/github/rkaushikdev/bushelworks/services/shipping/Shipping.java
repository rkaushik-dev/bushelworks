package io.github.rkaushikdev.bushelworks.services.shipping;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodity;
import io.github.rkaushikdev.bushelworks.persistence.Columns;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.SaleRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ShipmentRow;
import io.github.rkaushikdev.bushelworks.services.audit.Action;
import io.github.rkaushikdev.bushelworks.services.audit.Audit;
import io.github.rkaushikdev.bushelworks.services.audit.Particulars;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Loading out against a sale.
 *
 * <p>Shipping is the only place bushels leave the house, so it is the only place the position goes
 * down. Three things happen and they happen in this order:
 *
 * <ol>
 *   <li>the load is weighed out and converted to bushels at origin
 *   <li>the sale's shipped total moves, which is what stops the next car
 *   <li>weeks later the buyer's settlement arrives with a destination weight, a grade and a cheque,
 *       and the difference between the two weights is transit shrink
 * </ol>
 *
 * <p>Step three is separate on purpose. A shipment with no destination weight is the normal state
 * for a fortnight, and treating that as incomplete would make the position wrong for the whole time.
 *
 * <p>Everything here takes a unit of work and writes inside it. Nothing commits.
 */
public final class Shipping {

    /** The document kind a sale is audited under. */
    public static final String SALE = "sale";

    private static final Logger LOG = HouseLog.getLogger("shipping");

    private static final BigDecimal NO_BUSHELS = new BigDecimal("0.0000");

    private Shipping() {}

    /**
     * The sale filed under {@code number}.
     *
     * @throws SaleNotFound when there is none
     */
    public static SaleRow sale(UnitOfWork uow, String number) {
        return uow
                .session()
                .createQuery("select s from SaleRow s where s.number = :number", SaleRow.class)
                .setParameter("number", number)
                .getResultList()
                .stream()
                .findFirst()
                .orElseThrow(() -> new SaleNotFound("no sale " + number));
    }

    /** Every open sale, the one whose window closes first first. */
    public static List<SaleRow> openSales(UnitOfWork uow) {
        return openSales(uow, null, null);
    }

    /** The open sales of one commodity, the one whose window closes first first. */
    public static List<SaleRow> openSales(UnitOfWork uow, String commodityCode) {
        return openSales(uow, commodityCode, null);
    }

    /**
     * The open sales, the one whose window closes first first, then by number.
     *
     * @param commodityCode only this commodity; null or empty for every one
     * @param on only sales whose shipping window takes this day; null for any day
     */
    public static List<SaleRow> openSales(UnitOfWork uow, String commodityCode, LocalDate on) {
        boolean byCommodity = commodityCode != null && !commodityCode.isEmpty();
        StringBuilder jpql = new StringBuilder("select s from SaleRow s where s.status = 'open'");
        if (byCommodity) {
            jpql.append(" and s.commodityCode = :commodity");
        }
        if (on != null) {
            jpql.append(" and s.windowOpens <= :on and s.windowCloses >= :on");
        }
        jpql.append(" order by s.windowCloses, s.number");
        TypedQuery<SaleRow> query = uow.session().createQuery(jpql.toString(), SaleRow.class);
        if (byCommodity) {
            query.setParameter("commodity", commodityCode);
        }
        if (on != null) {
            query.setParameter("on", on);
        }
        return query.getResultList();
    }

    /** The most a sale will take: its bushels and its tolerance on top. */
    public static BigDecimal maximumFor(SaleRow row) {
        BigDecimal ceiling = Decimals.divide(
                row.getBushels().multiply(Decimals.HUNDRED.add(row.getTolerancePercent())), Decimals.HUNDRED);
        return Units.quantize(ceiling, Units.BUSHEL_PLACES);
    }

    /** Weigh a load out and put it against a sale, as the system, with no carrier named. */
    public static Loaded loadOut(
            UnitOfWork uow, String saleNumber, String reference, BigDecimal pounds, LocalDate shippedOn) {
        return loadOut(uow, saleNumber, reference, pounds, shippedOn, null, Audit.SYSTEM);
    }

    /** Weigh a load out and put it against a sale, as the system. */
    public static Loaded loadOut(
            UnitOfWork uow,
            String saleNumber,
            String reference,
            BigDecimal pounds,
            LocalDate shippedOn,
            String carrierId) {
        return loadOut(uow, saleNumber, reference, pounds, shippedOn, carrierId, Audit.SYSTEM);
    }

    /**
     * Weigh a load out and put it against a sale.
     *
     * <p>The pounds become bushels at the commodity's standard test weight. A sale whose shipped total
     * reaches its bushels is filled. The sale gets an {@code applied} audit entry from {@code actor},
     * and the load is logged as {@code shipment.loaded}.
     *
     * @param carrierId the car or truck's own identifier, or null
     * @throws SaleNotFound when the sale is not on file
     * @throws ConflictError when the sale is not open, or already has a shipment with this reference
     * @throws ShippingError when the day is outside the window, the load weighs nothing, or it would
     *     take the sale past its tolerance
     */
    public static Loaded loadOut(
            UnitOfWork uow,
            String saleNumber,
            String reference,
            BigDecimal pounds,
            LocalDate shippedOn,
            String carrierId,
            String actor) {
        SaleRow row = sale(uow, saleNumber);
        if (!row.getStatus().equals("open")) {
            throw new ConflictError(saleNumber + " is " + row.getStatus() + ", not open");
        }
        if (shippedOn.isBefore(row.getWindowOpens()) || shippedOn.isAfter(row.getWindowCloses())) {
            throw new ShippingError(saleNumber + ": " + shippedOn + " is outside the shipping window");
        }
        if (pounds.signum() <= 0) {
            throw new ShippingError(saleNumber + ": a load has to weigh something");
        }

        Commodity commodity = Commodities.get(row.getCommodityCode());
        BigDecimal bushels = Units.poundsToBushels(pounds, commodity.standardTestWeight());
        BigDecimal already = row.getShipped();
        BigDecimal maximum = maximumFor(row);
        if (already.add(bushels).compareTo(maximum) > 0) {
            throw new ShippingError(saleNumber + ": " + bushels + " bushels would take it past " + maximum);
        }

        boolean taken = !uow.session()
                .createQuery(
                        "select s.id from ShipmentRow s where s.sale = :sale and s.reference = :reference",
                        Integer.class)
                .setParameter("sale", row)
                .setParameter("reference", reference)
                .getResultList()
                .isEmpty();
        if (taken) {
            throw new ConflictError(saleNumber + " already has a shipment " + reference);
        }

        ShipmentRow shipment = new ShipmentRow();
        shipment.setReference(reference);
        shipment.setShippedOn(shippedOn);
        shipment.setCarrierId(carrierId);
        shipment.setOriginPounds(pounds);
        shipment.setOriginBushels(bushels);
        row.addShipment(shipment);
        row.setShipped(Units.quantize(already.add(bushels), Units.BUSHEL_PLACES));
        if (row.getShipped().compareTo(row.getBushels()) >= 0) {
            row.setStatus("filled");
        }
        uow.flush();

        Audit.record(
                uow,
                Action.APPLIED,
                SALE,
                saleNumber,
                Particulars.by(actor)
                        .withLocationCode(row.getOriginCode())
                        .withEffectiveOn(shippedOn)
                        .withDetail(reference + ": " + bushels + " bu out"));
        BigDecimal remaining = row.getBushels().subtract(row.getShipped());
        Loaded loaded =
                new Loaded(saleNumber, reference, bushels, shippedOn, remaining.signum() > 0 ? remaining : NO_BUSHELS);
        HouseLog.event(
                LOG,
                "shipment.loaded",
                field("sale", saleNumber),
                field("reference", reference),
                field("bushels", bushels),
                field("remaining", loaded.remaining()));
        return loaded;
    }

    /** Post the buyer's settlement against a shipment, as the system. */
    public static ShipmentRow settleShipment(
            UnitOfWork uow,
            String saleNumber,
            String reference,
            BigDecimal destinationPounds,
            String destinationGrade,
            BigDecimal proceeds,
            LocalDate settledOn) {
        return settleShipment(
                uow, saleNumber, reference, destinationPounds, destinationGrade, proceeds, settledOn, Audit.SYSTEM);
    }

    /**
     * Post the buyer's settlement against a shipment: the destination weight, as bushels too, the
     * grade, and the cheque. The sale gets a {@code settled} audit entry from {@code actor}.
     *
     * @throws SaleNotFound when the sale, or the shipment on it, is not on file
     * @throws ConflictError when the shipment is settled already
     * @throws ShippingError when the destination weight is not positive
     */
    public static ShipmentRow settleShipment(
            UnitOfWork uow,
            String saleNumber,
            String reference,
            BigDecimal destinationPounds,
            String destinationGrade,
            BigDecimal proceeds,
            LocalDate settledOn,
            String actor) {
        SaleRow row = sale(uow, saleNumber);
        ShipmentRow shipment = shipmentOn(row, reference);
        if (shipment.isSettled()) {
            throw new ConflictError(reference + " was settled on "
                    + shipment.getSettledOn().map(LocalDate::toString).orElse(""));
        }
        if (destinationPounds.signum() <= 0) {
            throw new ShippingError(reference + ": destination weight has to be positive");
        }

        Commodity commodity = Commodities.get(row.getCommodityCode());
        shipment.setDestinationPounds(destinationPounds);
        shipment.setDestinationBushels(Units.poundsToBushels(destinationPounds, commodity.standardTestWeight()));
        shipment.setDestinationGrade(destinationGrade);
        shipment.setProceeds(Money.amount(proceeds));
        shipment.setSettledOn(settledOn);
        uow.flush();

        Audit.record(
                uow,
                Action.SETTLED,
                SALE,
                saleNumber,
                Particulars.by(actor)
                        .withAmount(Money.amount(proceeds))
                        .withEffectiveOn(settledOn)
                        .withDetail(reference + " settled at " + destinationGrade));
        return shipment;
    }

    /**
     * Mark a car the buyer refused. Its bushels come back onto the sale, and a filled sale they leave
     * short opens again.
     *
     * @throws SaleNotFound when the sale, or the shipment on it, is not on file
     * @throws ConflictError when the shipment is already marked rejected
     * @throws ShippingError when no reason is given
     */
    public static ShipmentRow rejectShipment(UnitOfWork uow, String saleNumber, String reference, String reason) {
        SaleRow row = sale(uow, saleNumber);
        ShipmentRow shipment = shipmentOn(row, reference);
        if (shipment.isRejected()) {
            throw new ConflictError(reference + " is already marked rejected");
        }
        if (reason == null || reason.isEmpty()) {
            throw new ShippingError(reference + ": a rejection needs a reason");
        }

        shipment.setRejected(true);
        shipment.setRejectionReason(reason);
        row.setShipped(Units.quantize(row.getShipped().subtract(shipment.getOriginBushels()), Units.BUSHEL_PLACES));
        if (row.getStatus().equals("filled") && row.getShipped().compareTo(row.getBushels()) < 0) {
            row.setStatus("open");
        }
        uow.flush();
        return shipment;
    }

    /** Everything of a commodity loaded out through a day, house wide, for the position report. */
    public static BigDecimal shippedBushels(UnitOfWork uow, String commodityCode, LocalDate through) {
        return shippedBushels(uow, commodityCode, through, null);
    }

    /**
     * Everything of a commodity loaded out through a day and not rejected, for the position report.
     *
     * @param originCode only sales shipping from this site; null or empty for the whole house
     */
    public static BigDecimal shippedBushels(
            UnitOfWork uow, String commodityCode, LocalDate through, String originCode) {
        boolean bySite = originCode != null && !originCode.isEmpty();
        TypedQuery<BigDecimal> query = uow.session()
                .createQuery(
                        "select sum(s.originBushels) from ShipmentRow s join s.sale sale"
                                + " where sale.commodityCode = :commodity and s.shippedOn <= :through"
                                + " and s.rejected = false"
                                + (bySite ? " and sale.originCode = :origin" : ""),
                        BigDecimal.class)
                .setParameter("commodity", commodityCode)
                .setParameter("through", through);
        if (bySite) {
            query.setParameter("origin", originCode);
        }
        return Units.quantize(Columns.asDecimal(query.getSingleResult()), Units.BUSHEL_PLACES);
    }

    /** Bushels of a commodity sold and not yet loaded, house wide. */
    public static BigDecimal soldForward(UnitOfWork uow, String commodityCode, LocalDate on) {
        return soldForward(uow, commodityCode, on, null);
    }

    /**
     * Bushels sold and not yet loaded on the open sales whose window takes {@code on}, which is what
     * makes the house short.
     *
     * @param originCode only sales shipping from this site; null or empty for the whole house
     */
    public static BigDecimal soldForward(UnitOfWork uow, String commodityCode, LocalDate on, String originCode) {
        boolean bySite = originCode != null && !originCode.isEmpty();
        BigDecimal outstanding = BigDecimal.ZERO;
        for (SaleRow row : openSales(uow, commodityCode, on)) {
            if (bySite && !row.getOriginCode().equals(originCode)) {
                continue;
            }
            BigDecimal left = row.getBushels().subtract(row.getShipped());
            if (left.signum() > 0) {
                outstanding = outstanding.add(left);
            }
        }
        return Units.quantize(outstanding, Units.BUSHEL_PLACES);
    }

    /** Bushels that went missing on the way, across every settled car on a sale. */
    public static BigDecimal transitLoss(UnitOfWork uow, String saleNumber) {
        BigDecimal losses = sale(uow, saleNumber).getShipments().stream()
                .map(ShipmentRow::getTransitShrink)
                .flatMap(Optional::stream)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Units.quantize(losses, Units.BUSHEL_PLACES);
    }

    /** Freight on everything loaded out against a sale. */
    public static BigDecimal freightOwed(UnitOfWork uow, String saleNumber) {
        SaleRow row = sale(uow, saleNumber);
        return Money.extend(row.getShipped(), row.getFreightPerBushel());
    }

    private static ShipmentRow shipmentOn(SaleRow row, String reference) {
        return row.getShipments().stream()
                .filter(item -> item.getReference().equals(reference))
                .findFirst()
                .orElseThrow(() -> new SaleNotFound(row.getNumber() + " has no shipment " + reference));
    }
}
