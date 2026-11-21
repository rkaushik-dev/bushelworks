package io.github.rkaushikdev.bushelworks.reports.statement;

import static io.github.rkaushikdev.bushelworks.reports.Columns.clip;
import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementLine;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The producer statement, rendered for an eighty column dot matrix.
 *
 * <p>The width is not nostalgia. These print on carbonless three part stock in the scale house and
 * the form has been the same since before the software was, so the columns are fixed and the
 * amounts right align on the same character in every row. A producer who has been reading these for
 * thirty years should not have to learn a new one.
 */
public final class Statements {

    public static final int WIDTH = 80;
    public static final int DESCRIPTION_WIDTH = 44;
    public static final int CODE_WIDTH = 12;
    public static final int AMOUNT_WIDTH = 14;

    /** The heading each kind of line prints under. */
    public static final Map<LineKind, String> KIND_HEADINGS = headings();

    private Statements() {}

    private static Map<LineKind, String> headings() {
        Map<LineKind, String> headings = new EnumMap<>(LineKind.class);
        headings.put(LineKind.GROSS, "GRAIN PURCHASED");
        headings.put(LineKind.DISCOUNT, "QUALITY DISCOUNTS");
        headings.put(LineKind.DRYING, "DRYING");
        headings.put(LineKind.STORAGE, "STORAGE");
        headings.put(LineKind.SERVICE, "SERVICE CHARGES");
        headings.put(LineKind.ASSESSMENT, "CHECKOFF WITHHELD");
        headings.put(LineKind.ADVANCE, "ADVANCES RECOVERED");
        headings.put(LineKind.DEDUCTION, "OTHER DEDUCTIONS");
        headings.put(LineKind.ADJUSTMENT, "ADJUSTMENTS");
        return Map.copyOf(headings);
    }

    /** A rule of dashes across the form. */
    public static String rule() {
        return rule('-');
    }

    /** A rule of {@code letter} across the form. */
    public static String rule(char letter) {
        return String.valueOf(letter).repeat(WIDTH);
    }

    /** {@code text} centred on the form. */
    public static String centre(String text) {
        return Columns.centred(text, WIDTH);
    }

    /** A labelled header field: the label in eighteen columns, then the value. */
    public static String fieldRow(String label, String value) {
        return left(label, 18) + value;
    }

    /**
     * One printed line, and it never runs past the form.
     *
     * <p>A settlement over a million dollars formats to thirteen characters or more, so the amount
     * takes the room it needs and the description gives the room up instead. Otherwise the line runs
     * past eighty columns, the dot matrix wraps it onto a second line, and the producer reads it as a
     * second charge.
     *
     * <p>The code column keeps one character back for the gap. An advance reference of thirteen
     * characters would otherwise fill the column exactly and print as {@code
     * ADV-2024-091advance of 2024-09-12} with nothing between them, which reads as one word and hides
     * the fact that the code was cut short.
     */
    public static String amountRow(String code, String description, BigDecimal dollars) {
        String rendered = Money.formatAmount(dollars);
        int room = Math.max(1, WIDTH - 2 - CODE_WIDTH - rendered.length());
        String shown = clip(code, CODE_WIDTH - 1);
        return "  " + left(shown, CODE_WIDTH) + left(clip(description, room), room) + rendered;
    }

    /** A labelled total, the amount flush with the right edge of the form. */
    public static String totalRow(String label, BigDecimal dollars) {
        String rendered = Money.formatAmount(dollars);
        int room = Math.max(1, WIDTH - 2 - rendered.length());
        return "  " + left(clip(label, room), room) + rendered;
    }

    /** The whole statement under the default house name. */
    public static String render(Settlement settlement) {
        return render(settlement, Settings.DEFAULT_HOUSE_NAME);
    }

