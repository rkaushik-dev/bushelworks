package io.github.rkaushikdev.bushelworks.services.settlement;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.assessments.WithheldLine;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.pricing.PricedQuantity;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementBuilder;
import io.github.rkaushikdev.bushelworks.persistence.Mappers;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ApplicationRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ContractRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.services.audit.Action;
import io.github.rkaushikdev.bushelworks.services.audit.Audit;
import io.github.rkaushikdev.bushelworks.services.audit.Document;
import io.github.rkaushikdev.bushelworks.services.audit.Particulars;
import io.github.rkaushikdev.bushelworks.services.charges.Advance;
import io.github.rkaushikdev.bushelworks.services.charges.Deduction;
import io.github.rkaushikdev.bushelworks.services.charges.DiscountBasis;
import io.github.rkaushikdev.bushelworks.services.charges.StorageCharge;
import io.github.rkaushikdev.bushelworks.services.intake.GradedLoad;
import io.github.rkaushikdev.bushelworks.services.intake.Intake;
import io.github.rkaushikdev.bushelworks.services.numbering.Numbering;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/**
 * Settling stored tickets, one producer at a time.
 *
 * <p>The office settles by producer rather than by ticket. A man who hauled nine loads on Tuesday
 * gets one statement and one cheque, and the statement shows the loads. That is why a run takes the
 * producer's waiting tickets and produces one settlement.
 *
 * <p>Each load is still graded and priced on its own, because each has its own moisture, its own
 * discounts and possibly its own contract price. Only the document is shared.
 */
public final class SettlementRun {

    /** The series settlement numbers are allocated from. */
    public static final String SETTLEMENT_SERIES = "S";

    private static final Logger LOG = HouseLog.getLogger("settlement");

    private SettlementRun() {}

