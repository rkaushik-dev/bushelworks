package io.github.rkaushikdev.bushelworks.cli;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Quoting;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * How the words on a command line become the values a command works with, and the sentence the
 * operator reads when one will not.
 *
 * <p>A value that cannot be read is a usage error, so the command never runs and the exit code says
 * the arguments were wrong.
 */
final class Arguments {

    /** The one shape a day is typed in, as the refusal names it. */
    static final String DAY_FORMAT = "%Y-%m-%d";

    private Arguments() {}

    /**
     * A money or quantity argument, parsed exactly.
     *
     * <p>A binary floating point type would take these and would quietly turn 4.35 into something that
     * is not 4.35, which then multiplies by nine hundred bushels. The places typed are kept: {@code
     * 4.3500} stays {@code 4.3500}.
     */
    static final class Exact implements ITypeConverter<BigDecimal> {

        @Override
        public BigDecimal convert(String value) {
            try {
                return Decimals.parse(value);
            } catch (NumberFormatException notANumber) {
                throw new TypeConversionException(Quoting.quoted(value) + " is not a number");
            }
        }
    }

    /**
     * A day, written year, month, day with dashes between. The year has four digits; the month and the
     * day may have one or two, and a day may be a space and one digit.
     */
    static final class Day implements ITypeConverter<LocalDate> {

        private static final Pattern SHAPE =
                Pattern.compile("(\\d{4})-(1[0-2]|0[1-9]|[1-9])-(3[01]|[12]\\d|0[1-9]|[1-9]| [1-9])");

        @Override
        public LocalDate convert(String value) {
            Matcher day = SHAPE.matcher(value);
            if (!day.matches()) {
                throw wrongShape(value);
            }
            try {
                return LocalDate.of(
                        Integer.parseInt(day.group(1)),
                        Integer.parseInt(day.group(2)),
                        Integer.parseInt(day.group(3).strip()));
            } catch (DateTimeException noSuchDay) {
                // The thirty first of a thirty day month is refused the way a misspelt day is.
                throw wrongShape(value);
            }
        }

        private static TypeConversionException wrongShape(String value) {
            return new TypeConversionException(
                    Quoting.quoted(value) + " does not match the format '" + DAY_FORMAT + "'.");
        }
    }

    /** A file that has to be there already, such as the old system's ticket file. */
    static final class ExistingFile implements ITypeConverter<Path> {

        @Override
        public Path convert(String value) {
            Path path = pathOf(value);
            if (!Files.exists(path)) {
                throw new TypeConversionException("File " + Quoting.quoted(value) + " does not exist.");
            }
            return notADirectory(path, value);
        }
    }

    /** A file a command writes. It need not be there yet, but it cannot be a directory. */
    static final class OutputFile implements ITypeConverter<Path> {

        @Override
        public Path convert(String value) {
            return notADirectory(pathOf(value), value);
        }
    }

    private static Path pathOf(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException unusable) {
            throw new TypeConversionException("File " + Quoting.quoted(value) + " is not a usable path.");
        }
    }

    private static Path notADirectory(Path path, String value) {
        if (Files.isDirectory(path)) {
            throw new TypeConversionException("File " + Quoting.quoted(value) + " is a directory.");
        }
        return path;
    }

    /** One of a fixed set of words, spelled exactly. */
    abstract static class Choice implements ITypeConverter<String> {

        private final List<String> choices;

        Choice(String... choices) {
            this.choices = List.of(choices);
        }

        @Override
        public String convert(String value) {
            if (choices.contains(value)) {
                return value;
            }
            String named = choices.stream().map(Quoting::quoted).collect(Collectors.joining(", "));
            throw new TypeConversionException(Quoting.quoted(value) + " is not one of " + named + ".");
        }
    }

    /** What the patronage page is ranked on. */
    static final class Ranking extends Choice {

        Ranking() {
            super("bushels", "margin");
        }
    }

    /** How the position is printed. */
    static final class Layout extends Choice {

        Layout() {
            super("board", "hedge", "csv");
        }
    }
}
