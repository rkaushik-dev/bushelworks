package io.github.rkaushikdev.bushelworks.services.scalehouse;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.domain.application.Application;
import io.github.rkaushikdev.bushelworks.domain.application.ApplicationLedger;
import io.github.rkaushikdev.bushelworks.domain.application.Applications;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.pricing.PricedQuantity;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ApplicationRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ContractRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.services.audit.Action;
import io.github.rkaushikdev.bushelworks.services.audit.Audit;
import io.github.rkaushikdev.bushelworks.services.audit.Document;
import io.github.rkaushikdev.bushelworks.services.audit.Particulars;
import io.github.rkaushikdev.bushelworks.services.intake.GradedLoad;
import io.github.rkaushikdev.bushelworks.services.intake.Intake;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/**
 * What happens between the driver pulling off the scale and the ticket printing.
 *
 * <p>Recording a load is four writes and one decision, and the decision is which contracts it lands
 * on. The order matters: the application ledger is built from what is already on file before this
 * ticket is added to it, or a load would apply against itself.
 *
 * <p>Everything here takes a unit of work and writes inside it. Nothing commits.
 */
public final class ScaleHouse {

    private static final Logger LOG = HouseLog.getLogger("scalehouse");

    private ScaleHouse() {}

    /** Write the ticket, grade it, and apply it to whatever it fills, as the system. */
    public static RecordedLoad recordLoad(UnitOfWork uow, ScaleTicket ticket, String partyCode, RuleBook rules) {
        return recordLoad(uow, ticket, partyCode, rules, Audit.SYSTEM);
    }

    /**
     * Write the ticket, grade it, and apply it to whatever it fills.
     *
     * <p>Only a contract delivery or a cash sale is applied; grain into storage lands on nothing. The
     * ticket and every contract it lands on get an audit entry from {@code actor}, and the load is
     * logged as {@code ticket.recorded}, with a {@code ticket.sample_grade} warning when the sample
     * graded below the ladder.
     *
     * @throws ConflictError when the unit of work is not open, or the site already has the ticket
     * @throws ScaleHouseError when the ticket names a contract the producer has no open delivery on
     */
    public static RecordedLoad recordLoad(
            UnitOfWork uow, ScaleTicket ticket, String partyCode, RuleBook rules, String actor) {
        if (!uow.isOpen()) {
            throw new ConflictError("recording a load needs an open unit of work");
        }

        GradedLoad load = Intake.receive(ticket, rules);
        ScaleTicketRow row = uow.tickets().add(ticket, partyCode);

        List<Application> applications = List.of();
        if (ticket.disposition() == Disposition.CONTRACT || ticket.disposition() == Disposition.CASH_SALE) {
            applications = apply(uow, ticket, load, partyCode, rules);
        }

        for (Application application : applications) {
            if (application.contractNumber().isEmpty()) {
                continue;
            }
            ContractRow contractRow =
                    uow.contracts().row(application.contractNumber().get());
            ApplicationRow applied = new ApplicationRow();
            applied.setTicketId(row.getId());
            applied.setContractId(contractRow.getId());
            applied.setBushels(application.bushels());
            applied.setAppliedOn(application.appliedOn());
            uow.session().persist(applied);
        }
        uow.flush();

        RecordedLoad recorded = new RecordedLoad(row.getId(), ticket.ticketNumber(), load, applications);
        Particulars particulars = Particulars.by(actor)
                .withPartyCode(partyCode)
                .withLocationCode(ticket.locationCode())
                .withEffectiveOn(ticket.scaleDate());
        Audit.record(
                uow,
                Action.RECORDED,
                Document.TICKET,
                ticket.ticketNumber(),
                particulars.withDetail(ticket.commodityCode() + " " + load.settledBushels() + " bu net, grade "
                        + load.grade().grade().value()));
        for (Application application : recorded.applications()) {
            if (application.contractNumber().isEmpty()) {
                continue;
            }
            Audit.record(
                    uow,
                    Action.APPLIED,
                    Document.CONTRACT,
                    application.contractNumber().get(),
                    particulars.withDetail(application.bushels() + " bu off " + ticket.ticketNumber()));
        }
        String contracts = String.join(",", recorded.contractNumbers());
        HouseLog.event(
                LOG,
                "ticket.recorded",
                field("ticket", ticket.ticketNumber()),
                field("location", ticket.locationCode()),
                field("party", partyCode),
                field("commodity", ticket.commodityCode()),
                field("net_pounds", ticket.netPounds()),
                field("dry_bushels", load.dryBushels()),
                field("grade", load.grade().grade().value()),
                field("contracts", contracts.isEmpty() ? null : contracts),
                field("open_bushels", recorded.openBushels()));
        if (load.isSampleGrade()) {
            HouseLog.warn(
                    LOG,
                    "ticket.sample_grade",
                    field("ticket", ticket.ticketNumber()),
                    field(
                            "factors",
                            load.grade().limitingFactors().stream()
                                    .map(Factor::value)
                                    .collect(Collectors.joining(","))));
        }
        return recorded;
    }