    /**
     * The whole statement as one string, newline separated, no trailing blank.
     *
     * <p>Each kind of line present prints under its heading, in statement order; a heading with more
     * than one line under it gets a total. The gross, the deductions and the net close the page.
     */
    public static String render(Settlement settlement, String houseName) {
        List<String> lines = new ArrayList<>();
        lines.add(centre(houseName));
        lines.add(centre("SETTLEMENT STATEMENT"));
        lines.add(rule('='));
        lines.add(fieldRow("Statement", settlement.number()));
        lines.add(fieldRow("Date", settlement.settledOn().toString()));
        lines.add(fieldRow("Producer", settlement.partyId()));
        lines.add(fieldRow("Location", settlement.locationCode()));
        if (!settlement.ticketNumbers().isEmpty()) {
            lines.add(fieldRow("Tickets", String.join(", ", settlement.ticketNumbers())));
        }
        if (!settlement.contractNumbers().isEmpty()) {
            lines.add(fieldRow("Contracts", String.join(", ", settlement.contractNumbers())));
        }
        lines.add(rule());

        for (LineKind kind : LineKind.values()) {
            List<SettlementLine> ofKind = settlement.ofKind(kind);
            if (ofKind.isEmpty()) {
                continue;
            }
            String heading = KIND_HEADINGS.get(kind);
            lines.add(heading);
            for (SettlementLine line : ofKind) {
                lines.add(amountRow(line.code(), line.description(), line.signed()));
            }
            if (ofKind.size() > 1) {
                lines.add(totalRow(heading + " TOTAL", settlement.subtotal(kind)));
            }
            lines.add("");
        }

        lines.add(rule());
        lines.add(totalRow("GROSS", settlement.gross()));
        lines.add(totalRow("LESS DEDUCTIONS", settlement.deductions()));
        lines.add(rule('='));
        lines.add(totalRow("NET PAYABLE", settlement.net()));
        return String.join("\n", lines);
    }

    /** Several statements under the default house name. */
    public static String renderMany(List<Settlement> settlements) {
        return renderMany(settlements, Settings.DEFAULT_HOUSE_NAME);
    }

    /** Several statements separated by a form feed, as the printer expects. */
    public static String renderMany(List<Settlement> settlements, String houseName) {
        return settlements.stream().map(each -> render(each, houseName)).collect(Collectors.joining("\f"));
    }

    /** One row for the daily settlement register, inside the form width. */
    public static String summaryLine(Settlement settlement) {
        String gross = Money.formatAmount(settlement.gross());
        String net = Money.formatAmount(settlement.net());
        int room = Math.max(1, WIDTH - 14 - 12 - 8 - gross.length() - net.length());
        return left(clip(settlement.number(), 14), 14)
                + left(settlement.settledOn().toString(), 12)
                + left(clip(settlement.partyId(), room), room)
                + left(clip(settlement.locationCode(), 8), 8)
                + gross
                + net;
    }

    /** The register the office reconciles against the cheque run. */
    public static String register(List<Settlement> settlements, String asOf) {
        List<String> lines = new ArrayList<>();
        lines.add(centre("SETTLEMENT REGISTER"));
        lines.add(centre(asOf));
        lines.add(rule('='));
        lines.add(left("STATEMENT", 14)
                + left("DATE", 12)
                + left("PRODUCER", 18)
                + left("LOC", 8)
                + right("GROSS", 12)
                + right("NET", 14));
        lines.add(rule());
        for (Settlement settlement : settlements) {
            lines.add(summaryLine(settlement));
        }
        lines.add(rule());
        BigDecimal gross = Money.ZERO_AMOUNT;
        BigDecimal net = Money.ZERO_AMOUNT;
        for (Settlement settlement : settlements) {
            gross = gross.add(settlement.gross());
            net = net.add(settlement.net());
        }
        lines.add(left("", 14)
                + left("", 12)
                + left(settlements.size() + " statements", 18)
                + left("", 8)
                + right(Money.formatAmount(gross), 12)
                + right(Money.formatAmount(net), 14));
        return String.join("\n", lines);
    }
}
