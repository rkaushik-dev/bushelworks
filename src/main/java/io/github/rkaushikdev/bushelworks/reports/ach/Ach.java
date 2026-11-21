package io.github.rkaushikdev.bushelworks.reports.ach;

import static io.github.rkaushikdev.bushelworks.Quoting.quoted;

import io.github.rkaushikdev.bushelworks.reports.Columns;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The ACH file the bank wants instead of cheques.
 *
 * <p>NACHA format, PPD entries, one batch. Every record is exactly ninety four characters and the
 * file is padded with filler records until the block count is a multiple of ten, because the bank's
 * reader is older than anybody who works there and it will reject a short block without saying why.
 *
 * <p>The two totals at the bottom are computed rather than passed in. A file whose control record
 * disagrees with its entries is rejected as a whole, and the only way to be sure they agree is to add
 * them here.
 */
public final class Ach {

    /** Every record is this many characters. */
    public static final int RECORD_LENGTH = 94;

    /** The file is padded to a whole number of blocks of this many records. */
    public static final int BLOCKING_FACTOR = 10;

    private static final int[] WEIGHTS = {3, 7, 1, 3, 7, 1, 3, 7};

    private static final long HASH_MODULUS = 10_000_000_000L;

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd", Locale.ROOT);

    private static final String FILLER = "9".repeat(RECORD_LENGTH);

    private Ach() {}

    /**
     * The ninth digit of a routing number, worked out from the first eight.
     *
     * @throws IllegalArgumentException when there are not eight digits to work it from
     */
    public static int checkDigit(String routingNumber) {
        if (routingNumber.length() < WEIGHTS.length) {
            throw new IllegalArgumentException(
                    quoted(routingNumber) + " has fewer than eight digits to work a check digit from");
        }
        int total = 0;
        for (int index = 0; index < WEIGHTS.length; index++) {
            total += digit(routingNumber.charAt(index)) * WEIGHTS[index];
        }
        return (10 - total % 10) % 10;
    }

    /** Nine digits whose last is the check digit of the first eight. */
    public static boolean routingIsValid(String routingNumber) {
        if (!isNineDigits(routingNumber)) {
            return false;
        }
        return checkDigit(routingNumber) == digit(routingNumber.charAt(8));
    }

    /** The file header: who the file is for, who it is from, and when it was made. */
    public static String fileHeader(Originator originator, LocalDate created, String destination) {
        return "101"
                + " " + Columns.right(Columns.clip(destination, 9), 9)
                + " " + Columns.right(Columns.clip(originator.companyId(), 9), 9)
                + created.format(YYMMDD)
                + "0000"
                + "A094101"
                + pad(originator.bankName(), 23)
                + pad(originator.name(), 23)
                + " ".repeat(8);
    }

    /** The header of the one batch: credits only, PPD, paying out on the effective day. */
    public static String batchHeader(Originator originator, LocalDate effective) {
        return "5220"
                + pad(originator.name(), 16)
                + " ".repeat(20)
                + pad(originator.companyId(), 10)
                + "PPD"
                + pad("GRAIN PAY", 10)
                + " ".repeat(6)
                + effective.format(YYMMDD)
                + " ".repeat(3)
                + "1"
                + originator.routingNumber().substring(0, 8)
                + "0000001";
    }

    /** One credit to a checking account, numbered in the batch from one. */
    public static String entry(Credit credit, Originator originator, int sequence) {
        return "622"
                + credit.routingNumber().substring(0, 8)
                + credit.routingNumber().charAt(8)
                + pad(credit.accountNumber(), 17)
                + number(credit.cents(), 10)
                + pad(credit.partyCode(), 15)
                + pad(credit.name(), 22)
                + "  "
                + "0"
                + originator.routingNumber().substring(0, 8)
                + number(sequence, 7);
    }

    /** The batch control: how many entries, their hash and the total credited. */
    public static String batchControl(Originator originator, List<Credit> credits, long entryHash) {
        return "8220"
                + number(credits.size(), 6)
                + number(entryHash % HASH_MODULUS, 10)
                + number(0, 12)
                + number(totalCents(credits), 12)
                + pad(originator.companyId(), 10)
                + " ".repeat(19)
                + " ".repeat(6)
                + originator.routingNumber().substring(0, 8)
                + "0000001";
    }

    /** The file control: one batch, the blocks and records in the file, the hash and the total. */
    public static String fileControl(List<Credit> credits, long entryHash, int blockCount) {
        // Header, batch header, entries, batch control, file control.
        int records = 4 + credits.size();
        return "9"
                + number(1, 6)
                + number(blockCount, 6)
                + number(records, 8)
                + number(entryHash % HASH_MODULUS, 10)
                + number(0, 12)
                + number(totalCents(credits), 12)
                + " ".repeat(39);
    }

    /**
     * The whole file, blocked and padded, ready for the bank: one record to a line, each line ending in
     * a line feed.
     *
     * @param created the day the file was made
     * @param effective the day the credits pay out
     * @param destination the routing number of the bank the file goes to
     * @throws AchError when there are no credits, or a figure does not fit its field
     */
    public static String build(
            Originator originator, List<Credit> credits, LocalDate created, LocalDate effective, String destination) {
        if (credits.isEmpty()) {
            throw new AchError("an ACH file needs at least one credit");
        }

        long entryHash = 0;
        for (Credit credit : credits) {
            entryHash += Long.parseLong(credit.routingNumber().substring(0, 8));
        }
        List<String> records = new ArrayList<>();
        records.add(fileHeader(originator, created, destination));
        records.add(batchHeader(originator, effective));
        for (int index = 0; index < credits.size(); index++) {
            records.add(entry(credits.get(index), originator, index + 1));
        }
        records.add(batchControl(originator, credits, entryHash));

        int blockCount = Math.ceilDiv(records.size() + 1, BLOCKING_FACTOR);
        records.add(fileControl(credits, entryHash, blockCount));
        while (records.size() % BLOCKING_FACTOR != 0) {
            records.add(FILLER);
        }

        for (int index = 0; index < records.size(); index++) {
            String record = records.get(index);
            int length = record.codePointCount(0, record.length());
            if (length != RECORD_LENGTH) {
                throw new AchError("record " + (index + 1) + " is " + length + " characters, not " + RECORD_LENGTH);
            }
        }
        return String.join("\n", records) + "\n";
    }

    /** What the credits add up to, in cents. */
    public static long totalCents(List<Credit> credits) {
        return credits.stream().mapToLong(Credit::cents).sum();
    }

    /** Whether text is exactly nine digits. */
    static boolean isNineDigits(String text) {
        return text.length() == 9 && text.chars().allMatch(Character::isDigit);
    }

    private static int digit(char letter) {
        int value = Character.digit(letter, 10);
        if (value < 0) {
            throw new IllegalArgumentException("'" + letter + "' is not a digit");
        }
        return value;
    }

    /** Text cut to a field and padded on the right to fill it. */
    private static String pad(String text, int width) {
        return Columns.left(Columns.clip(text, width), width);
    }

    /** A number zero filled to its field. */
    private static String number(long value, int width) {
        String text = Long.toString(value);
        if (text.length() > width) {
            throw new AchError(value + " does not fit in " + width + " places");
        }
        return "0".repeat(width - text.length()) + text;
    }
}
