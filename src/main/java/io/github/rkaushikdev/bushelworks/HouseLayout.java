package io.github.rkaushikdev.bushelworks;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.event.KeyValuePair;

/**
 * Timestamp, level, event, then the fields: one log line in the house format.
 *
 * <p>The request id, when one is set, is the first field. A failure that carried a cause gets its
 * trace as a last {@code error} field.
 */
public final class HouseLayout extends LayoutBase<ILoggingEvent> {

    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);

    @Override
    public String doLayout(ILoggingEvent event) {
        return line(event) + System.lineSeparator();
    }

    /** The line without its line ending. */
    static String line(ILoggingEvent event) {
        StringBuilder out = new StringBuilder(96);
        LocalDateTime when =
                LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getTimeStamp()), ZoneId.systemDefault());
        out.append(STAMP.format(when))
                .append(' ')
                .append(levelName(event.getLevel()))
                .append(' ');
        out.append(event.getFormattedMessage());
        List<HouseLog.Field> fields = new ArrayList<>();
        HouseLog.currentRequestId().ifPresent(id -> fields.add(HouseLog.field("request_id", id)));
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            for (KeyValuePair pair : pairs) {
                fields.add(HouseLog.field(pair.key, pair.value));
            }
        }
        if (!fields.isEmpty()) {
            out.append(' ').append(HouseLog.renderFields(fields));
        }
        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            out.append(" error=").append(HouseLog.renderValue(ThrowableProxyUtil.asString(thrown)));
        }
        return out.toString();
    }

    /** The level as the office reads it: debug, info, warning, error. */
    static String levelName(Level level) {
        if (level.toInt() >= Level.ERROR_INT) {
            return "error";
        }
        if (level.toInt() >= Level.WARN_INT) {
            return "warning";
        }
        if (level.toInt() >= Level.INFO_INT) {
            return "info";
        }
        return "debug";
    }
}