    /**
     * Every live application for a run of tickets, grouped by ticket, with the contracts they landed
     * on.
     *
     * <p>Two statements however many tickets there are: one for the applications and one for their
     * contracts. Pricing used to query per ticket and then fetch each contract on its own, and a nine
     * load settlement was nineteen round trips for what is one join; on the office box over a network
     * share that was most of the wall clock. No tickets asked about is no statement at all.
     */
    public static Map<Integer, List<LiveApplication>> applicationsFor(UnitOfWork uow, Collection<Integer> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Map.of();
        }
        List<ApplicationRow> rows = uow.session()
                .createQuery(
                        "select a from ApplicationRow a where a.ticketId in :tickets and a.reversedOn is null"
                                + " order by a.id",
                        ApplicationRow.class)
                .setParameter("tickets", ticketIds)
                .getResultList();
        if (rows.isEmpty()) {
            return Map.of();
        }
        Set<Integer> contractIds =
                rows.stream().map(ApplicationRow::getContractId).collect(Collectors.toCollection(TreeSet::new));
        Map<Integer, ContractRow> contracts = uow
                .session()
                .createQuery("select c from ContractRow c where c.id in :contracts", ContractRow.class)
                .setParameter("contracts", contractIds)
                .getResultList()
                .stream()
                .collect(Collectors.toMap(ContractRow::getId, Function.identity()));
        Map<Integer, List<LiveApplication>> grouped = new LinkedHashMap<>();
        for (ApplicationRow row : rows) {
            grouped.computeIfAbsent(row.getTicketId(), ticket -> new ArrayList<>())
                    .add(new LiveApplication(row, contracts.get(row.getContractId())));
        }
        grouped.replaceAll((ticket, applications) -> List.copyOf(applications));
        return grouped;
    }

    /**
     * Blend the contract prices a ticket landed on with spot for the rest.
     *
     * <p>A ticket with no live applications is a spot purchase and takes the posted bid. One that half
     * filled a forward is paid the weighted average of the contract price on the contracted bushels
     * and spot on the rest, so the producer sees one number rather than two statements for one truck.
     * The bushels that fell off the end of a contract carry no application, and they are the ones
     * priced at spot here rather than at the contract price.
     */
    public static BigDecimal priceFrom(List<LiveApplication> applications, BigDecimal spot, BigDecimal settledBushels) {
        if (applications.isEmpty()) {
            return spot;
        }
        List<PricedQuantity> lots = new ArrayList<>();
        BigDecimal contracted = BigDecimal.ZERO;
        for (LiveApplication application : applications) {
            BigDecimal price = Mappers.contractFromRow(application.contract()).unitPrice();
            lots.add(new PricedQuantity(application.bushels(), price));
            contracted = contracted.add(application.bushels());
        }
        BigDecimal remainder = settledBushels.subtract(contracted);
        if (remainder.signum() > 0) {
            lots.add(new PricedQuantity(remainder, spot));
        }
        return PricedQuantity.blend(lots);
    }

    /**
     * The price for one stored ticket, blended across whatever it landed on, read on its own.
     *
     * <p>{@link #settleProducer} prices a whole run from one {@link #applicationsFor} instead; this is
     * for a single ticket looked at by itself.
     */
    public static BigDecimal priceOf(UnitOfWork uow, ScaleTicketRow row, BigDecimal spot, BigDecimal settledBushels) {
        List<LiveApplication> applications =
                applicationsFor(uow, List.of(row.getId())).getOrDefault(row.getId(), List.of());
        return priceFrom(applications, spot, settledBushels);
    }

    /** Settle every unsettled ticket a producer has at one site, at spot where nothing is contracted. */
    public static Settlement settleProducer(
            UnitOfWork uow,
            String partyCode,
            String locationCode,
            RuleBook rules,
            LocalDate settledOn,
            BigDecimal spotPrice) {
        return settleProducer(uow, partyCode, locationCode, rules, settledOn, spotPrice, RunOptions.defaults());
    }

    /**
     * Settle the unsettled tickets a producer has at one site onto one statement.
     *
     * <p>The statement takes the next settlement number for the year, its tickets are marked settled,
     * it gets an audit entry carrying its net, and it is logged as {@code settlement.written}.
     *
     * @throws ConflictError when the unit of work is not open
     * @throws NothingToSettle when the producer has nothing waiting at the site, or a ticket asked for
     *     is not waiting there
     */
    public static Settlement settleProducer(
            UnitOfWork uow,
            String partyCode,
            String locationCode,
            RuleBook rules,
            LocalDate settledOn,
            BigDecimal spotPrice,
            RunOptions options) {
        if (!uow.isOpen()) {
            throw new ConflictError("a settlement run needs an open unit of work");
        }

        List<ScaleTicketRow> rows = uow.tickets().awaitingSettlement(locationCode).stream()
                .filter(row -> row.getPartyCode().equals(partyCode))
                .toList();
        if (options.ticketNumbers().isPresent()) {
            Set<String> wanted = Set.copyOf(options.ticketNumbers().get());
            rows = rows.stream()
                    .filter(row -> wanted.contains(row.getTicketNumber()))
                    .toList();
            Set<String> missing = new TreeSet<>(wanted);
            rows.forEach(row -> missing.remove(row.getTicketNumber()));
            if (!missing.isEmpty()) {
                throw new NothingToSettle(partyCode + ": " + String.join(", ", missing)
                        + " is not waiting to be settled at " + locationCode);
            }
        }
        if (rows.isEmpty()) {
            throw new NothingToSettle(partyCode + " has nothing waiting to be settled at " + locationCode);
        }

        Map<Integer, List<LiveApplication>> byTicket =
                applicationsFor(uow, rows.stream().map(ScaleTicketRow::getId).toList());
        List<SettledTicket> settled = new ArrayList<>();
        for (ScaleTicketRow row : rows) {
            GradedLoad load = Intake.receive(Mappers.ticketFromRow(row), rules);
            settled.add(new SettledTicket(
                    row.getId(),
                    row.getTicketNumber(),
                    load,
                    priceFrom(byTicket.getOrDefault(row.getId(), List.of()), spotPrice, load.settledBushels())));
        }

        String number = new Numbering(uow.session())
                .allocate(SETTLEMENT_SERIES, settledOn)
                .toString();
        SettlementBuilder builder = new SettlementBuilder(number, settledOn, partyCode, locationCode);

        for (SettledTicket item : settled) {
            builder.referenceTicket(item.ticketNumber());
            builder.addGross(
                    "GRAIN-" + item.ticketNumber(),
                    item.load().commodityCode() + " " + item.load().settledBushels() + " bu at " + item.unitPrice(),
                    item.load().settledBushels(),
                    item.unitPrice());
        }

        addDiscounts(builder, settled, options.discountBasis());
        addDrying(builder, settled, rules, options.discountBasis());

        for (StorageCharge charge : options.storage()) {
            builder.addCharge(
                    LineKind.STORAGE,
                    "STOR-" + charge.lotId(),
                    "storage on " + charge.lotId() + " through " + charge.through(),
                    charge.dollars());
        }

        addAssessments(builder, settled, rules);

        for (Deduction deduction : options.deductions()) {
            builder.addCharge(LineKind.DEDUCTION, deduction.code(), deduction.description(), deduction.dollars());
        }

        for (Advance advance : options.advances()) {
            builder.addCharge(
                    LineKind.ADVANCE,
                    "ADV-" + advance.reference(),
                    "advance of " + advance.advancedOn(),
                    advance.dollars());
        }

        Settlement statement = builder.build();

        uow.settlements()
                .add(statement, settled.stream().map(SettledTicket::ticketId).toList());
        for (ScaleTicketRow row : rows) {
            row.setSettled(true);
        }
        uow.flush();
        Audit.record(
                uow,
                Action.SETTLED,
                Document.SETTLEMENT,
                statement.number(),
                Particulars.by(options.actor())
                        .withPartyCode(partyCode)
                        .withLocationCode(locationCode)
                        .withAmount(statement.net())
                        .withEffectiveOn(settledOn)
                        .withDetail(settled.size() + " tickets: " + String.join(", ", statement.ticketNumbers())));
        HouseLog.event(
                LOG,
                "settlement.written",
                field("number", statement.number()),
                field("party", partyCode),
                field("location", locationCode),
                field("tickets", settled.size()),
                field("gross", statement.gross()),
                field("deductions", statement.deductions()),
                field("net", statement.net()));
        return statement;
    }

    private static void addDiscounts(SettlementBuilder builder, List<SettledTicket> settled, DiscountBasis basis) {
        for (SettledTicket item : settled) {
            BigDecimal bushels = basis == DiscountBasis.DRY
                    ? item.load().dryBushels()
                    : item.load().cleanBushels();
            List<Map.Entry<Factor, BigDecimal>> discounts = item.load().discountDetail().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(Factor::value)))
                    .toList();
            for (Map.Entry<Factor, BigDecimal> discount : discounts) {
                String factor = discount.getKey().value();
                BigDecimal rate = discount.getValue();
                builder.addCharge(
                        LineKind.DISCOUNT,
                        factor.toUpperCase(Locale.ROOT) + "-" + item.ticketNumber(),
                        factor.replace('_', ' ') + " on " + item.ticketNumber(),
                        Money.extend(bushels, rate),
                        Optional.of(bushels),
                        Optional.of(rate));
            }
        }
    }

    private static void addDrying(
            SettlementBuilder builder, List<SettledTicket> settled, RuleBook rules, DiscountBasis basis) {
        for (SettledTicket item : settled) {
            BigDecimal charge = Intake.dryingCharge(item.load(), rules, basis == DiscountBasis.DRY);
            if (charge.signum() != 0) {
                builder.addCharge(
                        LineKind.DRYING,
                        "DRY-" + item.ticketNumber(),
                        "drying " + item.load().moisturePoints() + " points on " + item.ticketNumber(),
                        charge,
                        Optional.of(item.load().moisturePoints()),
                        Optional.empty());
            }
        }
    }

    /**
     * Levy per commodity, on the whole statement rather than per ticket.
     *
     * <p>A percentage checkoff rounded per ticket and one rounded per statement differ by a few cents
     * on a nine load day, and the commission reconciles against the statement.
     */
    private static void addAssessments(SettlementBuilder builder, List<SettledTicket> settled, RuleBook rules) {
        Map<String, BigDecimal> byCommodity = new TreeMap<>();
        for (SettledTicket item : settled) {
            byCommodity.merge(item.load().commodityCode(), item.load().settledBushels(), BigDecimal::add);
        }

        BigDecimal gross = builder.runningGross();
        BigDecimal net = Money.amount(gross.subtract(builder.runningDiscounts()));

        for (Map.Entry<String, BigDecimal> commodity : byCommodity.entrySet()) {
            for (WithheldLine line : rules.assessments()
                    .withhold(commodity.getKey(), rules.policy().stateCode(), commodity.getValue(), gross, net)) {
                builder.addCharge(LineKind.ASSESSMENT, line.code(), line.name(), line.dollars());
            }
        }
    }
}