    private static List<Application> apply(
            UnitOfWork uow, ScaleTicket ticket, GradedLoad load, String partyCode, RuleBook rules) {
        List<PurchaseContract> contracts =
                uow.contracts().openFor(partyCode, ticket.commodityCode(), ticket.scaleDate());

        String named = ticket.contractNumber().orElse("");
        if (!named.isEmpty()) {
            contracts = contracts.stream()
                    .filter(contract -> contract.number().equals(named))
                    .toList();
            if (contracts.isEmpty()) {
                throw new ScaleHouseError(ticket.ticketNumber() + ": contract " + named + " is not open for "
                        + partyCode + " on " + ticket.scaleDate());
            }
        }

        ApplicationLedger ledger = new ApplicationLedger();
        for (PurchaseContract contract : contracts) {
            BigDecimal applied = uow.contracts().appliedBushels(contract.number());
            if (applied.signum() != 0) {
                ledger.record(Application.onContract(
                        "prior:" + contract.number(), contract.number(), applied, ticket.scaleDate()));
            }
        }

        List<PurchaseContract> available =
                Applications.eligible(contracts, ticket.commodityCode(), partyCode, ticket.scaleDate(), ledger);
        if (available.isEmpty()) {
            return List.of();
        }
        return Applications.applyDelivery(
                ticket.ticketNumber(),
                load.settledBushels(),
                available,
                ledger,
                ticket.scaleDate(),
                rules.policy().selectionRule(),
                rules.policy().overDelivery());
    }

    /** The blended price across whatever a load landed on, when every bushel landed on a contract. */
    public static BigDecimal priceFor(UnitOfWork uow, RecordedLoad recorded) {
        return priceFor(uow, recorded, null);
    }

    /**
     * The blended price across whatever a load landed on.
     *
     * <p>A load that half fills a forward at 4.62 and half goes to spot at 4.35 is paid at neither of
     * those, and the producer is entitled to see the weighted number rather than two statements. A
     * load that landed on nothing is paid the spot price.
     *
     * @param spotPrice the posted bid for bushels off contract, or null when there is none
     * @throws ScaleHouseError when some bushels are off contract, or nothing was applied, and there is
     *     no spot price
     */
    public static BigDecimal priceFor(UnitOfWork uow, RecordedLoad recorded, BigDecimal spotPrice) {
        List<PricedQuantity> lots = new ArrayList<>();
        for (Application application : recorded.applications()) {
            if (application.contractNumber().isEmpty()) {
                if (spotPrice == null) {
                    throw new ScaleHouseError(recorded.ticketNumber() + ": " + application.bushels()
                            + " bushels are off contract and there is no spot price");
                }
                lots.add(new PricedQuantity(application.bushels(), spotPrice));
                continue;
            }
            PurchaseContract contract =
                    uow.contracts().get(application.contractNumber().get());
            lots.add(new PricedQuantity(application.bushels(), contract.unitPrice()));
        }

        if (lots.isEmpty()) {
            if (spotPrice == null) {
                throw new ScaleHouseError(recorded.ticketNumber() + ": nothing to price");
            }
            return spotPrice;
        }
        return PricedQuantity.blend(lots);
    }

    /** Which commodities a site can settle on a day, for the touch screen, in code order. */
    public static List<String> commoditiesHandled(RuleBook rules, LocalDate day, String locationCode) {
        return Commodities.codes().stream()
                .filter(code -> rules.covers(code, day, locationCode))
                .toList();
    }
}
