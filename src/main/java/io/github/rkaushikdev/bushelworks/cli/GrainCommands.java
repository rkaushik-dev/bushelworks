package io.github.rkaushikdev.bushelworks.cli;

import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.persistence.Mappers;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.reports.statement.Statements;
import io.github.rkaushikdev.bushelworks.services.intake.GradedLoad;
import io.github.rkaushikdev.bushelworks.services.intake.Intake;
import io.github.rkaushikdev.bushelworks.services.settlement.RunOptions;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** The ticket and settlement commands. */
final class GrainCommands {

    /** The commands this module adds to the group. */
    static final List<Class<?>> COMMANDS = List.of(
            Tickets.class, Grade.class, VoidTicket.class, Settle.class, Register.class, Statement.class, Unpaid.class);

    /** Who the audit trail says settled a statement from here. */
    static final String ACTOR = "cli";

    private static final DateTimeFormatter REGISTER_DAY = DateTimeFormatter.ofPattern("dd MMMM uuuu", Locale.ENGLISH);

    private GrainCommands() {}

    /** The tickets waiting to be settled. */
    @Command(name = "tickets", description = "List the tickets waiting to be settled.")
    static final class Tickets implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Option(names = "--location", paramLabel = "TEXT", description = "Limit to one elevator.")
        private String location;

        @Option(names = "--party", paramLabel = "TEXT", description = "Limit to one producer.")
        private String party;

