from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.contracts import ContractKind, DeliveryWindow, PurchaseContract
from bushelworks.errors import ConflictError, LookupFailure
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.audit import Document, for_document
from bushelworks.services.corrections import (
    CorrectionError,
    reversals_for,
    reverse_settlement,
    unapply,
    void_and_reissue,
)
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer

SETTLED = date(2024, 10, 20)
SPOT = Decimal("4.3500")
REVERSED = date(2024, 10, 22)


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def a_contract(bushels="20000"):
    return PurchaseContract(
        number="F-2024-118",
        kind=ContractKind.FORWARD,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal(bushels),
        window=DeliveryWindow(date(2024, 9, 1), date(2024, 11, 30)),
        flat_price=Decimal("4.6200"),
    )


@pytest.fixture
def applied(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract())
        record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    return factory


def test_unapplying_takes_the_bushels_back(applied):
    with unit_of_work(applied) as uow:
        before = uow.contracts.applied_bushels("F-2024-118")
        result = unapply(
            uow, "HOME", "A-10441", "F-2024-118", REVERSED, "wrong contract"
        )
        uow.commit()
    assert result.bushels == before
    with unit_of_work(applied) as uow:
        assert uow.contracts.applied_bushels("F-2024-118") == Decimal("0")


def test_the_ticket_is_untouched_by_an_unapply(applied):
    with unit_of_work(applied) as uow:
        unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "wrong contract")
        uow.commit()
    with unit_of_work(applied) as uow:
        row = uow.tickets.row("HOME", "A-10441")
        assert not row.voided
        assert row.net_pounds == Decimal("52240")


def test_unapplying_twice_finds_nothing_to_reverse(applied):
    with unit_of_work(applied) as uow:
        unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "wrong contract")
        with pytest.raises(LookupFailure):
            unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "again")


def test_an_unapply_needs_a_reason(applied):
    with unit_of_work(applied) as uow:
        with pytest.raises(CorrectionError):
            unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "")


def test_a_settled_ticket_cannot_be_unapplied(applied, rules):
    with unit_of_work(applied) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(applied) as uow:
        with pytest.raises(ConflictError):
            unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "too late")


def test_a_filled_contract_reopens_when_a_ticket_comes_off(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract(bushels="500"))
        record_load(uow, corn_ticket, "MERRICK", rules)
        uow.contracts.close("F-2024-118")
        uow.commit()
    with unit_of_work(factory) as uow:
        unapply(uow, "HOME", "A-10441", "F-2024-118", REVERSED, "wrong contract")
        uow.commit()
    with unit_of_work(factory) as uow:
        assert uow.contracts.row("F-2024-118").status == "open"


def test_an_unapply_leaves_an_audit_entry(applied):
    with unit_of_work(applied) as uow:
        unapply(
            uow, "HOME", "A-10441", "F-2024-118", REVERSED, "driver named the wrong one",
            actor="office",
        )
        uow.commit()
    with unit_of_work(applied) as uow:
        entries = for_document(uow, Document.CONTRACT, "F-2024-118")
    assert entries[-1].action == "reversed"
    assert "wrong one" in entries[-1].detail


def test_reissuing_writes_a_replacement_and_voids_the_original(applied):
    with unit_of_work(applied) as uow:
        replacement = void_and_reissue(
            uow,
            "HOME",
            "A-10441",
            "A-10441R",
            Decimal("78000"),
            Decimal("26180"),
            "scale head was reading light",
        )
        uow.commit()
    assert replacement.net_pounds == Decimal("51820")
    with unit_of_work(applied) as uow:
        assert uow.tickets.row("HOME", "A-10441").voided
        assert not uow.tickets.row("HOME", "A-10441R").voided


def test_the_replacement_carries_the_sample_and_the_splits(applied):
    with unit_of_work(applied) as uow:
        void_and_reissue(
            uow,
            "HOME",
            "A-10441",
            "A-10441R",
            Decimal("78000"),
            Decimal("26180"),
            "reweighed",
        )
        uow.commit()
    with unit_of_work(applied) as uow:
        row = uow.tickets.row("HOME", "A-10441R")
        assert len(row.samples) == 5
        assert row.splits[0].party_code == "MERRICK"
        assert "reissue of A-10441" in row.remarks


