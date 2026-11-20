package io.github.rkaushikdev.bushelworks;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Collects the package's log lines in a list while it is open, so a check can read what was
 * logged. Used by the tests and by nothing else.
 *
 * <pre>{@code
 * Capture log = Capture.during(() -> scalehouse.recordLoad(...));
 * assertThat(log.find("ticket.recorded")).hasSize(1);
 * }</pre>
 */
public final class Capture implements AutoCloseable, Iterable<String> {

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private final ListAppender appender;

    private Capture(String level) {
        ch.qos.logback.classic.Logger house = HouseLog.houseLogger();
        appender = new ListAppender(lines);
        appender.setContext(house.getLoggerContext());
        appender.setName("capture");
        appender.start();
        house.setLevel(HouseLog.level(level));
        house.addAppender(appender);
    }

    /** Start collecting at info and above. Close to stop. */
    public static Capture start() {
        return new Capture("INFO");
    }

    /** Start collecting at a level and above. Close to stop. */
    public static Capture start(String level) {
        return new Capture(level);
    }

    /** The lines logged while {@code work} ran, at info and above. */
    public static Capture during(Runnable work) {
        try (Capture capture = start()) {
            work.run();
            return capture;
        }
    }

    /** Every line so far, in order. */
    public List<String> lines() {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }

    /** The lines whose event, or any whole word, is {@code eventName}. */
    public List<String> find(String eventName) {
        String needle = " " + eventName + " ";
        return lines().stream()
                .filter(line -> (" " + line + " ").contains(needle))
                .toList();
    }

    @Override
    public Iterator<String> iterator() {
        return lines().iterator();
    }

    /** Stop collecting. The lines stay readable. */
    @Override
    public void close() {
        HouseLog.houseLogger().detachAppender(appender);
        appender.stop();
    }

    private static final class ListAppender extends AppenderBase<ILoggingEvent> {

        private final List<String> sink;

        ListAppender(List<String> sink) {
            this.sink = sink;
        }

        @Override
        protected void append(ILoggingEvent event) {
            sink.add(HouseLayout.line(event));
        }
    }
}
