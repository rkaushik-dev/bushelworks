from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.contracts import (
    ContractError,
    ContractKind,
    ContractStatus,
    DeliveryWindow,
    PurchaseContract,
)
from bushelworks.domain.pricing import FuturesMonth

WINDOW = DeliveryWindow(date(2024, 10, 1), date(2024, 11, 30))
CZ4 = FuturesMonth("CORN", "Z", 2024)
CH5 = FuturesMonth("CORN", "H", 2025)


def forward(**overrides) -> PurchaseContract:
    base = dict(
        number="F-2024-118",
        kind=ContractKind.FORWARD,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal("20000"),
        window=WINDOW,
        flat_price=Decimal("4.6200"),
    )
    base.update(overrides)
    return PurchaseContract(**base)


def hta(**overrides) -> PurchaseContract:
    base = dict(
        number="H-2024-042",
        kind=ContractKind.HTA,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal("10000"),
        window=WINDOW,
        futures_month=CZ4,
        futures_price=Decimal("4.8500"),
    )
    base.update(overrides)
    return PurchaseContract(**base)


def test_delivery_window_spans_inclusive_days():
    assert WINDOW.contains(date(2024, 10, 1))
    assert WINDOW.contains(date(2024, 11, 30))
    assert not WINDOW.contains(date(2024, 9, 30))
    assert WINDOW.days == 61


def test_a_window_cannot_close_before_it_opens():
    with pytest.raises(ContractError):
        DeliveryWindow(date(2024, 11, 30), date(2024, 10, 1))


def test_a_forward_is_priced_the_day_it_is_written():
    contract = forward()
    assert contract.is_fully_priced
    assert contract.unit_price == Decimal("4.6200")


def test_a_forward_without_a_price_is_refused():
    with pytest.raises(ContractError):
        forward(flat_price=None)


def test_an_hta_is_not_priced_until_the_basis_is_set():
    contract = hta()
    assert not contract.is_fully_priced
    with pytest.raises(ContractError):
        contract.unit_price


def test_setting_the_basis_prices_an_hta():
    contract = hta().set_basis(Decimal("-0.2500"), date(2024, 10, 15))
    assert contract.is_fully_priced
    assert contract.unit_price == Decimal("4.6000")


def test_the_basis_can_only_be_set_once():
    contract = hta().set_basis(Decimal("-0.2500"), date(2024, 10, 15))
    with pytest.raises(ContractError):
        contract.set_basis(Decimal("-0.1500"), date(2024, 10, 20))


def test_the_basis_cannot_be_set_after_the_window_closes():
    with pytest.raises(ContractError):
        hta().set_basis(Decimal("-0.2500"), date(2024, 12, 15))


def test_only_an_hta_sets_a_basis():
    with pytest.raises(ContractError):
        forward().set_basis(Decimal("-0.25"), date(2024, 10, 15))


def test_a_basis_contract_prices_when_the_futures_are_fixed():
    contract = PurchaseContract(
        number="B-2024-009",
        kind=ContractKind.BASIS,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal("5000"),
        window=WINDOW,
        futures_month=CZ4,
        basis=Decimal("-0.3000"),
    )
    assert not contract.is_fully_priced
    priced = contract.set_futures(Decimal("4.7000"), date(2024, 11, 1))
    assert priced.unit_price == Decimal("4.4000")


def test_only_a_basis_contract_sets_futures():
    with pytest.raises(ContractError):
        hta().set_futures(Decimal("4.70"), date(2024, 11, 1))


def test_a_deferred_price_contract_carries_no_price():
    contract = PurchaseContract(
        number="D-2024-003",
        kind=ContractKind.DEFERRED,
        party_id="HOLLIS",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 10, 5),
        bushels=Decimal("8000"),
        window=WINDOW,
        service_rate_per_bushel=Decimal("0.0050"),
    )
    assert not contract.is_fully_priced
    with pytest.raises(ContractError):
        PurchaseContract(
            number="D-2024-004",
            kind=ContractKind.DEFERRED,
            party_id="HOLLIS",
            commodity_code="CORN",
            location_code="HOME",
            written_on=date(2024, 10, 5),
            bushels=Decimal("8000"),
            window=WINDOW,
            flat_price=Decimal("4.50"),
        )


def test_service_charge_rounds_up_to_a_whole_month():
    contract = PurchaseContract(
        number="D-2024-003",
        kind=ContractKind.DEFERRED,
        party_id="HOLLIS",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 10, 5),
        bushels=Decimal("8000"),
        window=WINDOW,
        service_rate_per_bushel=Decimal("0.0050"),
    )
    assert contract.service_charge(Decimal("8000"), 31) == Decimal("80.00")
    assert contract.service_charge(Decimal("8000"), 30) == Decimal("40.00")
    assert contract.service_charge(Decimal("8000"), 0) == Decimal("0.00")


def test_tolerance_gives_a_floor_and_a_ceiling():
    contract = forward(tolerance_percent=Decimal("5"))
    assert contract.minimum_bushels == Decimal("19000.0000")
    assert contract.maximum_bushels == Decimal("21000.0000")


def test_tolerance_outside_a_quarter_is_refused():
    with pytest.raises(ContractError):
        forward(tolerance_percent=Decimal("40"))


def test_zero_bushels_is_not_a_contract():
    with pytest.raises(ContractError):
        forward(bushels=Decimal("0"))


def test_a_futures_month_of_the_wrong_commodity_is_refused():
    with pytest.raises(ContractError):
        hta(futures_month=FuturesMonth("SOYB", "X", 2024))


def test_rolling_forward_adds_the_spread():
    rolled = hta().roll_to(CH5, Decimal("0.1200"))
    assert rolled.futures_month == CH5
    assert rolled.futures_price == Decimal("4.9700")


def test_rolling_backward_is_refused():
    contract = hta(futures_month=CH5, futures_price=Decimal("4.97"))
    with pytest.raises(ContractError):
        contract.roll_to(CZ4, Decimal("-0.12"))


def test_delivery_is_only_accepted_inside_the_window_while_open():
    contract = forward()
    assert contract.accepts_delivery_on(date(2024, 10, 15))
    assert not contract.accepts_delivery_on(date(2024, 12, 15))
    assert not contract.cancel().accepts_delivery_on(date(2024, 10, 15))


def test_a_cancelled_contract_cannot_be_cancelled_again():
    contract = forward().cancel()
    assert contract.status is ContractStatus.CANCELLED
    with pytest.raises(ContractError):
        contract.cancel()
