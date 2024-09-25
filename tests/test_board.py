from datetime import date, datetime, timezone
from decimal import Decimal

import pytest

from bushelworks.domain.pricing import FuturesMonth, PricingError
from bushelworks.errors import ConflictError
from bushelworks.services.board import (
    Board,
    BoardError,
    DaySheet,
    NoBid,
    Slot,
)

CZ4 = FuturesMonth("CORN", "Z", 2024)
CH5 = FuturesMonth("CORN", "H", 2025)
HARVEST = Slot("CORN", "harvest", "HOME")
DEFERRED = Slot("CORN", "january", "HOME")
QUOTED = date(2024, 10, 3)

NINE = datetime(2024, 10, 3, 9, 0, tzinfo=timezone.utc)
TEN = datetime(2024, 10, 3, 10, 0, tzinfo=timezone.utc)
NOON = datetime(2024, 10, 3, 12, 0, tzinfo=timezone.utc)


def a_board() -> Board:
    board = Board()
    board.post(
        HARVEST,
        CZ4,
        Decimal("4.5750"),
        Decimal("-0.2250"),
        "kbrock",
        QUOTED,
        posted_at=NINE,
    )
    return board


def test_a_slot_needs_a_commodity_and_a_period():
    with pytest.raises(BoardError):
        Slot("", "harvest")
    with pytest.raises(BoardError):
        Slot("CORN", "")


def test_a_slot_labels_itself():
    assert HARVEST.label == "CORN harvest HOME"


def test_a_posted_bid_is_futures_plus_basis():
    posting = a_board().require(HARVEST)
    assert posting.cash == Decimal("4.3500")
    assert posting.basis == Decimal("-0.2250")
    assert posting.posted_by == "kbrock"


def test_a_bid_needs_somebodys_name_on_it():
    board = Board()
    with pytest.raises(BoardError):
        board.post(HARVEST, CZ4, Decimal("4.57"), Decimal("-0.22"), "", QUOTED)


def test_a_month_of_the_wrong_commodity_is_refused():
    board = Board()
    with pytest.raises(PricingError):
        board.post(
            Slot("SOYB", "harvest"),
            CZ4,
            Decimal("11.00"),
            Decimal("-0.30"),
            "kbrock",
            QUOTED,
        )


def test_nothing_posted_for_a_slot_says_so():
    with pytest.raises(NoBid):
        Board().require(HARVEST)
    assert Board().latest(HARVEST) is None


def test_the_latest_bid_replaces_the_earlier_one():
    board = a_board()
    board.post(
        HARVEST,
        CZ4,
        Decimal("4.6000"),
        Decimal("-0.2250"),
        "kbrock",
        QUOTED,
        posted_at=TEN,
    )
    assert board.require(HARVEST).cash == Decimal("4.3750")


def test_the_earlier_bid_is_still_on_file():
    board = a_board()
    board.post(
        HARVEST,
        CZ4,
        Decimal("4.6000"),
        Decimal("-0.2250"),
        "kbrock",
        QUOTED,
        posted_at=TEN,
    )
    assert len(board.history(HARVEST)) == 2


def test_the_board_can_be_asked_what_it_said_at_a_moment():
    board = a_board()
    board.post(
        HARVEST,
        CZ4,
        Decimal("4.6000"),
        Decimal("-0.2250"),
        "kbrock",
        QUOTED,
        posted_at=NOON,
    )
    assert board.as_at(HARVEST, TEN).cash == Decimal("4.3500")
    assert board.as_at(HARVEST, NOON).cash == Decimal("4.3750")


def test_nothing_was_posted_before_the_first_bid():
    board = a_board()
    assert board.as_at(HARVEST, datetime(2024, 10, 3, 8, tzinfo=timezone.utc)) is None


def test_a_wild_move_is_refused_unless_it_is_confirmed():
    board = a_board()
    with pytest.raises(ConflictError):
        board.post(
            HARVEST,
            CZ4,
            Decimal("45.7500"),
            Decimal("-0.2250"),
            "kbrock",
            QUOTED,
            posted_at=TEN,
        )


