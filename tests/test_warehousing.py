from datetime import date
from decimal import Decimal

import pytest

from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.houses import house_rulebook
from bushelworks.services.warehousing import (
    LotNotFound,
    StorageServiceError,
    accrue,
    cancel_receipt,
    issue_receipt,
    lot,
    on_hand,
    open_lots,
    store,
    withdraw,
)

STORED = date(2024, 10, 3)


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


@pytest.fixture
def house():
    return house_rulebook()


@pytest.fixture
def stocked(factory):
    with unit_of_work(factory) as uow:
        store(uow, "L-1", "MERRICK", "CORN", "HOME", Decimal("8000"), STORED)
        store(
            uow,
            "L-2",
            "MERRICK",
            "CORN",
            "HOME",
            Decimal("4000"),
            date(2024, 10, 20),
        )
        store(uow, "L-3", "HOLLIS", "CORN", "HOME", Decimal("2000"), STORED)
        uow.commit()
    return factory


def test_a_lot_opens_with_everything_still_in_it(stocked):
    with unit_of_work(stocked) as uow:
        assert lot(uow, "L-1").open_bushels == Decimal("8000.0000")


def test_a_missing_lot_says_so(stocked):
    with unit_of_work(stocked) as uow:
        with pytest.raises(LotNotFound):
            lot(uow, "L-99")


def test_opening_the_same_lot_twice_is_a_conflict(stocked):
    with unit_of_work(stocked) as uow:
        with pytest.raises(ConflictError):
            store(uow, "L-1", "MERRICK", "CORN", "HOME", Decimal("100"), STORED)


def test_a_lot_of_nothing_is_refused(factory):
    with unit_of_work(factory) as uow:
        with pytest.raises(StorageServiceError):
            store(uow, "L-9", "MERRICK", "CORN", "HOME", Decimal("0"), STORED)


def test_open_lots_are_filtered_by_producer(stocked):
    with unit_of_work(stocked) as uow:
        assert [row.lot_id for row in open_lots(uow, "MERRICK")] == ["L-1", "L-2"]
        assert [row.lot_id for row in open_lots(uow, "HOLLIS")] == ["L-3"]


def test_withdrawing_takes_the_oldest_lot_first(stocked):
    with unit_of_work(stocked) as uow:
        withdraw(uow, "MERRICK", "CORN", Decimal("9000"), date(2024, 11, 1))
        uow.commit()
    with unit_of_work(stocked) as uow:
        assert lot(uow, "L-1").open_bushels == Decimal("0.0000")
        assert lot(uow, "L-2").open_bushels == Decimal("3000.0000")


def test_an_emptied_lot_closes_itself(stocked):
    with unit_of_work(stocked) as uow:
        withdraw(uow, "MERRICK", "CORN", Decimal("8000"), date(2024, 11, 1))
        uow.commit()
    with unit_of_work(stocked) as uow:
        assert lot(uow, "L-1").closed_on == date(2024, 11, 1)
        assert [row.lot_id for row in open_lots(uow, "MERRICK")] == ["L-2"]


def test_withdrawing_more_than_is_there_is_refused(stocked):
    with unit_of_work(stocked) as uow:
        with pytest.raises(StorageServiceError):
            withdraw(uow, "MERRICK", "CORN", Decimal("12001"), date(2024, 11, 1))


def test_withdrawing_nothing_is_refused(stocked):
    with unit_of_work(stocked) as uow:
        with pytest.raises(StorageServiceError):
            withdraw(uow, "MERRICK", "CORN", Decimal("0"), date(2024, 11, 1))


def test_a_withdrawal_records_the_settlement_it_paid(stocked):
    with unit_of_work(stocked) as uow:
        written = withdraw(
            uow,
            "MERRICK",
            "CORN",
            Decimal("1000"),
            date(2024, 11, 1),
            settlement_number="S-2024-0417",
        )
        assert written[0].settlement_number == "S-2024-0417"


def test_what_is_on_hand_is_the_open_balance(stocked):
    with unit_of_work(stocked) as uow:
        assert on_hand(uow, "CORN") == Decimal("14000.0000")
        withdraw(uow, "MERRICK", "CORN", Decimal("2000"), date(2024, 11, 1))
        assert on_hand(uow, "CORN") == Decimal("12000.0000")


def test_the_first_accrual_charges_from_receipt(stocked, house):
    with unit_of_work(stocked) as uow:
        run = accrue(uow, house, date(2024, 12, 14))
        uow.commit()
    charged = {line.lot_id: line.dollars for line in run.lines}
    # 8000 bu, 72 days on hand, 30 of them free, so 42 days at 0.045 a month
    assert charged["L-1"] == Decimal("504.00")
    assert run.total > Decimal("0")


def test_a_lot_still_inside_its_free_period_accrues_nothing(stocked, house):
    with unit_of_work(stocked) as uow:
        run = accrue(uow, house, date(2024, 11, 1))
    charged = {line.lot_id: line.dollars for line in run.lines}
    assert charged["L-2"] == Decimal("0.00")


