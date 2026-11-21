package io.github.rkaushikdev.bushelworks.cli;

import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.domain.position.PositionReport;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.StorageLotRow;
import io.github.rkaushikdev.bushelworks.reports.position.PositionBoard;
import io.github.rkaushikdev.bushelworks.services.payments.Balance;
import io.github.rkaushikdev.bushelworks.services.payments.Cheque;
import io.github.rkaushikdev.bushelworks.services.payments.ChequeOptions;
import io.github.rkaushikdev.bushelworks.services.payments.ChequeRun;
import io.github.rkaushikdev.bushelworks.services.payments.Payments;
import io.github.rkaushikdev.bushelworks.services.positions.Positions;
import io.github.rkaushikdev.bushelworks.services.warehousing.AccrualLine;
import io.github.rkaushikdev.bushelworks.services.warehousing.AccrualRun;
import io.github.rkaushikdev.bushelworks.services.warehousing.Warehousing;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * The storage, position and cheque commands.
 *
 * <p>The month end run lives here. It is three commands rather than one because the office wants to
 * look at the accrual before it commits it, and because the cheque run is a separate decision made
 * on a different day.
 */
final class YardCommands {

    /** The commands this module adds to the group. */
    static final List<Class<?>> COMMANDS =
            List.of(Lots.class, OnHand.class, Accrue.class, Position.class, Cheques.class);

    private YardCommands() {}

    /** The open storage lots. */
    @Command(name = "lots", description = "Open storage lots.")
    static final class Lots implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Option(names = "--party", paramLabel = "TEXT")
        private String party;

        @Option(names = "--commodity", paramLabel = "TEXT")
        private String commodity;

        @Option(names = "--location", paramLabel = "TEXT")
        private String location;

        @Override
        public Integer call() {
            List<String> lines = new ArrayList<>();
            try (UnitOfWork uow = main.unitOfWork()) {
                for (StorageLotRow row : Warehousing.openLots(uow, party, commodity, location)) {
                    String kind = row.isDeferredPrice() ? "DP " : "STO";
                    lines.add(left(row.getLotId(), 14) + kind + " " + left(row.getPartyCode(), 16)
                            + left(row.getCommodityCode(), 6) + row.getStoredOn()
                            + right(row.getOpenBushels().toString(), 14)
                            + right(row.getAccruedToDate().toString(), 12));
                }
            }
            PrintWriter out = main.out();
            lines.forEach(out::println);
            out.println(lines.size() + " open lots");
            return Main.OK;
        }
    }

    /** What the house holds for producers. */
    @Command(name = "on-hand", description = "Bushels the house is holding for producers.")
    static final class OnHand implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "COMMODITY")
        private String commodity;

        @Option(names = "--location", paramLabel = "TEXT")
        private String location;

        @Override
        public Integer call() {
            BigDecimal bushels;
            try (UnitOfWork uow = main.unitOfWork()) {
                bushels = Warehousing.onHand(uow, commodity, location);
            }
            main.out().println(commodity + " " + bushels + " bu held for producers");
            return Main.OK;
        }
    }

    /** The storage run. */
    @Command(name = "accrue", description = "Charge storage on every open lot up to a date.")
    static final class Accrue implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "THROUGH", converter = Arguments.Day.class)
        private LocalDate through;

        @Option(names = "--location", paramLabel = "TEXT")
        private String location;

        private boolean commit;

        @Option(names = "--commit", description = "Bill the lots.")
        void commit(boolean given) {
            commit |= given;
        }

        @Option(names = "--dry-run", description = "Only show what would be billed. The default.")
        void dryRun(boolean given) {
            commit &= !given;
        }

        @Override
        public Integer call() {
            AccrualRun run;
            try (UnitOfWork uow = main.unitOfWork()) {
                run = Warehousing.accrue(uow, main.rules(), through, location, commit);
                if (commit) {
                    uow.commit();
                }
            }
            PrintWriter out = main.out();
            for (AccrualLine line : run.lines()) {
                out.println(left(line.lotId(), 14)
                        + left(line.partyCode(), 16)
                        + right(line.openBushels().toString(), 14)
                        + right(line.dollars().toString(), 12));
            }
            String verb = commit ? "billed" : "would bill";
            out.println(run.lines().size() + " lots, " + verb + " " + run.total() + " through " + through);
            return Main.OK;
        }
    }

    /** The daily position. */
    @Command(name = "position", description = "The daily position, as a board, a hedge sheet or a spreadsheet.")
    static final class Position implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "AS_OF", converter = Arguments.Day.class)
        private LocalDate asOf;

        @Option(names = "--location", defaultValue = Positions.EVERY_SITE, paramLabel = "TEXT")
        private String location;

        @Option(
                names = "--format",
                defaultValue = "board",
                paramLabel = "[board|hedge|csv]",
                converter = Arguments.Layout.class)
        private String layout;

        @Override
        public Integer call() {
            String text;
            try (UnitOfWork uow = main.unitOfWork()) {
                PositionReport report = Positions.buildReport(uow, asOf, List.of(), location);
                text = switch (layout) {
                    case "board" -> PositionBoard.render(report, main.settings().houseName());
                    case "hedge" -> PositionBoard.hedgeSheet(report);
                    default -> PositionBoard.toCsv(report);
                };
            }
            main.out().println(text);
            return Main.OK;
        }
    }

    /** The cheque run. */
    @Command(name = "cheques", description = "Pay everything unpaid, one cheque per producer.")
    static final class Cheques implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Option(
                names = "--first",
                required = true,
                paramLabel = "TEXT",
                description = "The first cheque number off the stock.")
        private String first;

        @Option(
                names = "--on",
                paramLabel = "[" + Arguments.DAY_FORMAT + "]",
                converter = Arguments.Day.class,
                description = "Payment date, defaulting to today.")
        private LocalDate on;

        @Option(names = "--party", paramLabel = "TEXT", description = "Limit to named producers.")
        private List<String> parties = new ArrayList<>();

        @Override
        public Integer call() {
            LocalDate paidOn = on != null ? on : LocalDate.now();
            ChequeRun run;
            try (UnitOfWork uow = main.unitOfWork()) {
                run = Payments.runCheques(
                        uow,
                        paidOn,
                        first,
                        ChequeOptions.defaults()
                                .withPartyCodes(parties.isEmpty() ? null : parties)
                                .withActor(GrainCommands.ACTOR));
                uow.commit();
            }
            PrintWriter out = main.out();
            for (Cheque cheque : run.cheques()) {
                out.println(left(cheque.chequeNumber(), 10)
                        + left(cheque.partyCode(), 20)
                        + right(cheque.dollars().toString(), 12));
            }
            for (Balance held : run.held()) {
                out.println(left("held", 10) + left(held.partyCode(), 20)
                        + right(held.owed().toString(), 12) + "  debit balance");
            }
            out.println(run.cheques().size() + " cheques, " + run.total());
            return Main.OK;
        }
    }
}
