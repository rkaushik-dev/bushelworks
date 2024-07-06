from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import inspect, select
from sqlalchemy.exc import IntegrityError

from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import (
    StorageLotRow,
    WarehouseReceiptRow,
    WithdrawalRow,
)


@pytest.fixture
def make_session():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def a_lot(**overrides) -> StorageLotRow:
    base = dict(
        lot_id="L-2024-0031",
        party_code="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        bushels=Decimal("8000"),
        stored_on=date(2024, 10, 3),
    )
    base.update(overrides)
    return StorageLotRow(**base)


def test_the_storage_tables_exist(make_session):
    engine = make_session.kw["bind"]
    names = set(inspect(engine).get_table_names())
    assert {"storage_lot", "storage_withdrawal", "warehouse_receipt"} <= names


def test_a_lot_round_trips(make_session):
    with make_session() as session:
        session.add(a_lot())
        session.commit()
    with make_session() as session:
        found = session.scalar(select(StorageLotRow))
        assert found.open_bushels == Decimal("8000.0000")
        assert found.accrued_to_date == Decimal("0.00")
        assert not found.is_deferred_price
        assert str(found) == "L-2024-0031 MERRICK CORN"


def test_a_lot_id_is_unique(make_session):
    with make_session() as session:
        session.add(a_lot())
        session.commit()
    with make_session() as session:
        session.add(a_lot())
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_lot_of_no_bushels_is_refused(make_session):
    with make_session() as session:
        session.add(a_lot(bushels=Decimal("0")))
        with pytest.raises(IntegrityError):
            session.commit()


def test_withdrawals_come_off_the_open_balance(make_session):
    with make_session() as session:
        lot = a_lot()
        lot.withdrawals = [
            WithdrawalRow(bushels=Decimal("1200"), withdrawn_on=date(2024, 11, 1)),
            WithdrawalRow(bushels=Decimal("800"), withdrawn_on=date(2024, 12, 1)),
        ]
        session.add(lot)
        session.commit()
    with make_session() as session:
        found = session.scalar(select(StorageLotRow))
        assert found.withdrawn == Decimal("2000.0000")
        assert found.open_bushels == Decimal("6000.0000")


def test_a_withdrawal_of_nothing_is_refused(make_session):
    with make_session() as session:
        lot = a_lot()
        lot.withdrawals = [
            WithdrawalRow(bushels=Decimal("0"), withdrawn_on=date(2024, 11, 1))
        ]
        session.add(lot)
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_withdrawal_records_why_and_against_which_settlement(make_session):
    with make_session() as session:
        lot = a_lot()
        lot.withdrawals = [
            WithdrawalRow(
                bushels=Decimal("1200"),
                withdrawn_on=date(2024, 11, 1),
                reason="settled",
                settlement_number="S-2024-0417",
            )
        ]
        session.add(lot)
        session.commit()
    with make_session() as session:
        withdrawal = session.scalar(select(WithdrawalRow))
        assert withdrawal.settlement_number == "S-2024-0417"
        assert withdrawal.reason == "settled"


def test_deleting_a_lot_takes_its_withdrawals(make_session):
    with make_session() as session:
        lot = a_lot()
        lot.withdrawals = [
            WithdrawalRow(bushels=Decimal("100"), withdrawn_on=date(2024, 11, 1))
        ]
        session.add(lot)
        session.commit()
        session.delete(lot)
        session.commit()
    with make_session() as session:
        assert session.scalars(select(WithdrawalRow)).all() == []


def test_a_receipt_is_live_until_it_is_cancelled(make_session):
    with make_session() as session:
        lot = a_lot()
        session.add(lot)
        session.flush()
        session.add(
            WarehouseReceiptRow(
                receipt_number="WR-004417",
                lot_row_id=lot.id,
                party_code="MERRICK",
                commodity_code="CORN",
                location_code="HOME",
                bushels=Decimal("8000"),
                grade="2",
                issued_on=date(2024, 10, 4),
            )
        )
        session.commit()
    with make_session() as session:
        receipt = session.scalar(select(WarehouseReceiptRow))
        assert receipt.is_live
        receipt.cancelled_on = date(2025, 3, 1)
        session.commit()
        assert not receipt.is_live


def test_a_receipt_number_is_unique(make_session):
    with make_session() as session:
        lot = a_lot()
        session.add(lot)
        session.flush()
        for _ in range(2):
            session.add(
                WarehouseReceiptRow(
                    receipt_number="WR-004417",
                    lot_row_id=lot.id,
                    party_code="MERRICK",
                    commodity_code="CORN",
                    location_code="HOME",
                    bushels=Decimal("8000"),
                    grade="2",
                    issued_on=date(2024, 10, 4),
                )
            )
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_receipt_can_carry_a_lienholder(make_session):
    with make_session() as session:
        lot = a_lot()
        session.add(lot)
        session.flush()
        session.add(
            WarehouseReceiptRow(
                receipt_number="WR-004418",
                lot_row_id=lot.id,
                party_code="MERRICK",
                commodity_code="CORN",
                location_code="HOME",
                bushels=Decimal("8000"),
                grade="2",
                issued_on=date(2024, 10, 4),
                lienholder="Farmers State Bank",
            )
        )
        session.commit()
    with make_session() as session:
        assert session.scalar(select(WarehouseReceiptRow)).lienholder == (
            "Farmers State Bank"
        )
