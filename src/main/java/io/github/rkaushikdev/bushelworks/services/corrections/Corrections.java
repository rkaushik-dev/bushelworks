package io.github.rkaushikdev.bushelworks.services.corrections;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.LookupFailure;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractStatus;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementBuilder;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementLine;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ApplicationRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ContractRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SampleRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.persistence.models.TicketSplitRow;
import io.github.rkaushikdev.bushelworks.persistence.repositories.ContractNotFound;
import io.github.rkaushikdev.bushelworks.persistence.repositories.SettlementNotFound;
import io.github.rkaushikdev.bushelworks.persistence.repositories.TicketNotFound;
import io.github.rkaushikdev.bushelworks.services.audit.Action;
import io.github.rkaushikdev.bushelworks.services.audit.Audit;
import io.github.rkaushikdev.bushelworks.services.audit.Document;
import io.github.rkaushikdev.bushelworks.services.audit.Particulars;
import io.github.rkaushikdev.bushelworks.services.numbering.Numbering;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;

/**
 * Putting right what was written down wrong.
 *
 * <p>Nothing here deletes anything. A correction is a second document that says what the first one
 * got wrong, and both stay on file. That is not bookkeeping fussiness: the state inspector reads the
 * pair, and a system that can make a ticket disappear cannot prove anything about the ones that are
 * left.
 *
 * <p>Three kinds of correction, in the order they are usually needed:
 *
 * <ul>
 *   <li><b>unapply</b> takes bushels back off a contract, because the driver named the wrong one. The
 *       ticket does not move.
 *   <li><b>void and reissue</b> replaces a ticket whose weights were wrong. The void keeps the first
 *       weights and the reissue carries a new number.
 *   <li><b>reverse a settlement</b> writes a settlement with the opposite sign and releases its
 *       tickets. It never edits the statement it reverses.
 * </ul>
 */
public final class Corrections {

    /** The series reversal numbers are allocated from. */
    public static final String REVERSAL_SERIES = "SR";

    private static final Logger LOG = HouseLog.getLogger("corrections");

    private Corrections() {}

    /** Take a ticket back off a contract without touching the ticket, as the system. */
    public static Unapplied unapply(
            UnitOfWork uow,
            String locationCode,
            String ticketNumber,
            String contractNumber,
            LocalDate reversedOn,
            String reason) {
        return unapply(uow, locationCode, ticketNumber, contractNumber, reversedOn, reason, Audit.SYSTEM);
    }

    /**
     * Take a ticket back off a contract without touching the ticket.
     *
     * <p>The application is marked reversed rather than removed, a filled contract opens again, and
     * the contract gets an audit entry with the reason.
     *
     * @throws CorrectionError when no reason is given
     * @throws TicketNotFound when the site has no such ticket
     * @throws ConflictError when the ticket has been settled
     * @throws ContractNotFound when there is no such contract
     * @throws LookupFailure when the ticket is not applied to the contract
     */
    public static Unapplied unapply(
            UnitOfWork uow,
            String locationCode,
            String ticketNumber,
            String contractNumber,
            LocalDate reversedOn,
            String reason,
            String actor) {
        if (reason.isEmpty()) {
            throw new CorrectionError(ticketNumber + ": an unapply needs a reason");
        }

        ScaleTicketRow ticket = uow.tickets().row(locationCode, ticketNumber);
        if (ticket.isSettled()) {
            throw new ConflictError(ticketNumber + " has been settled and cannot be unapplied");
        }
        ContractRow contract = uow.contracts().row(contractNumber);
        ApplicationRow application = uow
                .session()
                .createQuery(
                        "select a from ApplicationRow a where a.ticketId = :ticket and a.contractId = :contract"
                                + " and a.reversedOn is null",
                        ApplicationRow.class)
                .setParameter("ticket", ticket.getId())
                .setParameter("contract", contract.getId())
                .getResultList()
                .stream()
                .findFirst()
                .orElseThrow(() -> new LookupFailure(ticketNumber + " is not applied to " + contractNumber));

        application.setReversedOn(reversedOn);
        if (contract.getStatus().equals(ContractStatus.FILLED.value())) {
            contract.setStatus(ContractStatus.OPEN.value());
        }
        uow.flush();

        Audit.record(
                uow,
                Action.REVERSED,
                Document.CONTRACT,
                contractNumber,
                Particulars.by(actor)
                        .withPartyCode(ticket.getPartyCode())
                        .withLocationCode(locationCode)
                        .withEffectiveOn(reversedOn)
                        .withDetail(ticketNumber + " unapplied: " + reason));
        BigDecimal bushels = application.getBushels();
        HouseLog.event(
                LOG,
                "application.reversed",
                field("ticket", ticketNumber),
                field("contract", contractNumber),
                field("bushels", bushels));
        return new Unapplied(ticketNumber, contractNumber, bushels, reversedOn);
    }

