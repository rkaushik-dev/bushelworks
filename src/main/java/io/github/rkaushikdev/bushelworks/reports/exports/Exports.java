package io.github.rkaushikdev.bushelworks.reports.exports;

import io.github.rkaushikdev.bushelworks.Csv;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementLine;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spreadsheet exports.
 *
 * <p>Everybody who asks for data asks for it as comma separated text, and the two people who ask
 * most are the auditor and the lender. Neither of them wants the printed layout, and both will open
 * it in a spreadsheet that guesses at types, so the rules here are about not being helpfully
 * reinterpreted:
 *
 * <ul>
 *   <li>dates are ISO, because a spreadsheet reads 03/10/2024 as March in one country and October
 *       in the other
 *   <li>quantities are unrounded decimals as text
 *   <li>a ticket number that looks like a number is still text, and it is written so that it stays
 *       that way
 * </ul>
 *
 * <p>The last one is why every identifier goes through {@link #textField}. A ticket numbered 0010441
 * that loses its leading zeroes is a ticket nobody can find.
 */
public final class Exports {

    public static final List<String> TICKET_COLUMNS = List.of(
            "ticket_number",
            "location_code",
            "scale_date",
            "commodity_code",
            "party_code",
            "gross_pounds",
            "tare_pounds",
            "net_pounds",
            "dockage_percent",
            "disposition",
            "contract_number",
            "voided",
            "settled");

    public static final List<String> SETTLEMENT_COLUMNS =
            List.of("number", "settled_on", "party_code", "location_code", "gross", "deductions", "net", "tickets");

    public static final List<String> LINE_COLUMNS = List.of(
            "settlement_number", "sequence", "kind", "code", "description", "dollars", "signed", "quantity", "rate");

    private Exports() {}

    /**
     * Write an identifier so a spreadsheet leaves it alone.
     *
     * <p>An identifier of digits with a leading zero is written as {@code ="0010441"}: the equals and
     * the quotes are what a spreadsheet needs to keep it as text. It looks odd in a text editor, and it
     * is the difference between a file the office can use and one it cannot. Anything else is written
     * as it is, and nothing is written as nothing.
     */
    public static String textField(Object value) {
        if (value instanceof Optional<?> maybe) {
            return textField(maybe.orElse(null));
        }
        if (value == null) {
            return "";
        }
        String text = value.toString();
        boolean digits = !text.isEmpty() && text.chars().allMatch(Character::isDigit);
        if (digits && text.charAt(0) == '0') {
            return "=\"" + text + "\"";
        }
        return text;
    }

    /**
     * One value as the office reads it: nothing as an empty cell, a flag as {@code yes} or {@code
     * no}, a decimal with its exact places, a date as ISO, anything else as its text.
     */
    public static String cell(Object value) {
        if (value instanceof Optional<?> maybe) {
            return cell(maybe.orElse(null));
        }
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean flag) {
            return flag ? "yes" : "no";
        }
        if (value instanceof BigDecimal number) {
            return number.toString();
        }
        if (value instanceof LocalDate day) {
            return day.toString();
        }
        return value.toString();
    }

    /** Scale tickets, one row each, under a header of {@link #TICKET_COLUMNS}. */
    public static String ticketsToCsv(Iterable<ScaleTicketRow> rows) {
        StringBuilder out = new StringBuilder(Csv.row(TICKET_COLUMNS));
        for (ScaleTicketRow row : rows) {
            out.append(Csv.row(
                    textField(row.getTicketNumber()),
                    cell(row.getLocationCode()),
                    cell(row.getScaleDate()),
                    cell(row.getCommodityCode()),
                    cell(row.getPartyCode()),
                    cell(row.getGrossPounds()),
                    cell(row.getTarePounds()),
                    cell(row.getNetPounds()),
                    cell(row.getDockagePercent()),
                    cell(row.getDisposition()),
                    textField(row.getContractNumber()),
                    cell(row.isVoided()),
                    cell(row.isSettled())));
        }
        return out.toString();
    }

    /** One row per statement, under a header of {@link #SETTLEMENT_COLUMNS}, the tickets it covers in a cell. */
    public static String settlementsToCsv(List<Settlement> settlements) {
        StringBuilder out = new StringBuilder(Csv.row(SETTLEMENT_COLUMNS));
        for (Settlement statement : settlements) {
            out.append(Csv.row(
                    textField(statement.number()),
                    cell(statement.settledOn()),
                    cell(statement.partyId()),
                    cell(statement.locationCode()),
                    cell(statement.gross()),
                    cell(statement.deductions()),
                    cell(statement.net()),
                    String.join(" ", statement.ticketNumbers())));
        }
        return out.toString();
    }

    /**
     * Every settlement line, numbered from one within its statement, under a header of {@link
     * #LINE_COLUMNS}. This is the file the auditor actually wants.
     */
    public static String linesToCsv(List<Settlement> settlements) {
        StringBuilder out = new StringBuilder(Csv.row(LINE_COLUMNS));
        for (Settlement statement : settlements) {
            int sequence = 1;
            for (SettlementLine line : statement.lines()) {
                out.append(Csv.row(
                        textField(statement.number()),
                        sequence++,
                        cell(line.kind().value()),
                        cell(line.code()),
                        cell(line.description()),
                        cell(line.dollars()),
                        cell(line.signed()),
                        cell(line.quantity()),
                        cell(line.rate())));
            }
        }
        return out.toString();
    }

    /**
     * Read an export back and say how many rows it holds under its header.
     *
     * <p>Run against every file the office sends out. A file with an embedded line break in a remark
     * reads back with the wrong number of columns, and finding that out here is much cheaper than
     * finding it out from the auditor.
     *
     * @throws ExportError when the text is empty, its header is not {@code expectedColumns}, or a
     *     row has a different number of fields from the header
     */
    public static int checkRoundTrip(String text, List<String> expectedColumns) {
        List<List<String>> rows = Csv.parse(text);
        if (rows.isEmpty()) {
            throw new ExportError("the export is empty, not even a header");
        }
        List<String> header = rows.get(0);
        if (!header.equals(expectedColumns)) {
            throw new ExportError("header is " + header + ", expected " + expectedColumns);
        }
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.size() != header.size()) {
                throw new ExportError(
                        "row " + (index + 1) + " has " + row.size() + " columns, expected " + header.size());
            }
        }
        return rows.size() - 1;
    }
}
