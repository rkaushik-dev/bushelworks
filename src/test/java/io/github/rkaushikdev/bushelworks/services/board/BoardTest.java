package io.github.rkaushikdev.bushelworks.services.board;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import io.github.rkaushikdev.bushelworks.domain.pricing.PricingError;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoardTest {

    static final FuturesMonth CZ4 = new FuturesMonth("CORN", "Z", 2024);
    static final FuturesMonth CH5 = new FuturesMonth("CORN", "H", 2025);
    static final Slot HARVEST = new Slot("CORN", "harvest", "HOME");
    static final Slot DEFERRED = new Slot("CORN", "january", "HOME");
    static final LocalDate QUOTED = LocalDate.of(2024, 10, 3);

    static final OffsetDateTime NINE = at(9);
    static final OffsetDateTime TEN = at(10);
    static final OffsetDateTime NOON = at(12);

    static OffsetDateTime at(int hour) {
        return OffsetDateTime.of(2024, 10, 3, hour, 0, 0, 0, ZoneOffset.UTC);
    }

    static Board aBoard() {
        Board board = new Board();
        board.post(HARVEST, CZ4, d("4.5750"), d("-0.2250"), "kbrock", QUOTED, NINE);
        return board;
    }

    @Test
    void aSlotNeedsACommodityAndAPeriod() {
        assertThatThrownBy(() -> new Slot("", "harvest")).isInstanceOf(BoardError.class);
        assertThatThrownBy(() -> new Slot("CORN", "")).isInstanceOf(BoardError.class);
    }

    @Test
    void aSlotLabelsItself() {
        assertThat(HARVEST.label()).isEqualTo("CORN harvest HOME");
    }

    @Test
    void aPostedBidIsFuturesPlusBasis() {
        Posting posting = aBoard().require(HARVEST);
        assertThat(posting.cash()).isEqualTo(d("4.3500"));
        assertThat(posting.basis()).isEqualTo(d("-0.2250"));
        assertThat(posting.postedBy()).isEqualTo("kbrock");
    }

    @Test
    void aBidNeedsSomebodysNameOnIt() {
        Board board = new Board();
        assertThatThrownBy(() -> board.post(HARVEST, CZ4, d("4.57"), d("-0.22"), "", QUOTED))
                .isInstanceOf(BoardError.class);
    }

    @Test
    void aMonthOfTheWrongCommodityIsRefused() {
        Board board = new Board();
        assertThatThrownBy(() -> board.post(new Slot("SOYB", "harvest"), CZ4, d("11.00"), d("-0.30"), "kbrock", QUOTED))
                .isInstanceOf(PricingError.class);
    }

    @Test
    void nothingPostedForASlotSaysSo() {
        assertThatThrownBy(() -> new Board().require(HARVEST)).isInstanceOf(NoBid.class);
        assertThat(new Board().latest(HARVEST)).isEmpty();
    }

    @Test
    void theLatestBidReplacesTheEarlierOne() {
        Board board = aBoard();
        board.post(HARVEST, CZ4, d("4.6000"), d("-0.2250"), "kbrock", QUOTED, TEN);
        assertThat(board.require(HARVEST).cash()).isEqualTo(d("4.3750"));
    }

    @Test
    void theEarlierBidIsStillOnFile() {
        Board board = aBoard();
        board.post(HARVEST, CZ4, d("4.6000"), d("-0.2250"), "kbrock", QUOTED, TEN);
        assertThat(board.history(HARVEST)).hasSize(2);
    }

    @Test
    void theBoardCanBeAskedWhatItSaidAtAMoment() {
        Board board = aBoard();
        board.post(HARVEST, CZ4, d("4.6000"), d("-0.2250"), "kbrock", QUOTED, NOON);
        assertThat(board.asAt(HARVEST, TEN).orElseThrow().cash()).isEqualTo(d("4.3500"));
        assertThat(board.asAt(HARVEST, NOON).orElseThrow().cash()).isEqualTo(d("4.3750"));
    }

    @Test
    void nothingWasPostedBeforeTheFirstBid() {
        assertThat(aBoard().asAt(HARVEST, at(8))).isEmpty();
    }

    @Test
    void aWildMoveIsRefusedUnlessItIsConfirmed() {
        Board board = aBoard();
        assertThatThrownBy(() -> board.post(HARVEST, CZ4, d("45.7500"), d("-0.2250"), "kbrock", QUOTED, TEN))
                .isInstanceOf(ConflictError.class);
    }

    @Test
    void aConfirmedWildMoveGoesUp() {
        Board board = aBoard();
        Posting posting = board.post(HARVEST, CZ4, d("45.7500"), d("-0.2250"), "kbrock", QUOTED, TEN, true);
        assertThat(posting.cash()).isEqualTo(d("45.5250"));
    }

    @Test
    void anOrdinaryMoveNeedsNoConfirmation() {
        Board board = aBoard();
        Posting posting = board.post(HARVEST, CZ4, d("4.8000"), d("-0.2250"), "kbrock", QUOTED, TEN);
        assertThat(posting.cash()).isEqualTo(d("4.5750"));
    }

    @Test
    void theFirstBidOfASlotIsNeverGuarded() {
        Posting posting = new Board().post(HARVEST, CZ4, d("40.00"), d("0.00"), "kbrock", QUOTED);
        assertThat(posting.cash()).isEqualTo(d("40.0000"));
    }

    @Test
    void slotsAreListedInOrder() {
        Board board = aBoard();
        board.post(DEFERRED, CH5, d("4.7000"), d("-0.1500"), "kbrock", QUOTED);
        assertThat(board.slots()).extracting(Slot::period).containsExactly("harvest", "january");
    }

    @Test
    void theCurrentBoardHasOneRowPerSlot() {
        Board board = aBoard();
        board.post(HARVEST, CZ4, d("4.6000"), d("-0.2250"), "kbrock", QUOTED, TEN);
        board.post(DEFERRED, CH5, d("4.7000"), d("-0.1500"), "kbrock", QUOTED);
        Map<Slot, Posting> current = board.current();
        assertThat(current).hasSize(2);
        assertThat(current.get(HARVEST).cash()).isEqualTo(d("4.3750"));
    }

    @Test
    void theMoveSinceAMomentIsTheDifference() {
        Board board = aBoard();
        board.post(HARVEST, CZ4, d("4.6000"), d("-0.2250"), "kbrock", QUOTED, NOON);
        assertThat(board.moveSince(HARVEST, TEN)).isEqualTo(d("0.0250"));
    }

    @Test
    void theMoveOfAnUnpostedSlotIsRefused() {
        assertThatThrownBy(() -> new Board().moveSince(HARVEST, TEN)).isInstanceOf(NoBid.class);
    }

    @Test
    void aDaySheetRecordsTheOpenAndTheClose() {
        DaySheet sheet = new DaySheet(QUOTED);
        sheet.recordOpen(HARVEST, d("4.3500"));
        sheet.recordClose(HARVEST, d("4.4200"));
        assertThat(sheet.change(HARVEST)).isEqualTo(d("0.0700"));
    }

    @Test
    void aSlotCannotCloseWithoutOpening() {
        DaySheet sheet = new DaySheet(QUOTED);
        assertThatThrownBy(() -> sheet.recordClose(HARVEST, d("4.42"))).isInstanceOf(BoardError.class);
    }

    @Test
    void aSlotThatHasNotClosedHasNoChange() {
        DaySheet sheet = new DaySheet(QUOTED);
        sheet.recordOpen(HARVEST, d("4.3500"));
        assertThatThrownBy(() -> sheet.change(HARVEST)).isInstanceOf(NoBid.class);
    }

    @Test
    void theMoversAreTheSlotsThatMovedMost() {
        DaySheet sheet = new DaySheet(QUOTED);
        sheet.recordOpen(HARVEST, d("4.3500"));
        sheet.recordClose(HARVEST, d("4.4200"));
        sheet.recordOpen(DEFERRED, d("4.5500"));
        sheet.recordClose(DEFERRED, d("4.3000"));
        assertThat(sheet.movers(d("0.05")))
                .extracting(move -> move.slot().period())
                .containsExactly("january", "harvest");
    }

    @Test
    void aQuietDayHasNoMovers() {
        DaySheet sheet = new DaySheet(QUOTED);
        sheet.recordOpen(HARVEST, d("4.3500"));
        sheet.recordClose(HARVEST, d("4.3550"));
        assertThat(sheet.movers()).isEmpty();
    }
}
