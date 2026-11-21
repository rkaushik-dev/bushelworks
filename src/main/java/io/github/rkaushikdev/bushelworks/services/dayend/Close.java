package io.github.rkaushikdev.bushelworks.services.dayend;

import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.position.PositionReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the day looked like at one site, or across the house, as the close found it.
 *
 * <p>The close fills this in check by check, noting every discrepancy as it goes, and hands it back
 * whole. Nothing outside the close changes it.
 */
public final class Close {

    private final LocalDate day;
    private final String locationCode;
    private int ticketsWritten;
    private int ticketsVoided;
    private BigDecimal bushelsIn = BigDecimal.ZERO;
    private BigDecimal bushelsOut = BigDecimal.ZERO;
    private int settlementsWritten;
    private BigDecimal settledValue = new BigDecimal("0.00");
    private List<String> entriesPosted = List.of();
    private PositionReport position;
    private final List<Discrepancy> discrepancies = new ArrayList<>();

    Close(LocalDate day, String locationCode) {
        this.day = Objects.requireNonNull(day, "day");
        this.locationCode = Objects.requireNonNull(locationCode, "locationCode");
    }

    /** The day closed. */
    public LocalDate day() {
        return day;
    }

    /** The site closed, or {@code *} for the whole house. */
    public String locationCode() {
        return locationCode;
    }

    /** Tickets written on the day and still standing. */
    public int ticketsWritten() {
        return ticketsWritten;
    }

    void setTicketsWritten(int value) {
        ticketsWritten = value;
    }

    /** Tickets written on the day and voided since. */
    public int ticketsVoided() {
        return ticketsVoided;
    }

    void setTicketsVoided(int value) {
        ticketsVoided = value;
    }

    /** What came across the scale. */
    public BigDecimal bushelsIn() {
        return bushelsIn;
    }

    void setBushelsIn(BigDecimal value) {
        bushelsIn = value;
    }

    /** What went out against sales. */
    public BigDecimal bushelsOut() {
        return bushelsOut;
    }

    void setBushelsOut(BigDecimal value) {
        bushelsOut = value;
    }

    /** In less out, at bushel places. */
    public BigDecimal netBushels() {
        return Units.quantize(bushelsIn.subtract(bushelsOut), Units.BUSHEL_PLACES);
    }

    /** Statements dated the day. */
    public int settlementsWritten() {
        return settlementsWritten;
    }

    void setSettlementsWritten(int value) {
        settlementsWritten = value;
    }

    /** What the day's statements net to. */
    public BigDecimal settledValue() {
        return settledValue;
    }

    void setSettledValue(BigDecimal value) {
        settledValue = value;
    }

    /** The journal entries the close wrote, in the order it wrote them. */
    public List<String> entriesPosted() {
        return entriesPosted;
    }

    void setEntriesPosted(List<String> value) {
        entriesPosted = List.copyOf(value);
    }

    /** The position the close rebuilt for the day; empty until the close has got that far. */
    public Optional<PositionReport> position() {
        return Optional.ofNullable(position);
    }

    void setPosition(PositionReport value) {
        position = value;
    }

    /** Everything that did not tie, in the order it was found. */
    public List<Discrepancy> discrepancies() {
        return List.copyOf(discrepancies);
    }

    /** Whether everything tied. */
    public boolean isClean() {
        return discrepancies.isEmpty();
    }

    void note(String kind, String detail) {
        discrepancies.add(new Discrepancy(kind, detail));
    }
}
