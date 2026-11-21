package io.github.rkaushikdev.bushelworks.services.warehousing;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.LookupFailure;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.storage.StorageRate;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.StorageLotRow;
import io.github.rkaushikdev.bushelworks.persistence.models.WarehouseReceiptRow;
import io.github.rkaushikdev.bushelworks.persistence.models.WithdrawalRow;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Putting grain into storage, taking it out, and billing for the time between.
 *
 * <p>The accrual run is the piece with teeth. It walks every open lot, works out what is owed to the
 * day it is asked about, and records how far it has billed. Run it twice for the same day and the
 * second run charges nothing, because the office does run it twice: once on the last of the month and
 * again when somebody notices the first run went out before the last load was keyed.
 *
 * <p>Everything here takes a unit of work and writes inside it. Nothing commits.
 */
public final class Warehousing {

    private static final Logger LOG = HouseLog.getLogger("warehousing");

    private Warehousing() {}

    /** Every open lot with bushels still in it, oldest first. */
    public static List<StorageLotRow> openLots(UnitOfWork uow) {
        return openLots(uow, null, null, null);
    }

    /** One producer's open lots with bushels still in them, oldest first. */
    public static List<StorageLotRow> openLots(UnitOfWork uow, String partyCode) {
        return openLots(uow, partyCode, null, null);
    }

    /**
     * The open lots with bushels still in them, by the day they were stored and then the order they
     * were written.
     *
     * @param partyCode only this producer's; null or empty for everyone's
     * @param commodityCode only this commodity; null or empty for every one
     * @param locationCode only this site; null or empty for every site
     */
    public static List<StorageLotRow> openLots(
            UnitOfWork uow, String partyCode, String commodityCode, String locationCode) {
        StringBuilder jpql = new StringBuilder("select l from StorageLotRow l where l.closedOn is null");
        if (given(partyCode)) {
            jpql.append(" and l.partyCode = :party");
        }
        if (given(commodityCode)) {
            jpql.append(" and l.commodityCode = :commodity");
        }
        if (given(locationCode)) {
            jpql.append(" and l.locationCode = :location");
        }
        jpql.append(" order by l.storedOn, l.id");
        TypedQuery<StorageLotRow> query = uow.session().createQuery(jpql.toString(), StorageLotRow.class);
        if (given(partyCode)) {
            query.setParameter("party", partyCode);
        }
        if (given(commodityCode)) {
            query.setParameter("commodity", commodityCode);
        }
        if (given(locationCode)) {
            query.setParameter("location", locationCode);
        }
        return query.getResultList().stream()
                .filter(row -> row.getOpenBushels().signum() > 0)
                .toList();
    }

    /**
     * The lot filed under {@code lotId}, open or closed.
     *
     * @throws LotNotFound when there is none
     */
    public static StorageLotRow lot(UnitOfWork uow, String lotId) {
        return find(uow, lotId).orElseThrow(() -> new LotNotFound("no storage lot " + lotId));
    }

    /** Open a storage lot that came in on no particular ticket. */
    public static StorageLotRow store(
            UnitOfWork uow,
            String lotId,
            String partyCode,
            String commodityCode,
            String locationCode,
            BigDecimal bushels,
            LocalDate storedOn) {
        return store(uow, lotId, partyCode, commodityCode, locationCode, bushels, storedOn, null, false);
    }

    /**
     * Open a lot, its bushels rounded to bushel places.
     *
     * @param ticketId the key of the ticket it came in on, or null
     * @param deferredPrice whether the grain is the house's, waiting on a price, rather than stored
     * @throws StorageServiceError when the lot holds no bushels
     * @throws ConflictError when the lot is on file already
     */
    public static StorageLotRow store(
            UnitOfWork uow,
            String lotId,
            String partyCode,
            String commodityCode,
            String locationCode,
            BigDecimal bushels,
            LocalDate storedOn,
            Integer ticketId,
            boolean deferredPrice) {
        if (bushels.signum() <= 0) {
            throw new StorageServiceError(lotId + ": a lot needs bushels");
        }
        if (find(uow, lotId).isPresent()) {
            throw new ConflictError("storage lot " + lotId + " already exists");
        }
        StorageLotRow row = new StorageLotRow();
        row.setLotId(lotId);
        row.setPartyCode(partyCode);
        row.setCommodityCode(commodityCode);
        row.setLocationCode(locationCode);
        row.setBushels(Units.quantize(bushels, Units.BUSHEL_PLACES));
        row.setStoredOn(storedOn);
        row.setTicketId(ticketId);
        row.setDeferredPrice(deferredPrice);
        uow.session().persist(row);
        uow.flush();
        return row;
    }