        @Override
        public Integer call() {
            PrintWriter out = main.out();
            try (UnitOfWork uow = main.unitOfWork()) {
                List<ScaleTicketRow> rows = uow.tickets().awaitingSettlement(location);
                if (party != null && !party.isEmpty()) {
                    rows = rows.stream()
                            .filter(row -> row.getPartyCode().equals(party))
                            .toList();
                }
                for (ScaleTicketRow row : rows) {
                    out.println(row.getScaleDate() + "  " + left(row.getLocationCode(), 8)
                            + left(row.getTicketNumber(), 12) + left(row.getPartyCode(), 16)
                            + left(row.getCommodityCode(), 6)
                            + right(row.getNetPounds().toString(), 10) + " lb");
                }
                out.println(rows.size() + " tickets waiting");
            }
            return Main.OK;
        }
    }

    /** A stored ticket, graded again. */
    @Command(name = "grade", description = "Regrade a stored ticket against the tables in force on its scale date.")
    static final class Grade implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "LOCATION")
        private String location;

        @Parameters(index = "1", paramLabel = "TICKET_NUMBER")
        private String ticketNumber;

        @Override
        public Integer call() {
            GradedLoad load;
            try (UnitOfWork uow = main.unitOfWork()) {
                ScaleTicketRow row = uow.tickets().row(location, ticketNumber);
                load = Intake.receive(Mappers.ticketFromRow(row), main.rules());
            }
            PrintWriter out = main.out();
            out.println("ticket        " + load.ticketNumber());
            out.println("commodity     " + load.commodityCode());
            out.println("gross bushels " + load.grossBushels());
            out.println("clean bushels " + load.cleanBushels());
            out.println("shrink        " + load.shrinkPercent() + "% (" + load.shrinkBushels() + " bu)");
            out.println("dry bushels   " + load.dryBushels());
            out.println("grade         US " + load.grade().grade().value());
            if (!load.grade().limitingFactors().isEmpty()) {
                String held = load.grade().limitingFactors().stream()
                        .map(Factor::value)
                        .collect(Collectors.joining(", "));
                out.println("held back by  " + held);
            }
            out.println("discount      " + load.discountRate() + " per bushel");
            return Main.OK;
        }
    }

    /** A ticket voided, its weights kept. */
    @Command(name = "void", description = "Void a ticket, keeping its weights on file.")
    static final class VoidTicket implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "LOCATION")
        private String location;

        @Parameters(index = "1", paramLabel = "TICKET_NUMBER")
        private String ticketNumber;

        @Option(
                names = "--reason",
                required = true,
                paramLabel = "TEXT",
                description = "Why the ticket is being voided.")
        private String reason;

        @Override
        public Integer call() {
            String voided;
            try (UnitOfWork uow = main.unitOfWork()) {
                voided =
                        uow.tickets().voidTicket(location, ticketNumber, reason).getTicketNumber();
                uow.commit();
            }
            main.out().println(voided + " voided: " + reason);
            return Main.OK;
        }
    }

    /** Everything a producer has waiting at one elevator, on one statement. */
    @Command(name = "settle", description = "Settle everything a producer has waiting at one elevator.")
    static final class Settle implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "PARTY")
        private String party;

        @Option(
                names = "--location",
                required = true,
                paramLabel = "TEXT",
                description = "Which elevator to settle at.")
        private String location;

        @Option(
                names = "--price",
                required = true,
                paramLabel = "DECIMAL",
                converter = Arguments.Exact.class,
                description = "The spot price per bushel.")
        private BigDecimal price;

        @Option(
                names = "--on",
                paramLabel = "[" + Arguments.DAY_FORMAT + "]",
                converter = Arguments.Day.class,
                description = "Settlement date, defaulting to today.")
        private LocalDate on;

        private boolean print = true;

        @Option(names = "--print", description = "Print the statement. The default.")
        void print(boolean given) {
            print |= given;
        }

        @Option(names = "--no-print", description = "Print only its number and net.")
        void noPrint(boolean given) {
            print &= !given;
        }

        @Override
        public Integer call() {
            LocalDate settledOn = on != null ? on : LocalDate.now();
            Settlement statement;
            try (UnitOfWork uow = main.unitOfWork()) {
                statement = SettlementRun.settleProducer(
                        uow,
                        party,
                        location,
                        main.rules(),
                        settledOn,
                        price,
                        RunOptions.defaults().withActor(ACTOR));
                uow.commit();
            }
            if (print) {
                main.out().println(Statements.render(statement, main.settings().houseName()));
            } else {
                main.out().println(statement.number() + "  net " + statement.net());
            }
            return Main.OK;
        }
    }

    /** One day's statements. */
    @Command(name = "register", description = "Print the settlement register for a day.")
    static final class Register implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "ON", converter = Arguments.Day.class)
        private LocalDate on;

        @Option(names = "--location", paramLabel = "TEXT")
        private String location;

        @Override
        public Integer call() {
            List<Settlement> statements;
            try (UnitOfWork uow = main.unitOfWork()) {
                statements = uow.settlements().forDay(on, location).stream()
                        .map(Mappers::settlementFromRow)
                        .toList();
            }
            main.out().println(Statements.register(statements, REGISTER_DAY.format(on)));
            return Main.OK;
        }
    }

    /** One statement, printed again. */
    @Command(name = "statement", description = "Reprint one statement.")
    static final class Statement implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "NUMBER")
        private String number;

        @Override
        public Integer call() {
            Settlement statement;
            try (UnitOfWork uow = main.unitOfWork()) {
                statement = uow.settlements().get(number);
            }
            main.out().println(Statements.render(statement, main.settings().houseName()));
            return Main.OK;
        }
    }

    /** What is waiting on a cheque. */
    @Command(name = "unpaid", description = "Everything waiting on a cheque, oldest first.")
    static final class Unpaid implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Override
        public Integer call() {
            List<SettlementRow> rows;
            try (UnitOfWork uow = main.unitOfWork()) {
                rows = uow.settlements().unpaid();
            }
            PrintWriter out = main.out();
            for (SettlementRow row : rows) {
                out.println(left(row.getNumber(), 16) + row.getSettledOn() + "  " + left(row.getPartyCode(), 16)
                        + right(row.getNet().toString(), 12));
            }
            out.println(rows.size() + " unpaid");
            return Main.OK;
        }
    }
}
