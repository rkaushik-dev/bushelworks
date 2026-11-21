package io.github.rkaushikdev.bushelworks.services.desk;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.hedging.Account;
import io.github.rkaushikdev.bushelworks.domain.hedging.HedgeError;
import io.github.rkaushikdev.bushelworks.domain.hedging.Hedging;
import io.github.rkaushikdev.bushelworks.domain.hedging.Lot;
import io.github.rkaushikdev.bushelworks.domain.position.PositionReport;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.positions.Positions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the manager does at eight in the morning.
 *
 * <p>He looks at the position, looks at what the broker says is on, and works out how many contracts
 * he is short of where he wants to be. Then he rings the broker.
 *
 * <p>The two halves of that come from different places and neither of them can be made to produce
 * the other. The position is on file here, assembled out of tickets and lots and contracts. The
 * futures account is not: fills arrive on a statement in the afternoon post and somebody keys them
 * in, so the account comes in as an argument rather than out of a table.
 *
 * <p>Nothing here decides to hedge. It says what the gap is and what closing it would cost at the
 * prices it was handed, and a person decides.
 */
public final class Desk {

    /**
     * A gap smaller than this many contracts is not worth a telephone call. One contract of corn is
     * five thousand bushels and the house carries that much slack most days.
     */
    public static final int TOLERANCE_CONTRACTS = 1;

    private Desk() {}

    /**
     * One line of the sheet.
     *
     * <p>The contracts wanted round toward zero on purpose. A house that rounds up is short more grain
     * than it has the moment a load is voided.
     */
    public static Gap gapFor(String commodityCode, BigDecimal netLong, Account account) {
        return new Gap(
                commodityCode,
                Units.quantize(netLong, Units.BUSHEL_PLACES),
                Units.quantize(account.netBushels().negate(), Units.BUSHEL_PLACES),
                Hedging.contractsFor(netLong),
                -account.netContracts());
    }

    /** Match the whole house's position against the broker, commodity by commodity. */
    public static Sheet sheetFor(PositionReport report, Map<String, Account> accounts) {
        return sheetFor(report, accounts, Positions.EVERY_SITE);
    }

    /**
     * Match the position against the broker, commodity by commodity.
     *
     * <p>A commodity with no account is not skipped. An unhedged position is the thing the manager most
     * needs to see, and leaving it off the sheet because there is no matching account is how it stays
     * unseen.
     *
     * @param locationCode the site the report is for, or {@code *}
     */
    public static Sheet sheetFor(PositionReport report, Map<String, Account> accounts, String locationCode) {
        List<Gap> gaps = new ArrayList<>();
        for (String code : report.commodities()) {
            Account account = accounts.getOrDefault(code, new Account());
            gaps.add(gapFor(code, report.byCommodity(code).netLong(), account));
        }
        return new Sheet(report.asOf(), locationCode, gaps);
    }

    /** Build the whole house's position off the books for every commodity and match it against the broker. */
    public static Sheet sheet(UnitOfWork uow, LocalDate asOf, Map<String, Account> accounts) {
        return sheet(uow, asOf, accounts, List.of(), Positions.EVERY_SITE);
    }

    /**
     * Build the position off the books and match it against the broker.
     *
     * @param commodities the commodities to look at; null or empty for every one
     * @param locationCode one site, or {@code *} for the whole house
     */
    public static Sheet sheet(
            UnitOfWork uow,
            LocalDate asOf,
            Map<String, Account> accounts,
            List<String> commodities,
            String locationCode) {
        PositionReport report =
                Positions.buildReport(uow, asOf, commodities == null ? List.of() : commodities, locationCode);
        return sheetFor(report, accounts, locationCode);
    }

    /** Cash less futures, at price places. Negative is the usual state at a country house. */
    public static BigDecimal basis(BigDecimal cashBid, BigDecimal futures) {
        return Money.price(cashBid.subtract(futures));
    }

    /**
     * Whether the market is paying to hold grain, per bushel, at price places.
     *
     * <p>Positive means the spread more than covers the storage the house would charge itself. That is
     * the number that decides whether to sell now or roll, and it is the one that gets worked out on
     * the back of an envelope and then argued about.
     *
     * @throws HedgeError when {@code months} is less than one
     */
    public static BigDecimal carry(BigDecimal near, BigDecimal deferred, int months, BigDecimal storagePerMonth) {
        if (months <= 0) {
            throw new HedgeError("a carry needs at least one month");
        }
        BigDecimal spread = deferred.subtract(near);
        return Money.price(spread.subtract(storagePerMonth.multiply(BigDecimal.valueOf(months))));
    }

    /**
     * Open gain or loss per month with something on, keyed by symbol for printing, in the order the
     * months were first filled. A month that has flattened is left off.
     *
     * @throws HedgeError when a month with something on has no settlement price
     */
    public static Map<String, BigDecimal> monthExposure(Account account, Map<FuturesMonth, BigDecimal> settlements) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map.Entry<FuturesMonth, Integer> position : account.byMonth().entrySet()) {
            FuturesMonth month = position.getKey();
            BigDecimal settle = settlements.get(month);
            if (settle == null) {
                throw new HedgeError("no settlement price for " + month.symbol());
            }
            BigDecimal move = settle.subtract(account.averagePrice(month));
            out.put(
                    month.symbol(),
                    Money.amount(move.multiply(BigDecimal.valueOf(position.getValue()))
                            .multiply(Lot.CONTRACT_BUSHELS)));
        }
        return out;
    }
}