    /** Take settled bushels out of a producer's lots at any site, oldest lot first. */
    public static List<WithdrawalRow> withdraw(
            UnitOfWork uow, String partyCode, String commodityCode, BigDecimal bushels, LocalDate withdrawnOn) {
        return withdraw(uow, partyCode, commodityCode, bushels, withdrawnOn, WithdrawalOptions.defaults());
    }

    /**
     * Take bushels out of a producer's lots, oldest lot first.
     *
     * <p>First in first out, which is what the licence assumes. Drawing the newest lot first would
     * leave the oldest bushels accruing storage after they have physically gone, and the inspector
     * calls that what it is. A lot the withdrawal empties is closed on the withdrawal day.
     *
     * @return one withdrawal per lot drawn on, in the order they were drawn
     * @throws StorageServiceError when nothing is asked for, or more than the producer has on hand
     */
    public static List<WithdrawalRow> withdraw(
            UnitOfWork uow,
            String partyCode,
            String commodityCode,
            BigDecimal bushels,
            LocalDate withdrawnOn,
            WithdrawalOptions options) {
        BigDecimal wanted = Units.quantize(bushels, Units.BUSHEL_PLACES);
        if (wanted.signum() <= 0) {
            throw new StorageServiceError("nothing to withdraw");
        }

        List<StorageLotRow> lots =
                openLots(uow, partyCode, commodityCode, options.locationCode().orElse(null));
        BigDecimal available = openBushelsOf(lots);
        if (wanted.compareTo(available) > 0) {
            throw new StorageServiceError(
                    partyCode + " has " + available + " bushels of " + commodityCode + " on hand, asked for " + wanted);
        }

        List<WithdrawalRow> written = new ArrayList<>();
        for (StorageLotRow row : lots) {
            if (wanted.signum() <= 0) {
                break;
            }
            BigDecimal take = Decimals.min(row.getOpenBushels(), wanted);
            if (take.signum() <= 0) {
                continue;
            }
            WithdrawalRow withdrawal = new WithdrawalRow();
            withdrawal.setBushels(take);
            withdrawal.setWithdrawnOn(withdrawnOn);
            withdrawal.setReason(options.reason());
            withdrawal.setSettlementNumber(options.settlementNumber().orElse(null));
            // Added through the lot rather than written with a foreign key, so the lot's open
            // balance reflects it straight away and the lot closes below.
            row.addWithdrawal(withdrawal);
            written.add(withdrawal);
            wanted = Units.quantize(wanted.subtract(take), Units.BUSHEL_PLACES);
        }
        uow.flush();

        for (StorageLotRow row : lots) {
            if (row.getOpenBushels().signum() <= 0 && row.getClosedOn().isEmpty()) {
                row.setClosedOn(withdrawnOn);
            }
        }
        uow.flush();
        return List.copyOf(written);
    }

    /** Charge storage on every open lot at every site up to {@code through}, and record it. */
    public static AccrualRun accrue(UnitOfWork uow, RuleBook rules, LocalDate through) {
        return accrue(uow, rules, through, null, true);
    }

    /** Charge storage on the open lots at one site up to {@code through}, and record it. */
    public static AccrualRun accrue(UnitOfWork uow, RuleBook rules, LocalDate through, String locationCode) {
        return accrue(uow, rules, through, locationCode, true);
    }

    /**
     * Charge storage on every open lot up to {@code through}.
     *
     * <p>Idempotent by the day each lot is billed through. A lot already billed to that day accrues
     * nothing on a second pass, and a lot billed past it is left alone rather than credited, because a
     * credit needs somebody to approve it. A lot never billed is charged from receipt, even when the
     * charge is nothing yet; one billed before is charged the balance for the days since, and left
     * off the run when that comes to nothing. A commodity with no storage rate filed is not charged.
     * The pass is logged as {@code storage.accrued}.
     *
     * @param locationCode only lots at this site; null or empty for every site
     * @param record whether to move each lot's billed-through day and add to what it has accrued;
     *     false works the charges out and writes nothing
     */
    public static AccrualRun accrue(
            UnitOfWork uow, RuleBook rules, LocalDate through, String locationCode, boolean record) {
        List<AccrualLine> lines = new ArrayList<>();
        for (StorageLotRow row : openLots(uow, null, null, locationCode)) {
            Optional<StorageRate> rate =
                    rules.storageFor(row.getCommodityCode(), row.getStoredOn(), row.getLocationCode());
            if (rate.isEmpty()) {
                continue;
            }
            Optional<LocalDate> billed = row.getBilledThrough();
            if (billed.isPresent() && !billed.get().isBefore(through)) {
                continue;
            }
            BigDecimal dollars = rate.get().chargeBetween(row.getOpenBushels(), row.getStoredOn(), billed, through);
            if (dollars.signum() <= 0 && billed.isPresent()) {
                continue;
            }
            lines.add(new AccrualLine(
                    row.getLotId(),
                    row.getPartyCode(),
                    row.getCommodityCode(),
                    row.getOpenBushels(),
                    billed,
                    through,
                    dollars));
            if (record) {
                row.setBilledThrough(through);
                row.setAccruedToDate(Money.amount(row.getAccruedToDate().add(dollars)));
            }
        }
        if (record) {
            uow.flush();
        }
        AccrualRun run = new AccrualRun(through, lines);
        HouseLog.event(
                LOG,
                "storage.accrued",
                field("through", through),
                field("location", locationCode),
                field("lots", run.lines().size()),
                field("total", run.total()),
                field("recorded", record));
        return run;
    }

