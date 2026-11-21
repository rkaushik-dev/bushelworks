package io.github.rkaushikdev.bushelworks.services.board;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The board at open and at close, for one day. */
public final class DaySheet {

    /** How far a slot has to move before it counts as a mover, unless asked otherwise. */
    public static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.10");

    /**
     * One slot's change over the day.
     *
     * @param slot the slot
     * @param change close less open
     */
    public record Move(Slot slot, BigDecimal change) {}

    private final LocalDate day;
    private final Map<Slot, BigDecimal> opened = new LinkedHashMap<>();
    private final Map<Slot, BigDecimal> closed = new LinkedHashMap<>();

    public DaySheet(LocalDate day) {
        this.day = Objects.requireNonNull(day, "day");
    }

    public LocalDate day() {
        return day;
    }

    /** The opening cash prices, in the order they were recorded. */
    public Map<Slot, BigDecimal> opened() {
        return Collections.unmodifiableMap(opened);
    }

    /** The closing cash prices, in the order they were first recorded. */
    public Map<Slot, BigDecimal> closed() {
        return Collections.unmodifiableMap(closed);
    }

    public void recordOpen(Slot slot, BigDecimal cash) {
        opened.put(slot, Money.price(cash));
    }

    /**
     * Record a slot's close.
     *
     * @throws BoardError when the slot never opened
     */
    public void recordClose(Slot slot, BigDecimal cash) {
        if (!opened.containsKey(slot)) {
            throw new BoardError(slot.label() + " closed without opening");
        }
        closed.put(slot, Money.price(cash));
    }

    /**
     * Close less open for one slot.
     *
     * @throws NoBid when the slot has not closed
     */
    public BigDecimal change(Slot slot) {
        if (!closed.containsKey(slot)) {
            throw new NoBid(slot.label() + " has not closed");
        }
        return Units.quantize(closed.get(slot).subtract(opened.get(slot)), Money.PRICE_PLACES);
    }

    /** Slots that moved by ten cents or more, biggest first. */
    public List<Move> movers() {
        return movers(DEFAULT_THRESHOLD);
    }

    /** Slots that moved by the threshold or more either way, biggest first, ties in closing order. */
    public List<Move> movers(BigDecimal threshold) {
        return closed.keySet().stream()
                .map(slot -> new Move(slot, change(slot)))
                .filter(move -> move.change().abs().compareTo(threshold) >= 0)
                .sorted(Comparator.comparing((Move move) -> move.change().abs()).reversed())
                .toList();
    }
}
