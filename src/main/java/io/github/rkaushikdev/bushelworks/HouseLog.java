package io.github.rkaushikdev.bushelworks;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Logging that a grain accountant can grep.
 *
 * <p>One line per event, key equals value, no multi line records. The office box has no log
 * aggregator and nobody is going to install one, so the format has to be readable in {@code less}
 * and greppable in a batch file. That rules out JSON, which is unreadable at the terminal, and it
 * rules out free text, which is ungreppable.
 *
 * <pre>2024-10-03T05:31:02 info ticket.recorded ticket=A-10441 party=MERRICK</pre>
 *
 * <p>Two rules are enforced rather than suggested: every quantity is written as its exact decimal
 * text, places and all, and a value containing a space is quoted, so {@code grep 'party=MERRICK'}
 * cannot match the middle of somebody's name.
 *
 * <p>Events go through SLF4J with their fields as key-value pairs. {@link #configure} points the
 * package's logger at one stream in this format.
 */
public final class HouseLog {

    /** The package's logger. Every named logger sits under it. */
    public static final String LOGGER_NAME = "bushelworks";

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private static final Map<String, Level> LEVELS = Map.of(
            "DEBUG", Level.DEBUG,
            "INFO", Level.INFO,
            "WARNING", Level.WARN,
            "WARN", Level.WARN,
            "ERROR", Level.ERROR,
            "CRITICAL", Level.ERROR);

    private HouseLog() {}

    /**
     * One key and its value, in a log line.
     *
     * @param name the key
     * @param value the value, which may be null
     */
    public record Field(String name, Object value) {}

    /** A field for an event. */
    public static Field field(String name, Object value) {
        return new Field(name, value);
    }

    /**
     * The request the current thread is serving, set by the API and read by every line written
     * while it is set. Null clears it.
     */
    public static void setRequestId(String value) {
        if (value == null) {
            REQUEST_ID.remove();
        } else {
            REQUEST_ID.set(value);
        }
    }

    public static Optional<String> currentRequestId() {
        return Optional.ofNullable(REQUEST_ID.get());
    }

    /** One value, written so it cannot split a field. */
    public static String renderValue(Object value) {
        if (value == null) {
            return "-";
        }
        if (value instanceof BigDecimal number) {
            return number.toString();
        }
        if (value instanceof Boolean flag) {
            return flag ? "yes" : "no";
        }
        if (value instanceof Optional<?> maybe) {
            return renderValue(maybe.orElse(null));
        }
        String text = value.toString();
        if (text.isEmpty()) {
            return "\"\"";
        }
        if (text.chars().anyMatch(letter -> " \t=\"'".indexOf(letter) >= 0)) {
            return '"' + text.replace("\"", "\\\"") + '"';
        }
        return text;
    }

    /** Key equals value, in the order they were given. */
    public static String renderFields(List<Field> fields) {
        return fields.stream()
                .map(each -> each.name() + "=" + renderValue(each.value()))
                .collect(Collectors.joining(" "));
    }

    /** Key equals value, in the order they were given. */
    public static String renderFields(Field... fields) {
        return renderFields(List.of(fields));
    }

    /** Point the package's logger at standard error. Safe to call twice. */
    public static Logger configure(String level) {
        return configure(level, System.err);
    }

    /**
     * Point the package's logger at one stream, replacing whatever it wrote to before. Safe to call
     * twice; the stream is never closed by this.
     *
     * @param level DEBUG, INFO, WARNING or ERROR, in any case
     */
    public static Logger configure(String level, OutputStream stream) {
        ch.qos.logback.classic.Logger house = houseLogger();
        LoggerContext context = house.getLoggerContext();
        house.setLevel(level(level));
        house.setAdditive(false);
        house.detachAndStopAllAppenders();

        LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
        encoder.setContext(context);
        HouseLayout layout = new HouseLayout();
        layout.setContext(context);
        layout.start();
        encoder.setLayout(layout);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setName("house");
        appender.setEncoder(encoder);
        appender.setImmediateFlush(true);
        appender.setOutputStream(new KeptOpen(stream));
        appender.start();
        house.addAppender(appender);
        return house;
    }

    /** The level a name stands for. */
    static Level level(String name) {
        Level chosen = LEVELS.get(name.toUpperCase(Locale.ROOT));
        if (chosen == null) {
            throw new ConfigurationError("'" + name + "' is not a log level");
        }
        return chosen;
    }

    /** How many places the package's logger writes to. */
    public static int handlerCount() {
        int count = 0;
        for (var appenders = houseLogger().iteratorForAppenders(); appenders.hasNext(); appenders.next()) {
            count++;
        }
        return count;
    }

    /** Whether the package's logger hands its lines on to the root logger as well. */
    public static boolean propagates() {
        return houseLogger().isAdditive();
    }

    /** The package's logger. */
    public static Logger getLogger() {
        return LoggerFactory.getLogger(LOGGER_NAME);
    }

    /** A named logger under the package's own, such as {@code bushelworks.intake}. */
    public static Logger getLogger(String name) {
        return LoggerFactory.getLogger(LOGGER_NAME + "." + name);
    }

    /**
     * Log one named event with its fields.
     *
     * <p>The event name is the thing to grep for and it never changes. Fields come and go; {@code
     * ticket.recorded} does not.
     */
    public static void event(Logger logger, String name, Field... fields) {
        emit(logger.atInfo(), name, fields);
    }

    public static void warn(Logger logger, String name, Field... fields) {
        emit(logger.atWarn(), name, fields);
    }

    public static void failure(Logger logger, String name, Field... fields) {
        emit(logger.atError(), name, fields);
    }

    /** An error event carrying the failure that caused it. */
    public static void failure(Logger logger, String name, Throwable cause, Field... fields) {
        emit(logger.atError().setCause(cause), name, fields);
    }

    private static void emit(LoggingEventBuilder builder, String name, Field... fields) {
        LoggingEventBuilder event = builder;
        for (Field each : fields) {
            event = event.addKeyValue(each.name(), each.value());
        }
        event.log(name);
    }

    static ch.qos.logback.classic.Logger houseLogger() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        return context.getLogger(LOGGER_NAME);
    }

    /** A stream an appender may stop without closing what it was handed. */
    private static final class KeptOpen extends FilterOutputStream {

        KeptOpen(OutputStream stream) {
            super(stream);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