    /** Write a negotiable receipt against an open lot, held by nobody but the producer. */
    public static WarehouseReceiptRow issueReceipt(
            UnitOfWork uow, String receiptNumber, String lotId, String grade, LocalDate issuedOn) {
        return issueReceipt(uow, receiptNumber, lotId, grade, issuedOn, null);
    }

    /**
     * Write a negotiable receipt for everything still in a lot.
     *
     * @param lienholder whoever holds a lien on the grain, or null
     * @throws LotNotFound when the lot is not on file
     * @throws StorageServiceError when the lot is empty
     * @throws ConflictError when the lot already carries a live receipt
     */
    public static WarehouseReceiptRow issueReceipt(
            UnitOfWork uow, String receiptNumber, String lotId, String grade, LocalDate issuedOn, String lienholder) {
        StorageLotRow row = lot(uow, lotId);
        if (row.getOpenBushels().signum() <= 0) {
            throw new StorageServiceError(lotId + " is empty and cannot carry a receipt");
        }
        Optional<WarehouseReceiptRow> existing = uow
                .session()
                .createQuery(
                        "select r from WarehouseReceiptRow r where r.lotRowId = :lot and r.cancelledOn is null",
                        WarehouseReceiptRow.class)
                .setParameter("lot", row.getId())
                .getResultList()
                .stream()
                .findFirst();
        if (existing.isPresent()) {
            throw new ConflictError(
                    lotId + " already carries live receipt " + existing.get().getReceiptNumber());
        }
        WarehouseReceiptRow receipt = new WarehouseReceiptRow();
        receipt.setReceiptNumber(receiptNumber);
        receipt.setLotRowId(row.getId());
        receipt.setPartyCode(row.getPartyCode());
        receipt.setCommodityCode(row.getCommodityCode());
        receipt.setLocationCode(row.getLocationCode());
        receipt.setBushels(row.getOpenBushels());
        receipt.setGrade(grade);
        receipt.setIssuedOn(issuedOn);
        receipt.setLienholder(lienholder);
        uow.session().persist(receipt);
        uow.flush();
        return receipt;
    }

    /**
     * Cancel a live receipt, which frees its lot to carry another.
     *
     * @throws LookupFailure when the receipt is not on file
     * @throws ConflictError when it was cancelled already
     */
    public static WarehouseReceiptRow cancelReceipt(UnitOfWork uow, String receiptNumber, LocalDate cancelledOn) {
        WarehouseReceiptRow receipt = uow
                .session()
                .createQuery(
                        "select r from WarehouseReceiptRow r where r.receiptNumber = :number",
                        WarehouseReceiptRow.class)
                .setParameter("number", receiptNumber)
                .getResultList()
                .stream()
                .findFirst()
                .orElseThrow(() -> new LookupFailure("no warehouse receipt " + receiptNumber));
        if (!receipt.isLive()) {
            throw new ConflictError("receipt " + receiptNumber + " was cancelled on "
                    + receipt.getCancelledOn().map(LocalDate::toString).orElse("None"));
        }
        receipt.setCancelledOn(cancelledOn);
        uow.flush();
        return receipt;
    }

    /** Total bushels of a commodity the house is holding for producers, at every site. */
    public static BigDecimal onHand(UnitOfWork uow, String commodityCode) {
        return onHand(uow, commodityCode, null);
    }

    /**
     * Total bushels of a commodity the house is holding for producers.
     *
     * @param locationCode only this site; null or empty for every site
     */
    public static BigDecimal onHand(UnitOfWork uow, String commodityCode, String locationCode) {
        return Units.quantize(openBushelsOf(openLots(uow, null, commodityCode, locationCode)), Units.BUSHEL_PLACES);
    }

    /** What is still in a run of lots, added up. */
    public static BigDecimal openBushelsOf(List<StorageLotRow> lots) {
        return lots.stream().map(StorageLotRow::getOpenBushels).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Optional<StorageLotRow> find(UnitOfWork uow, String lotId) {
        return uow
                .session()
                .createQuery("select l from StorageLotRow l where l.lotId = :lot", StorageLotRow.class)
                .setParameter("lot", lotId)
                .getResultList()
                .stream()
                .findFirst();
    }

    private static boolean given(String code) {
        return code != null && !code.isEmpty();
    }
}
