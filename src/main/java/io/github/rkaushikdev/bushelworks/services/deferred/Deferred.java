package io.github.rkaushikdev.bushelworks.services.deferred;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.StorageLotRow;
import io.github.rkaushikdev.bushelworks.services.warehousing.LotNotFound;
import io.github.rkaushikdev.bushelworks.services.warehousing.Warehousing;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deferred price grain, which the trade calls price later.
 *
 * <p>Title passes at delivery and the price is agreed some time afterwards. The producer is an
 * unsecured creditor of the house for the whole time, which is why several states cap how long the
 * arrangement can run and why the house charges a service fee for it.
 *
 * <p>Three things make this different from storage and all three catch people out:
 *
 * <ul>
 *   <li>the house owns the bushels, so they are on its position and have to be hedged
 *   <li>the producer is owed money rather than grain, so a withdrawal is a pricing rather than a load
 *       out
 *   <li>the service fee accrues on a different clock from storage, usually with no free period at all
 * </ul>
 *
 * <p>A deferred delivery is a storage lot with the deferred flag set, so everything here reads the
 * lots {@link Warehousing} keeps. Nothing commits.
 */
public final class Deferred {

    private Deferred() {}

    /** Take delivery of grain the producer has not priced, on no particular ticket. */
    public static StorageLotRow deliver(
            UnitOfWork uow,
            String lotId,
            String partyCode,
            String commodityCode,
            String locationCode,
            BigDecimal bushels,
            LocalDate deliveredOn) {
        return deliver(uow, lotId, partyCode, commodityCode, locationCode, bushels, deliveredOn, null);
    }

    /**
     * Take delivery of grain the producer has not priced.
     *
     * <p>Written as a lot with the deferred flag set rather than as a table of its own. The bushels
     * behave the same way going out; what differs is which side of the position they sit on and what
     * they are charged.
     *
     * @param ticketId the key of the ticket it came in on, or null
     */
    public static StorageLotRow deliver(
            UnitOfWork uow,
            String lotId,
            String partyCode,
            String commodityCode,
            String locationCode,
            BigDecimal bushels,
            LocalDate deliveredOn,
            Integer ticketId) {
        return Warehousing.store(
                uow, lotId, partyCode, commodityCode, locationCode, bushels, deliveredOn, ticketId, true);
    }

    /** Every open deferred lot, oldest first. */
    public static List<StorageLotRow> unpricedLots(UnitOfWork uow) {
        return unpricedLots(uow, null, null, null);
    }

    /**
     * The open deferred lots, oldest first.
     *
     * @param partyCode only this producer's; null or empty for everyone's
     * @param commodityCode only this commodity; null or empty for every one
     * @param locationCode only this site; null or empty for every site
     */
    public static List<StorageLotRow> unpricedLots(
            UnitOfWork uow, String partyCode, String commodityCode, String locationCode) {
        return Warehousing.openLots(uow, partyCode, commodityCode, locationCode).stream()
                .filter(StorageLotRow::isDeferredPrice)
                .toList();
    }

    /** Fix a price on some or all of a deferred lot, under the standard terms. */
    public static PricedLot priceLot(
            UnitOfWork uow, String lotId, BigDecimal bushels, BigDecimal unitPrice, LocalDate pricedOn) {
        return priceLot(uow, lotId, bushels, unitPrice, pricedOn, ServiceTerms.DEFAULT);
    }