    /** Void a ticket and write its replacement with corrected weights, as the system. */
    public static ScaleTicketRow voidAndReissue(
            UnitOfWork uow,
            String locationCode,
            String ticketNumber,
            String replacementNumber,
            BigDecimal grossPounds,
            BigDecimal tarePounds,
            String reason) {
        return voidAndReissue(
                uow, locationCode, ticketNumber, replacementNumber, grossPounds, tarePounds, reason, Audit.SYSTEM);
    }

    /**
     * Void a ticket and write its replacement with corrected weights.
     *
     * <p>The replacement carries everything else the voided ticket said, its sample and its split
     * included, and a remark saying what it replaces and why. The void gets an audit entry and a
     * {@code ticket.reissued} warning in the log.
     *
     * @throws CorrectionError when no reason is given, or the tare is above the gross
     * @throws TicketNotFound when the site has no such ticket
     * @throws ConflictError when the ticket has been settled or is void already, or the replacement
     *     number is already written at the site
     */
    public static ScaleTicketRow voidAndReissue(
            UnitOfWork uow,
            String locationCode,
            String ticketNumber,
            String replacementNumber,
            BigDecimal grossPounds,
            BigDecimal tarePounds,
            String reason,
            String actor) {
        if (reason.isEmpty()) {
            throw new CorrectionError(ticketNumber + ": a reissue needs a reason");
        }
        if (grossPounds.compareTo(tarePounds) < 0) {
            throw new CorrectionError(replacementNumber + ": tare " + tarePounds + " exceeds gross " + grossPounds);
        }

        ScaleTicketRow voided = uow.tickets().row(locationCode, ticketNumber);
        if (voided.isSettled()) {
            throw new ConflictError(ticketNumber + " has been settled and cannot be reissued");
        }
        if (uow.tickets().exists(locationCode, replacementNumber)) {
            throw new ConflictError(replacementNumber + " is already written at " + locationCode);
        }

        ScaleTicketRow replacement = new ScaleTicketRow();
        replacement.setTicketNumber(replacementNumber);
        replacement.setLocationCode(voided.getLocationCode());
        replacement.setScaleDate(voided.getScaleDate());
        replacement.setCommodityCode(voided.getCommodityCode());
        replacement.setPartyCode(voided.getPartyCode());
        replacement.setGrossPounds(grossPounds);
        replacement.setTarePounds(tarePounds);
        replacement.setDockagePercent(voided.getDockagePercent());
        replacement.setDisposition(voided.getDisposition());
        replacement.setContractNumber(voided.getContractNumber().orElse(null));
        replacement.setTruckId(voided.getTruckId().orElse(null));
        replacement.setDriver(voided.getDriver().orElse(null));
        replacement.setFarm(voided.getFarm().orElse(null));
        replacement.setFieldName(voided.getFieldName().orElse(null));
        replacement.setRemarks("reissue of " + ticketNumber + ": " + reason);
        replacement.setSamples(
                voided.getSamples().stream().map(Corrections::copyReading).toList());
        replacement.setSplits(
                voided.getSplits().stream().map(Corrections::copyShare).toList());
        uow.session().persist(replacement);
        uow.tickets().voidTicket(locationCode, ticketNumber, "reissued as " + replacementNumber);
        uow.flush();

        Audit.record(
                uow,
                Action.VOIDED,
                Document.TICKET,
                ticketNumber,
                Particulars.by(actor)
                        .withPartyCode(voided.getPartyCode())
                        .withLocationCode(locationCode)
                        .withEffectiveOn(voided.getScaleDate())
                        .withDetail("reissued as " + replacementNumber + ": " + reason));
        HouseLog.warn(
                LOG,
                "ticket.reissued",
                field("ticket", ticketNumber),
                field("replacement", replacementNumber),
                field("reason", reason));
        return replacement;
    }