def test_a_confirmed_wild_move_goes_up():
    board = a_board()
    posting = board.post(
        HARVEST,
        CZ4,
        Decimal("45.7500"),
        Decimal("-0.2250"),
        "kbrock",
        QUOTED,
        posted_at=TEN,
        confirm_move=True,
    )
    assert posting.cash == Decimal("45.5250")


def test_an_ordinary_move_needs_no_confirmation():
    board = a_board()
    posting = board.post(
        HARVEST,
        CZ4,
        Decimal("4.8000"),
        Decimal("-0.2250"),
        "kbrock",
        QUOTED,
        posted_at=TEN,
    )
    assert posting.cash == Decimal("4.5750")


def test_the_first_bid_of_a_slot_is_never_guarded():
    board = Board()
    posting = board.post(
        HARVEST, CZ4, Decimal("40.00"), Decimal("0.00"), "kbrock", QUOTED
    )
    assert posting.cash == Decimal("40.0000")


def test_slots_are_listed_in_order():
    board = a_board()
    board.post(
        DEFERRED, CH5, Decimal("4.7000"), Decimal("-0.1500"), "kbrock", QUOTED
    )
    assert [slot.period for slot in board.slots()] == ["harvest", "january"]


def test_the_current_board_has_one_row_per_slot():
    board = a_board()
    board.post(
        HARVEST, CZ4, Decimal("4.6000"), Decimal("-0.2250"), "kbrock", QUOTED, posted_at=TEN
    )
    board.post(
        DEFERRED, CH5, Decimal("4.7000"), Decimal("-0.1500"), "kbrock", QUOTED
    )
    current = board.current()
    assert len(current) == 2
    assert current[HARVEST].cash == Decimal("4.3750")


def test_the_move_since_a_moment_is_the_difference():
    board = a_board()
    board.post(
        HARVEST, CZ4, Decimal("4.6000"), Decimal("-0.2250"), "kbrock", QUOTED, posted_at=NOON
    )
    assert board.move_since(HARVEST, TEN) == Decimal("0.0250")


def test_the_move_of_an_unposted_slot_is_refused():
    with pytest.raises(NoBid):
        Board().move_since(HARVEST, TEN)


def test_a_day_sheet_records_the_open_and_the_close():
    sheet = DaySheet(day=QUOTED)
    sheet.record_open(HARVEST, Decimal("4.3500"))
    sheet.record_close(HARVEST, Decimal("4.4200"))
    assert sheet.change(HARVEST) == Decimal("0.0700")


def test_a_slot_cannot_close_without_opening():
    sheet = DaySheet(day=QUOTED)
    with pytest.raises(BoardError):
        sheet.record_close(HARVEST, Decimal("4.42"))


def test_a_slot_that_has_not_closed_has_no_change():
    sheet = DaySheet(day=QUOTED)
    sheet.record_open(HARVEST, Decimal("4.3500"))
    with pytest.raises(NoBid):
        sheet.change(HARVEST)


def test_the_movers_are_the_slots_that_moved_most():
    sheet = DaySheet(day=QUOTED)
    sheet.record_open(HARVEST, Decimal("4.3500"))
    sheet.record_close(HARVEST, Decimal("4.4200"))
    sheet.record_open(DEFERRED, Decimal("4.5500"))
    sheet.record_close(DEFERRED, Decimal("4.3000"))
    movers = sheet.movers(threshold=Decimal("0.05"))
    assert [slot.period for slot, _ in movers] == ["january", "harvest"]


def test_a_quiet_day_has_no_movers():
    sheet = DaySheet(day=QUOTED)
    sheet.record_open(HARVEST, Decimal("4.3500"))
    sheet.record_close(HARVEST, Decimal("4.3550"))
    assert sheet.movers() == []
