from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.tickets import SplitRole, SplitShare
from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.repositories import PartyNotFound
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.parties import (
    NoSplitAgreement,
    PartyError,
    agreement_for,
    close_account,
    farms_for,
    file_agreement,
    open_account,
    set_exempt,
)
from bushelworks.services.scalehouse import record_load

OPENED = date(2024, 1, 15)
SEASON = date(2024, 3, 1)


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


@pytest.fixture
def filed(factory):
    with unit_of_work(factory) as uow:
        open_account(uow, "MERRICK", "Merrick Farms LLC", opened_on=OPENED)
        open_account(uow, "BROCKMAN", "Ellen Brockman", opened_on=OPENED)
        open_account(uow, "HOLLIS", "Hollis Brothers", opened_on=OPENED)
        uow.commit()
    return factory


def a_split():
    return [
        SplitShare("BROCKMAN", Decimal("25"), SplitRole.LANDLORD),
        SplitShare("MERRICK", Decimal("75"), SplitRole.TENANT),
    ]


def test_opening_an_account_puts_it_on_the_file(filed):
    with unit_of_work(filed) as uow:
        assert uow.parties.get("MERRICK").name == "Merrick Farms LLC"


def test_the_sort_name_is_upper_cased(filed):
    with unit_of_work(filed) as uow:
        assert uow.parties.get("MERRICK").sort_name == "MERRICK FARMS LLC"


def test_an_account_needs_a_code_and_a_name(factory):
    with unit_of_work(factory) as uow:
        with pytest.raises(PartyError):
            open_account(uow, "", "Somebody")
        with pytest.raises(PartyError):
            open_account(uow, "X", "")


def test_the_same_code_twice_is_a_conflict(filed):
    with unit_of_work(filed) as uow:
        with pytest.raises(ConflictError):
            open_account(uow, "MERRICK", "Somebody Else")


def test_a_missing_party_says_so(factory):
    with unit_of_work(factory) as uow:
        with pytest.raises(PartyNotFound):
            uow.parties.get("NOBODY")


def test_closing_an_account_takes_it_off_the_active_list(filed):
    with unit_of_work(filed) as uow:
        close_account(uow, "HOLLIS", date(2024, 12, 31))
        uow.commit()
    with unit_of_work(filed) as uow:
        assert [p.code for p in uow.parties.active()] == ["BROCKMAN", "MERRICK"]


def test_closing_twice_is_a_conflict(filed):
    with unit_of_work(filed) as uow:
        close_account(uow, "HOLLIS", date(2024, 12, 31))
        with pytest.raises(ConflictError):
            close_account(uow, "HOLLIS", date(2024, 12, 31))


def test_an_account_with_unsettled_tickets_cannot_close(filed, rules, corn_ticket):
    with unit_of_work(filed) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    with unit_of_work(filed) as uow:
        with pytest.raises(ConflictError) as caught:
            close_account(uow, "MERRICK", date(2024, 12, 31))
    assert "A-10441" in str(caught.value)


def test_a_voided_ticket_does_not_hold_an_account_open(filed, rules, corn_ticket):
    with unit_of_work(filed) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        uow.tickets.void("HOME", "A-10441", "reweighed")
        uow.commit()
    with unit_of_work(filed) as uow:
        close_account(uow, "MERRICK", date(2024, 12, 31))
        uow.commit()


def test_a_producer_can_be_marked_exempt(filed):
    with unit_of_work(filed) as uow:
        assert not set_exempt(uow, "MERRICK", False).checkoff_exempt
        assert set_exempt(uow, "MERRICK", True).checkoff_exempt
        uow.commit()


def test_filing_an_agreement_that_adds_up(filed):
    with unit_of_work(filed) as uow:
        agreement = file_agreement(uow, "HOME-PLACE", a_split(), SEASON)
        uow.commit()
    assert agreement.is_balanced()
    assert agreement.total_percent == Decimal("100")