def test_a_reissue_needs_a_reason(applied):
    with unit_of_work(applied) as uow:
        with pytest.raises(CorrectionError):
            void_and_reissue(
                uow, "HOME", "A-10441", "A-10441R", Decimal("78000"), Decimal("26180"), ""
            )


def test_a_reissue_with_a_tare_above_gross_is_refused(applied):
    with unit_of_work(applied) as uow:
        with pytest.raises(CorrectionError):
            void_and_reissue(
                uow,
                "HOME",
                "A-10441",
                "A-10441R",
                Decimal("26000"),
                Decimal("26180"),
                "reweighed",
            )


def test_reissuing_onto_an_existing_number_is_a_conflict(applied, corn_ticket, rules):
    from dataclasses import replace

    with unit_of_work(applied) as uow:
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "MERRICK", rules
        )
        uow.commit()
    with unit_of_work(applied) as uow:
        with pytest.raises(ConflictError):
            void_and_reissue(
                uow,
                "HOME",
                "A-10441",
                "A-10442",
                Decimal("78000"),
                Decimal("26180"),
                "reweighed",
            )


def test_a_settled_ticket_cannot_be_reissued(applied, rules):
    with unit_of_work(applied) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(applied) as uow:
        with pytest.raises(ConflictError):
            void_and_reissue(
                uow,
                "HOME",
                "A-10441",
                "A-10441R",
                Decimal("78000"),
                Decimal("26180"),
                "too late",
            )


def test_reversing_a_settlement_turns_the_sign_over(applied, rules):
    with unit_of_work(applied) as uow:
        original = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(applied) as uow:
        reversal = reverse_settlement(uow, original.number, REVERSED, "priced wrong")
        uow.commit()
    assert reversal.net == -original.net
    assert reversal.number.startswith("SR-")


def test_the_original_is_marked_voided_rather_than_deleted(applied, rules):
    with unit_of_work(applied) as uow:
        original = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(applied) as uow:
        reverse_settlement(uow, original.number, REVERSED, "priced wrong")
        uow.commit()
    with unit_of_work(applied) as uow:
        assert uow.settlements.row(original.number).voided


def test_reversing_frees_the_tickets_to_settle_again(applied, rules):
    with unit_of_work(applied) as uow:
        original = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(applied) as uow:
        reverse_settlement(uow, original.number, REVERSED, "priced wrong")
        uow.commit()
    with unit_of_work(applied) as uow:
        waiting = uow.tickets.awaiting_settlement("HOME")
        assert [row.ticket_number for row in waiting] == ["A-10441"]


def test_reversing_twice_is_a_conflict(applied, rules):
    with unit_of_work(applied) as uow:
        original = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(applied) as uow:
        reverse_settlement(uow, original.number, REVERSED, "priced wrong")
        uow.commit()
    with unit_of_work(applied) as uow:
        with pytest.raises(ConflictError):
            reverse_settlement(uow, original.number, REVERSED, "again")


def test_a_paid_settlement_cannot_be_reversed_here(applied, rules):
    with unit_of_work(applied) as uow:
        original = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.settlements.mark_paid(original.number, "010441", date(2024, 10, 21))
        uow.commit()
    with unit_of_work(applied) as uow:
        with pytest.raises(ConflictError) as caught:
            reverse_settlement(uow, original.number, REVERSED, "priced wrong")
    assert "010441" in str(caught.value)


def test_a_reversal_needs_a_reason(applied, rules):
    with unit_of_work(applied) as uow:
        original = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        with pytest.raises(CorrectionError):
            reverse_settlement(uow, original.number, REVERSED, "")


def test_reversals_are_listed_for_a_producer(applied, rules):
    with unit_of_work(applied) as uow:
        original = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(applied) as uow:
        reverse_settlement(uow, original.number, REVERSED, "priced wrong")
        uow.commit()
    with unit_of_work(applied) as uow:
        numbers = [row.number for row in reversals_for(uow, "MERRICK")]
    assert len(numbers) == 1
    assert numbers[0].startswith("SR-")