def test_running_the_accrual_twice_charges_nothing_the_second_time(stocked, house):
    with unit_of_work(stocked) as uow:
        accrue(uow, house, date(2024, 12, 14))
        uow.commit()
    with unit_of_work(stocked) as uow:
        second = accrue(uow, house, date(2024, 12, 14))
    assert second.lines == ()


def test_a_later_run_charges_only_the_time_since(stocked, house):
    with unit_of_work(stocked) as uow:
        accrue(uow, house, date(2024, 12, 14))
        uow.commit()
    with unit_of_work(stocked) as uow:
        second = accrue(uow, house, date(2025, 1, 13))
        uow.commit()
    charged = {line.lot_id: line.dollars for line in second.lines}
    assert charged["L-1"] == Decimal("360.00")


def test_the_accrual_accumulates_on_the_lot(stocked, house):
    with unit_of_work(stocked) as uow:
        accrue(uow, house, date(2024, 12, 14))
        uow.commit()
    with unit_of_work(stocked) as uow:
        accrue(uow, house, date(2025, 1, 13))
        uow.commit()
    with unit_of_work(stocked) as uow:
        assert lot(uow, "L-1").accrued_to_date == Decimal("864.00")
        assert lot(uow, "L-1").billed_through == date(2025, 1, 13)


def test_bushels_leaving_between_runs_do_not_reduce_what_was_billed(stocked, house):
    """The regression that made the house give storage away.

    Bill the lot, take half of it out, bill again. The second run has to charge
    the remaining balance for the new period. It used to compute the whole
    charge from receipt on the smaller balance, subtract what it had already
    billed on the larger one, get a negative number and floor it to nothing.
    """
    with unit_of_work(stocked) as uow:
        accrue(uow, house, date(2024, 12, 14))
        uow.commit()
    with unit_of_work(stocked) as uow:
        withdraw(uow, "MERRICK", "CORN", Decimal("4000"), date(2024, 12, 15))
        uow.commit()
    with unit_of_work(stocked) as uow:
        run = accrue(uow, house, date(2025, 1, 13))
        uow.commit()
    charged = {line.lot_id: line.dollars for line in run.lines}
    assert charged["L-1"] == Decimal("180.00")


def test_the_earlier_billing_is_still_on_the_lot_afterwards(stocked, house):
    with unit_of_work(stocked) as uow:
        accrue(uow, house, date(2024, 12, 14))
        uow.commit()
    with unit_of_work(stocked) as uow:
        withdraw(uow, "MERRICK", "CORN", Decimal("4000"), date(2024, 12, 15))
        accrue(uow, house, date(2025, 1, 13))
        uow.commit()
    with unit_of_work(stocked) as uow:
        assert lot(uow, "L-1").accrued_to_date == Decimal("684.00")


def test_a_dry_run_does_not_move_the_billed_through(stocked, house):
    with unit_of_work(stocked) as uow:
        accrue(uow, house, date(2024, 12, 14), record=False)
        uow.commit()
    with unit_of_work(stocked) as uow:
        assert lot(uow, "L-1").billed_through is None


def test_the_run_can_be_split_by_producer(stocked, house):
    with unit_of_work(stocked) as uow:
        run = accrue(uow, house, date(2024, 12, 14))
    assert {line.lot_id for line in run.for_party("MERRICK")} == {"L-1", "L-2"}
    assert {line.lot_id for line in run.for_party("HOLLIS")} == {"L-3"}


def test_a_receipt_is_written_against_an_open_lot(stocked):
    with unit_of_work(stocked) as uow:
        receipt = issue_receipt(uow, "WR-0001", "L-1", "2", date(2024, 10, 4))
        uow.commit()
    assert receipt.bushels == Decimal("8000.0000")
    assert receipt.is_live


def test_a_lot_carries_one_live_receipt_at_a_time(stocked):
    with unit_of_work(stocked) as uow:
        issue_receipt(uow, "WR-0001", "L-1", "2", date(2024, 10, 4))
        with pytest.raises(ConflictError):
            issue_receipt(uow, "WR-0002", "L-1", "2", date(2024, 10, 5))


def test_a_cancelled_receipt_frees_the_lot(stocked):
    with unit_of_work(stocked) as uow:
        issue_receipt(uow, "WR-0001", "L-1", "2", date(2024, 10, 4))
        cancel_receipt(uow, "WR-0001", date(2025, 3, 1))
        issue_receipt(uow, "WR-0002", "L-1", "2", date(2025, 3, 2))
        uow.commit()


def test_cancelling_twice_is_a_conflict(stocked):
    with unit_of_work(stocked) as uow:
        issue_receipt(uow, "WR-0001", "L-1", "2", date(2024, 10, 4))
        cancel_receipt(uow, "WR-0001", date(2025, 3, 1))
        with pytest.raises(ConflictError):
            cancel_receipt(uow, "WR-0001", date(2025, 3, 2))


def test_an_empty_lot_cannot_carry_a_receipt(stocked):
    with unit_of_work(stocked) as uow:
        withdraw(uow, "HOLLIS", "CORN", Decimal("2000"), date(2024, 11, 1))
        with pytest.raises(StorageServiceError):
            issue_receipt(uow, "WR-0003", "L-3", "2", date(2024, 11, 2))