def test_an_agreement_that_does_not_add_up_is_refused(filed):
    with unit_of_work(filed) as uow:
        with pytest.raises(PartyError):
            file_agreement(
                uow,
                "HOME-PLACE",
                [SplitShare("MERRICK", Decimal("60"))],
                SEASON,
            )


def test_an_agreement_needs_a_farm_and_a_share(filed):
    with unit_of_work(filed) as uow:
        with pytest.raises(PartyError):
            file_agreement(uow, "", a_split(), SEASON)
        with pytest.raises(PartyError):
            file_agreement(uow, "HOME-PLACE", [], SEASON)


def test_a_party_cannot_appear_twice_on_an_agreement(filed):
    with unit_of_work(filed) as uow:
        with pytest.raises(PartyError):
            file_agreement(
                uow,
                "HOME-PLACE",
                [
                    SplitShare("MERRICK", Decimal("50")),
                    SplitShare("MERRICK", Decimal("50")),
                ],
                SEASON,
            )


def test_an_agreement_naming_somebody_not_on_the_file_is_refused(filed):
    with unit_of_work(filed) as uow:
        with pytest.raises(PartyNotFound):
            file_agreement(
                uow,
                "HOME-PLACE",
                [
                    SplitShare("NOBODY", Decimal("50")),
                    SplitShare("MERRICK", Decimal("50")),
                ],
                SEASON,
            )


def test_the_agreement_in_force_is_the_one_that_reads_back(filed):
    with unit_of_work(filed) as uow:
        file_agreement(uow, "HOME-PLACE", a_split(), SEASON)
        uow.commit()
    with unit_of_work(filed) as uow:
        agreement = agreement_for(uow, "HOME-PLACE", date(2024, 10, 3))
    assert {share.party_id for share in agreement.shares} == {"BROCKMAN", "MERRICK"}
    assert agreement.is_balanced()


def test_nothing_is_in_force_before_the_agreement_starts(filed):
    with unit_of_work(filed) as uow:
        file_agreement(uow, "HOME-PLACE", a_split(), SEASON)
        uow.commit()
    with unit_of_work(filed) as uow:
        with pytest.raises(NoSplitAgreement):
            agreement_for(uow, "HOME-PLACE", date(2024, 1, 5))


def test_a_farm_with_no_agreement_says_so(filed):
    with unit_of_work(filed) as uow:
        with pytest.raises(NoSplitAgreement):
            agreement_for(uow, "NORTH-EIGHTY", date(2024, 10, 3))


def test_a_new_agreement_closes_the_old_one(filed):
    with unit_of_work(filed) as uow:
        file_agreement(uow, "HOME-PLACE", a_split(), SEASON)
        file_agreement(
            uow,
            "HOME-PLACE",
            [SplitShare("MERRICK", Decimal("100"))],
            date(2025, 3, 1),
        )
        uow.commit()
    with unit_of_work(filed) as uow:
        old = agreement_for(uow, "HOME-PLACE", date(2024, 10, 3))
        new = agreement_for(uow, "HOME-PLACE", date(2025, 10, 3))
    assert len(old.shares) == 2
    assert len(new.shares) == 1


def test_backdating_an_agreement_under_a_live_one_is_refused(filed):
    with unit_of_work(filed) as uow:
        file_agreement(uow, "HOME-PLACE", a_split(), date(2025, 3, 1))
        with pytest.raises(ConflictError):
            file_agreement(uow, "HOME-PLACE", a_split(), SEASON)


def test_the_farms_a_producer_has_a_share_of(filed):
    with unit_of_work(filed) as uow:
        file_agreement(uow, "HOME-PLACE", a_split(), SEASON)
        file_agreement(
            uow,
            "NORTH-EIGHTY",
            [SplitShare("MERRICK", Decimal("100"))],
            SEASON,
        )
        uow.commit()
    with unit_of_work(filed) as uow:
        assert farms_for(uow, "MERRICK") == ("HOME-PLACE", "NORTH-EIGHTY")
        assert farms_for(uow, "BROCKMAN") == ("HOME-PLACE",)
        assert farms_for(uow, "HOLLIS") == ()
