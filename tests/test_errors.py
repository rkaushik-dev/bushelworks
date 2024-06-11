import pytest

from bushelworks.domain.commodity import UnknownCommodity
from bushelworks.domain.contracts import ContractError
from bushelworks.domain.settlement import SettlementError
from bushelworks.domain.tickets import TicketError
from bushelworks.errors import (
    BushelworksError,
    ConfigurationError,
    ConflictError,
    LookupFailure,
    ValidationError,
)
from bushelworks.money import MoneyError
from bushelworks.schedules import NoScheduleInEffect
from bushelworks.units import UnitError


def test_every_deliberate_error_descends_from_the_root():
    for cls in (
        UnitError,
        MoneyError,
        TicketError,
        ContractError,
        SettlementError,
        UnknownCommodity,
        NoScheduleInEffect,
    ):
        assert issubclass(cls, BushelworksError)


def test_validation_errors_are_still_value_errors():
    assert issubclass(TicketError, ValueError)
    assert issubclass(ValidationError, ValueError)


def test_lookup_failures_are_still_key_errors():
    assert issubclass(UnknownCommodity, KeyError)
    assert issubclass(LookupFailure, KeyError)


def test_a_lookup_failure_reads_cleanly_rather_than_quoted():
    err = LookupFailure("no such producer: HOLLIS")
    assert str(err) == "no such producer: HOLLIS"


def test_a_subclass_that_names_no_code_gets_one_from_its_name():
    assert ContractError("x").code == "contract_error"
    assert TicketError("x").code == "ticket_error"
    assert UnknownCommodity("x").code == "unknown_commodity"
    assert NoScheduleInEffect("x").code == "no_schedule_in_effect"


def test_an_explicit_code_survives_the_derivation():
    assert LookupFailure("x").code == "not_found"
    assert SettlementError("x").code == "settlement_error"


def test_errors_carry_a_stable_code():
    assert ValidationError("bad").code == "validation_error"
    assert LookupFailure("gone").code == "not_found"
    assert ConflictError("busy").code == "conflict"
    assert ConfigurationError("nope").code == "configuration_error"


def test_a_code_can_be_overridden_per_instance():
    err = ValidationError("tare exceeds gross", code="tare_exceeds_gross")
    assert err.code == "tare_exceeds_gross"
    assert err.as_dict() == {
        "code": "tare_exceeds_gross",
        "message": "tare exceeds gross",
    }


def test_catching_the_root_catches_a_domain_error():
    with pytest.raises(BushelworksError):
        raise ContractError("F-1: no price")


def test_conflict_is_not_a_value_error():
    assert not issubclass(ConflictError, ValueError)