    /**
     * Fix a price on some or all of a deferred lot.
     *
     * <p>Gives the gross value and the service fee, which the settlement builder puts on separate
     * lines. The fee is charged on the bushels being priced rather than on the whole lot, because a
     * producer who prices half in March should not pay March's fee on the half he leaves until July.
     * Nothing is written.
     *
     * @throws LotNotFound when the lot is not on file
     * @throws DeferredPriceError when the lot is storage, nothing is asked for, more than is open is
     *     asked for, or the price is not positive
     * @throws ConflictError when the day is before the lot was delivered
     */
    public static PricedLot priceLot(
            UnitOfWork uow,
            String lotId,
            BigDecimal bushels,
            BigDecimal unitPrice,
            LocalDate pricedOn,
            ServiceTerms terms) {
        StorageLotRow row = Warehousing.lot(uow, lotId);
        if (!row.isDeferredPrice()) {
            throw new DeferredPriceError(lotId + " is storage, not deferred price");
        }
        BigDecimal wanted = Units.quantize(bushels, Units.BUSHEL_PLACES);
        if (wanted.signum() <= 0) {
            throw new DeferredPriceError(lotId + ": nothing to price");
        }
        if (wanted.compareTo(row.getOpenBushels()) > 0) {
            throw new DeferredPriceError(
                    lotId + " has " + row.getOpenBushels() + " bushels open, asked to price " + wanted);
        }
        if (unitPrice.signum() <= 0) {
            throw new DeferredPriceError(lotId + ": a price has to be positive");
        }
        if (pricedOn.isBefore(row.getStoredOn())) {
            throw new ConflictError(lotId + ": cannot price before it was delivered");
        }
        return new PricedLot(Money.extend(wanted, unitPrice), terms.charge(wanted, row.getStoredOn(), pricedOn));
    }

    /** What one producer has unpriced in one commodity, under the standard terms. */
    public static Optional<DeferredPosition> position(
            UnitOfWork uow, String partyCode, String commodityCode, LocalDate asOf) {
        return position(uow, partyCode, commodityCode, asOf, ServiceTerms.DEFAULT);
    }

    /**
     * What one producer has unpriced in one commodity: the bushels, the fee on all of it to {@code
     * asOf}, and which lots have run past the term.
     *
     * @return nothing when the producer has no open deferred lot of it
     */
    public static Optional<DeferredPosition> position(
            UnitOfWork uow, String partyCode, String commodityCode, LocalDate asOf, ServiceTerms terms) {
        List<StorageLotRow> rows = unpricedLots(uow, partyCode, commodityCode, null);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal bushels = Units.quantize(Warehousing.openBushelsOf(rows), Units.BUSHEL_PLACES);
        BigDecimal owed = Money.amount(rows.stream()
                .map(row -> terms.charge(row.getOpenBushels(), row.getStoredOn(), asOf))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        List<String> overdue = rows.stream()
                .filter(row -> terms.isOverdue(row.getStoredOn(), asOf))
                .map(StorageLotRow::getLotId)
                .toList();
        LocalDate oldest = rows.stream()
                .map(StorageLotRow::getStoredOn)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        return Optional.of(new DeferredPosition(partyCode, commodityCode, bushels, oldest, owed, overdue));
    }

    /**
     * Bushels of a commodity the house owns because nobody has priced them yet, counting deliveries up
     * to and including {@code asOf}.
     *
     * <p>This is the number that has to be hedged and the one most often missed. The grain is in the
     * bin, it is not the producer's any more, and if the house counts it as storage it is long by that
     * much without knowing.
     */
    public static BigDecimal houseExposure(UnitOfWork uow, String commodityCode, LocalDate asOf) {
        List<StorageLotRow> delivered = unpricedLots(uow, null, commodityCode, null).stream()
                .filter(row -> !row.getStoredOn().isAfter(asOf))
                .toList();
        return Units.quantize(Warehousing.openBushelsOf(delivered), Units.BUSHEL_PLACES);
    }

    /** Every deferred lot past the term the standard terms allow. */
    public static List<StorageLotRow> overdue(UnitOfWork uow, LocalDate asOf) {
        return overdue(uow, asOf, ServiceTerms.DEFAULT);
    }

    /** Every deferred lot past the term the state allows. */
    public static List<StorageLotRow> overdue(UnitOfWork uow, LocalDate asOf, ServiceTerms terms) {
        return unpricedLots(uow).stream()
                .filter(row -> terms.isOverdue(row.getStoredOn(), asOf))
                .toList();
    }

    /** Every deferred bushel still in the house, across producers, commodities and sites. */
    public static BigDecimal totalUnpriced(UnitOfWork uow) {
        BigDecimal open = uow
                .session()
                .createQuery(
                        "select l from StorageLotRow l where l.deferredPrice = true and l.closedOn is null",
                        StorageLotRow.class)
                .getResultList()
                .stream()
                .map(row -> row.getBushels().subtract(row.getWithdrawn()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Units.quantize(open, Units.BUSHEL_PLACES);
    }
}
