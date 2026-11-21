package io.github.rkaushikdev.bushelworks.services.payments;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.services.audit.Action;
import io.github.rkaushikdev.bushelworks.services.audit.Audit;
import io.github.rkaushikdev.bushelworks.services.audit.Document;
import io.github.rkaushikdev.bushelworks.services.audit.Particulars;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;

/**
 * The cheque run.
 *
 * <p>Settlements pile up unpaid until somebody runs the cheques, usually on a Thursday. The run
 * gathers every unpaid statement for a producer, adds them, takes one cheque number off the stock and
 * marks all of them paid against it.
 *
 * <p>Two rules that look like details and are not:
 *
 * <ul>
 *   <li>a producer with a zero or negative net does not get a cheque. A negative net is a debit
 *       balance, which the office carries forward and collects against the next load rather than
 *       sending a bill.
 *   <li>a cheque number, once allocated, is used or voided. It is never quietly skipped, because the
 *       bank reconciliation counts them.
 * </ul>
 *
 * <p>Everything here takes a unit of work and writes inside it. Nothing commits.
 */
public final class Payments {

    private static final Logger LOG = HouseLog.getLogger("payments");

    private Payments() {}

    /** Every unpaid statement, grouped by who it belongs to, oldest statement first within each. */
    public static Map<String, List<SettlementRow>> unpaidByParty(UnitOfWork uow) {
        Map<String, List<SettlementRow>> grouped = new LinkedHashMap<>();
        for (SettlementRow row : uow.settlements().unpaid()) {
            grouped.computeIfAbsent(row.getPartyCode(), code -> new ArrayList<>())
                    .add(row);
        }
        return grouped;
    }

    /**
     * Step a cheque number {@code offset} places, keeping its width.
     *
     * <p>Cheque stock is numbered with leading zeroes and the bank cares: {@code 010441} plus one is
     * {@code 010442} and not {@code 10442}.
     *
     * @throws PaymentError when {@code start} is not all digits, or the step would need more digits
     *     than the stock prints
     */
    public static String nextChequeNumber(String start, int offset) {
        if (start.isEmpty() || !start.chars().allMatch(Character::isDigit)) {
            throw new PaymentError("'" + start + "' is not a cheque number");
        }
        int width = start.length();
        String text = new BigInteger(start).add(BigInteger.valueOf(offset)).toString();
        if (text.length() > width) {
            throw new PaymentError("cheque numbers ran past the width of the stock at " + text);
        }
        return zeroFilled(text, width);
    }

    /** {@code text} widened to {@code width} with zeroes after any sign. */
    private static String zeroFilled(String text, int width) {
        String sign = text.startsWith("-") ? "-" : "";
        String digits = text.substring(sign.length());
        return sign + "0".repeat(Math.max(width - text.length(), 0)) + digits;
    }

    /** Pay everything unpaid, one cheque per producer, as the system. */
    public static ChequeRun runCheques(UnitOfWork uow, LocalDate paidOn, String firstCheque) {
        return runCheques(uow, paidOn, firstCheque, ChequeOptions.defaults());
    }

    /**
     * Pay everything unpaid, one cheque per producer, in producer order.
     *
     * <p>Cheque numbers are taken off the stock from {@code firstCheque}, one per cheque written, so a
     * producer held under the minimum uses no number. Each cheque marks its statements paid and gets a
     * {@code paid} audit entry; the run is logged as {@code cheque.run}.
     *
     * @throws PaymentError when a producer named has nothing unpaid, or nobody has anything to be paid
     */
    public static ChequeRun runCheques(UnitOfWork uow, LocalDate paidOn, String firstCheque, ChequeOptions options) {
        Map<String, List<SettlementRow>> grouped = new TreeMap<>(unpaidByParty(uow));
        if (options.partyCodes().isPresent()) {
            Set<String> wanted = Set.copyOf(options.partyCodes().get());
            Set<String> missing = new TreeSet<>(wanted);
            missing.removeAll(grouped.keySet());
            if (!missing.isEmpty()) {
                throw new PaymentError("nothing unpaid for " + String.join(", ", missing));
            }
            grouped.keySet().retainAll(wanted);
        }
        if (grouped.isEmpty()) {
            throw new PaymentError("nothing is waiting to be paid");
        }

        List<Cheque> cheques = new ArrayList<>();
        List<Balance> held = new ArrayList<>();
        int issued = 0;

        for (Map.Entry<String, List<SettlementRow>> producer : grouped.entrySet()) {
            String partyCode = producer.getKey();
            List<SettlementRow> rows = producer.getValue();
            BigDecimal owed = owedOn(rows);
            if (owed.compareTo(options.minimum()) < 0) {
                held.add(new Balance(partyCode, owed));
                continue;
            }

            String number = nextChequeNumber(firstCheque, issued);
            issued++;
            for (SettlementRow row : rows) {
                uow.settlements().markPaid(row.getNumber(), number, paidOn);
            }
            Cheque cheque = new Cheque(
                    number,
                    partyCode,
                    paidOn,
                    Money.amount(owed),
                    rows.stream().map(SettlementRow::getNumber).toList());
            cheques.add(cheque);
            Audit.record(
                    uow,
                    Action.PAID,
                    Document.PAYMENT,
                    number,
                    Particulars.by(options.actor())
                            .withPartyCode(partyCode)
                            .withAmount(cheque.dollars())
                            .withEffectiveOn(paidOn)
                            .withDetail("settles " + String.join(", ", cheque.settlementNumbers())));
        }

        ChequeRun run = new ChequeRun(paidOn, cheques, held);
        HouseLog.event(
                LOG,
                "cheque.run",
                field("paid_on", paidOn),
                field("cheques", run.count()),
                field("total", run.total()),
                field("held", run.held().size()));
        return run;
    }

