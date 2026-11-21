package io.github.rkaushikdev.bushelworks;

import io.github.rkaushikdev.bushelworks.domain.assessments.WithheldLine;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementBuilder;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.services.charges.Advance;
import io.github.rkaushikdev.bushelworks.services.charges.DiscountBasis;
import io.github.rkaushikdev.bushelworks.services.charges.StorageCharge;
import io.github.rkaushikdev.bushelworks.services.intake.GradedLoad;
import io.github.rkaushikdev.bushelworks.services.intake.Intake;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A finished statement for one ticket, with no database anywhere near it.
 *
 * <p>Several tests want a statement to render, total or store, rather than the settlement rule that
 * produced it. {@link #quickStatement} gives them one with the defaults below, and {@link
 * #statement} lets a test change any of them first:
 *
 * <pre>
 *     QuickStatements.statement(rules, ticket).number("S-1").advances(advance).build()
 * </pre>
 */
public final class QuickStatements {

    private QuickStatements() {}

    /** Statement {@code S-2024-0417} to MERRICK on 20 October 2024 at $4.35, discounts on dry bushels. */
    public static Settlement quickStatement(RuleBook rules, ScaleTicket ticket) {
        return statement(rules, ticket).build();
    }

    /** The same statement, waiting for whatever a test wants to change. */
    public static Request statement(RuleBook rules, ScaleTicket ticket) {
        return new Request(rules, ticket);
    }

    /** A statement for one ticket named the way the settlement run names its arguments. */
    public static Request settlementFor(
            String number,
            ScaleTicket ticket,
            BigDecimal unitPrice,
            RuleBook rules,
            String partyCode,
            LocalDate settledOn) {
        return statement(rules, ticket)
                .number(number)
                .unitPrice(unitPrice)
                .partyCode(partyCode)
                .settledOn(settledOn);
    }

    /** The choices a quick statement is made with. */
    public static final class Request {

        private final RuleBook rules;
        private final ScaleTicket ticket;
        private String number = "S-2024-0417";
        private BigDecimal unitPrice = new BigDecimal("4.3500");
        private LocalDate settledOn = LocalDate.of(2024, 10, 20);
        private String partyCode = "MERRICK";
        private DiscountBasis discountBasis = DiscountBasis.DRY;
        private List<Advance> advances = List.of();
        private List<StorageCharge> storage = List.of();
        private Optional<String> contractNumber = Optional.empty();

        private Request(RuleBook rules, ScaleTicket ticket) {
            this.rules = rules;
            this.ticket = ticket;
        }

        public Request number(String value) {
            this.number = value;
            return this;
        }

        public Request unitPrice(BigDecimal value) {
            this.unitPrice = value;
            return this;
        }

        public Request settledOn(LocalDate value) {
            this.settledOn = value;
            return this;
        }

        public Request partyCode(String value) {
            this.partyCode = value;
            return this;
        }

        public Request discountBasis(DiscountBasis value) {
            this.discountBasis = value;
            return this;
        }

        public Request advances(Advance... values) {
            this.advances = List.of(values);
            return this;
        }

        public Request storage(StorageCharge... values) {
            this.storage = List.of(values);
            return this;
        }

        /** The contract the statement settles against; an empty number references none. */
        public Request contractNumber(String value) {
            this.contractNumber = Optional.of(value).filter(text -> !text.isEmpty());
            return this;
        }

        public Settlement build() {
            GradedLoad load = Intake.receive(ticket, rules);
            SettlementBuilder builder = new SettlementBuilder(number, settledOn, partyCode, load.locationCode());
            builder.referenceTicket(load.ticketNumber());
            contractNumber.ifPresent(builder::referenceContract);

            builder.addGross(
                    "GRAIN",
                    load.commodityCode() + " " + load.settledBushels() + " bu at " + unitPrice,
                    load.settledBushels(),
                    unitPrice);

            BigDecimal bushels = discountBasis == DiscountBasis.DRY ? load.dryBushels() : load.cleanBushels();
            List<Map.Entry<Factor, BigDecimal>> discounts = load.discountDetail().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Factor.BY_VALUE))
                    .toList();
            for (Map.Entry<Factor, BigDecimal> discount : discounts) {
                String factor = discount.getKey().value();
                builder.addCharge(
                        LineKind.DISCOUNT,
                        factor.toUpperCase(Locale.ROOT),
                        factor.replace('_', ' ') + " discount",
                        Money.extend(bushels, discount.getValue()),
                        Optional.of(bushels),
                        Optional.of(discount.getValue()));
            }

            BigDecimal drying = Intake.dryingCharge(load, rules, discountBasis == DiscountBasis.DRY);
            if (drying.signum() != 0) {
                builder.addCharge(
                        LineKind.DRYING,
                        "DRY",
                        "drying " + load.moisturePoints() + " points",
                        drying,
                        Optional.of(load.moisturePoints()),
                        Optional.empty());
            }

            for (StorageCharge charge : storage) {
                builder.addCharge(
                        LineKind.STORAGE,
                        "STOR-" + charge.lotId(),
                        "storage on " + charge.lotId() + " through " + charge.through(),
                        charge.dollars());
            }

            List<WithheldLine> levies = rules.assessments()
                    .withhold(
                            load.commodityCode(),
                            rules.policy().stateCode(),
                            load.settledBushels(),
                            builder.runningGross(),
                            Money.amount(builder.runningGross().subtract(builder.runningDiscounts())));
            for (WithheldLine levy : levies) {
                builder.addCharge(LineKind.ASSESSMENT, levy.code(), levy.name(), levy.dollars());
            }

            for (Advance advance : advances) {
                builder.addCharge(
                        LineKind.ADVANCE,
                        "ADV-" + advance.reference(),
                        "advance of " + advance.advancedOn(),
                        advance.dollars());
            }
            return builder.build();
        }
    }
}
