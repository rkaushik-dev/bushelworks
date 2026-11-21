package io.github.rkaushikdev.bushelworks.services.board;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.pricing.CashBid;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import io.github.rkaushikdev.bushelworks.domain.pricing.PricingError;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * The price board: what the sign by the driveway says, and what the telephone answers.
 *
 * <p>A bid is posted for a commodity, a delivery slot and a site, and it stands until it is replaced
 * or the day ends. Nothing here prices a ticket: a bid is an offer and a contract is what a ticket
 * settles on.
 *
 * <p>Bids are kept, not overwritten. The producer who says he was told four seventy two at ten past
 * nine is usually right, and the only way to find out is to have the nine o'clock board still on
 * file.
 */
public final class Board {

    /**
     * How far a new bid may move from the last one before the desk has to confirm it. A fat finger on
     * the futures line is the common cause and it is expensive.
     */
    public static final BigDecimal MOVE_GUARD = new BigDecimal("0.50");

    private static final Logger LOG = HouseLog.getLogger("board");

    private final Clock clock;
    private final List<Posting> postings = new ArrayList<>();

    /** A board that dates bids by the system clock. */
    public Board() {
        this(Clock.systemDefaultZone());
    }

    /** A board that takes today's date and the posting time from {@code clock}. */
    public Board(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Put a bid up, quoted today and posted now. */
    public Posting post(Slot slot, FuturesMonth month, BigDecimal futures, BigDecimal basis, String postedBy) {
        return post(slot, month, futures, basis, postedBy, LocalDate.now(clock));
    }

    /** Put a bid up, posted now. */
    public Posting post(
            Slot slot, FuturesMonth month, BigDecimal futures, BigDecimal basis, String postedBy, LocalDate quotedOn) {
        return post(slot, month, futures, basis, postedBy, quotedOn, now(), false);
    }

    /** Put a bid up at a stated moment, refusing a wild move. */
    public Posting post(
            Slot slot,
            FuturesMonth month,
            BigDecimal futures,
            BigDecimal basis,
            String postedBy,
            LocalDate quotedOn,
            OffsetDateTime postedAt) {
        return post(slot, month, futures, basis, postedBy, quotedOn, postedAt, false);
    }

    /**
     * Put a bid up, refusing a wild move unless it is confirmed.
     *
     * <p>The first bid of a slot is never guarded; after that a cash price more than {@link
     * #MOVE_GUARD} away from the standing one needs {@code confirmMove}.
     *
     * @throws BoardError when nobody's name is on the bid
     * @throws PricingError when the futures month is for another commodity
     * @throws ConflictError when the move is wild and not confirmed
     */
    public Posting post(
            Slot slot,
            FuturesMonth month,
            BigDecimal futures,
            BigDecimal basis,
            String postedBy,
            LocalDate quotedOn,
            OffsetDateTime postedAt,
            boolean confirmMove) {
        if (postedBy.isEmpty()) {
            throw new BoardError(slot.label() + ": a bid needs somebody's name on it");
        }
        if (!month.commodityCode().equals(slot.commodityCode())) {
            throw new PricingError(month.symbol() + " is not a " + slot.commodityCode() + " contract");
        }

        CashBid bid = new CashBid(
                slot.commodityCode(), month, Money.price(futures), Money.price(basis), quotedOn, slot.locationCode());
        Optional<Posting> previous = latest(slot);
        if (previous.isPresent() && !confirmMove) {
            BigDecimal standing = previous.get().cash();
            BigDecimal move = bid.cash().subtract(standing).abs();
            if (move.compareTo(MOVE_GUARD) > 0) {
                throw new ConflictError(slot.label() + ": " + bid.cash() + " is " + move + " away from the standing "
                        + standing + ", confirm the move to post it");
            }
        }

        Posting posting = new Posting(slot, bid, postedAt, postedBy);
        postings.add(posting);
        HouseLog.event(
                LOG,
                "bid.posted",
                field("slot", slot.label()),
                field("month", month.symbol()),
                field("futures", bid.futures()),
                field("basis", bid.basis()),
                field("cash", bid.cash()),
                field("by", postedBy));
        return posting;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** The bid standing for a slot, if anything was ever posted. */
    public Optional<Posting> latest(Slot slot) {
        for (int index = postings.size() - 1; index >= 0; index--) {
            if (postings.get(index).slot().equals(slot)) {
                return Optional.of(postings.get(index));
            }
        }
        return Optional.empty();
    }

    /**
     * The bid standing for a slot.
     *
     * @throws NoBid when nothing was ever posted for it
     */
    public Posting require(Slot slot) {
        return latest(slot).orElseThrow(() -> new NoBid("nothing posted for " + slot.label()));
    }

    /** What the board said at a moment, which is what the producer asks. */
    public Optional<Posting> asAt(Slot slot, OffsetDateTime moment) {
        Optional<Posting> found = Optional.empty();
        for (Posting posting : postings) {
            if (posting.slot().equals(slot) && !posting.postedAt().isAfter(moment)) {
                found = Optional.of(posting);
            }
        }
        return found;
    }

    /** Every bid ever posted for a slot, oldest first. */
    public List<Posting> history(Slot slot) {
        return postings.stream().filter(posting -> posting.slot().equals(slot)).toList();
    }

    /** The slots anything was posted for, by commodity, period and site. */
    public List<Slot> slots() {
        return postings.stream()
                .map(Posting::slot)
                .distinct()
                .sorted(Slot.ORDER)
                .toList();
    }

    /** The board as it stands, one row per slot, in slot order. */
    public Map<Slot, Posting> current() {
        Map<Slot, Posting> board = new LinkedHashMap<>();
        for (Slot slot : slots()) {
            board.put(slot, require(slot));
        }
        return board;
    }

    /**
     * How far a bid has moved since a moment, for the day sheet.
     *
     * @throws NoBid when nothing was posted for the slot by then
     */
    public BigDecimal moveSince(Slot slot, OffsetDateTime moment) {
        Optional<Posting> then = asAt(slot, moment);
        Optional<Posting> now = latest(slot);
        if (then.isEmpty() || now.isEmpty()) {
            throw new NoBid("nothing posted for " + slot.label());
        }
        return Units.quantize(now.get().cash().subtract(then.get().cash()), Money.PRICE_PLACES);
    }
}