    /** Void a cheque and put its settlements back in the unpaid pile, as the system. */
    public static List<SettlementRow> voidCheque(UnitOfWork uow, String chequeNumber, String reason) {
        return voidCheque(uow, chequeNumber, reason, Audit.SYSTEM);
    }

    /**
     * Void a cheque and put its settlements back in the unpaid pile. The cheque number gets a {@code
     * voided} audit entry from {@code actor}, carrying the reason.
     *
     * @return the statements the cheque paid
     * @throws PaymentError when no statement was paid by that cheque, or there is no reason
     */
    public static List<SettlementRow> voidCheque(UnitOfWork uow, String chequeNumber, String reason, String actor) {
        List<SettlementRow> rows = paidBy(uow, chequeNumber);
        if (rows.isEmpty()) {
            throw new PaymentError("no cheque " + chequeNumber + " on file");
        }
        if (reason == null || reason.isEmpty()) {
            throw new PaymentError(chequeNumber + ": a void needs a reason");
        }

        for (SettlementRow row : rows) {
            row.setPaidOn(null);
            row.setChequeNumber(null);
        }
        uow.flush();
        Audit.record(
                uow,
                Action.VOIDED,
                Document.PAYMENT,
                chequeNumber,
                Particulars.by(actor).withDetail(reason));
        return rows;
    }

    /**
     * Producers whose unpaid statements add to nothing or less, in producer order.
     *
     * <p>Carried forward rather than billed. The office rings them; the software does not send a
     * demand.
     */
    public static List<Balance> debitBalances(UnitOfWork uow) {
        List<Balance> out = new ArrayList<>();
        new TreeMap<>(unpaidByParty(uow)).forEach((partyCode, rows) -> {
            BigDecimal owed = owedOn(rows);
            if (owed.signum() <= 0) {
                out.add(new Balance(partyCode, owed));
            }
        });
        return out;
    }

    /**
     * Compare the bank's cleared list, cheque number to amount, against what the house wrote.
     *
     * <p>Gives the cheque numbers that disagree, in number order, which is what the bookkeeper works
     * from: one the house never wrote, and one the bank cleared for a different amount. The second is
     * the one that matters, and it is the one this finds.
     */
    public static List<String> reconcile(UnitOfWork uow, Map<String, BigDecimal> cleared) {
        List<String> problems = new ArrayList<>();
        new TreeMap<>(cleared).forEach((number, clearedAmount) -> {
            List<SettlementRow> rows = paidBy(uow, number);
            if (rows.isEmpty() || Money.amount(owedOn(rows)).compareTo(Money.amount(clearedAmount)) != 0) {
                problems.add(number);
            }
        });
        return problems;
    }

    private static BigDecimal owedOn(List<SettlementRow> rows) {
        return Money.total(rows.stream().map(SettlementRow::getNet).toList());
    }

    private static List<SettlementRow> paidBy(UnitOfWork uow, String chequeNumber) {
        return uow.session()
                .createQuery("select s from SettlementRow s where s.chequeNumber = :cheque", SettlementRow.class)
                .setParameter("cheque", chequeNumber)
                .getResultList();
    }
}