    /** Write the opposite of a settlement and free its tickets, as the system. */
    public static Settlement reverseSettlement(UnitOfWork uow, String number, LocalDate reversedOn, String reason) {
        return reverseSettlement(uow, number, reversedOn, reason, Audit.SYSTEM);
    }

    /**
     * Write the opposite of a settlement and free its tickets.
     *
     * <p>Every gross line comes back as a deduction and every deduction as gross, so the reversal's
     * net is the statement's with the sign turned over. The reversal takes the next number in the
     * {@value #REVERSAL_SERIES} series, the statement is marked voided rather than changed, and its
     * tickets wait to be settled again.
     *
     * @throws CorrectionError when no reason is given
     * @throws SettlementNotFound when there is no such statement
     * @throws ConflictError when it was reversed already, or has been paid and has to be voided at the
     *     bank first
     */
    public static Settlement reverseSettlement(
            UnitOfWork uow, String number, LocalDate reversedOn, String reason, String actor) {
        if (reason.isEmpty()) {
            throw new CorrectionError(number + ": a reversal needs a reason");
        }

        SettlementRow reversedRow = uow.settlements().row(number);
        if (reversedRow.isVoided()) {
            throw new ConflictError(number + " has already been reversed");
        }
        if (reversedRow.getPaidOn().isPresent()) {
            throw new ConflictError(
                    number + " was paid on " + reversedRow.getPaidOn().get() + " by cheque "
                            + reversedRow.getChequeNumber().orElse("") + " and has to be voided at the bank first");
        }

        Settlement reversed = uow.settlements().get(number);
        String reversalNumber = new Numbering(uow.session())
                .allocate(REVERSAL_SERIES, reversedOn)
                .toString();
        SettlementBuilder builder =
                new SettlementBuilder(reversalNumber, reversedOn, reversed.partyId(), reversed.locationCode());
        for (SettlementLine line : reversed.lines()) {
            LineKind kind = line.kind() == LineKind.GROSS ? LineKind.DEDUCTION : LineKind.GROSS;
            builder.addCharge(
                    kind,
                    line.code(),
                    "reversal of " + number + ": " + line.description(),
                    line.dollars(),
                    line.quantity(),
                    line.rate());
        }
        for (String ticketNumber : reversed.ticketNumbers()) {
            builder.referenceTicket(ticketNumber);
        }

        Settlement reversal = builder.build();
        uow.settlements().add(reversal);
        reversedRow.setVoided(true);

        List<Integer> ticketIds = uow.session()
                .createQuery(
                        "select t.ticketId from SettlementTicketRow t where t.settlementId = :settlement order by t.id",
                        Integer.class)
                .setParameter("settlement", reversedRow.getId())
                .getResultList();
        for (Integer ticketId : ticketIds) {
            ScaleTicketRow ticket = uow.session().find(ScaleTicketRow.class, ticketId);
            if (ticket != null) {
                ticket.setSettled(false);
            }
        }
        uow.flush();

        Audit.record(
                uow,
                Action.REVERSED,
                Document.SETTLEMENT,
                number,
                Particulars.by(actor)
                        .withPartyCode(reversed.partyId())
                        .withAmount(reversal.net())
                        .withEffectiveOn(reversedOn)
                        .withDetail(reversalNumber + ": " + reason));
        HouseLog.event(
                LOG,
                "settlement.reversed",
                field("original", number),
                field("reversal", reversalNumber),
                field("net", reversal.net()));
        return reversal;
    }

    /** Every reversal written against one producer, newest first. */
    public static List<SettlementRow> reversalsFor(UnitOfWork uow, String partyCode) {
        return uow.session()
                .createQuery(
                        "select s from SettlementRow s where s.partyCode = :party and s.number like :series"
                                + " order by s.settledOn desc, s.number desc",
                        SettlementRow.class)
                .setParameter("party", partyCode)
                .setParameter("series", REVERSAL_SERIES + "-%")
                .getResultList();
    }

    private static SampleRow copyReading(SampleRow reading) {
        SampleRow row = new SampleRow();
        row.setFactor(reading.getFactor());
        row.setValue(reading.getValue());
        row.setInstrument(reading.getInstrument().orElse(null));
        return row;
    }

    private static TicketSplitRow copyShare(TicketSplitRow share) {
        TicketSplitRow row = new TicketSplitRow();
        row.setPartyCode(share.getPartyCode());
        row.setPercent(share.getPercent());
        row.setRole(share.getRole());
        return row;
    }
}
