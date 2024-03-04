from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.application import (
    Application,
    ApplicationError,
    ApplicationLedger,
    OverDelivery,
    SelectionRule,
    apply_delivery,
    eligible,
    order_for,
)
from bushelworks.domain.contracts import (
    ContractKind,
    DeliveryWindow,
    PurchaseContract,
)

DAY = date(2024, 10, 15)


def contract(number, bushels, closes, price, written=date(2024, 5, 1), **kw):
    return PurchaseContract(
        number=number,
        kind=ContractKind.FORWARD,
        party_id=kw.pop("party_id", "MERRICK"),
        commodity_code=kw.pop("commodity_code", "CORN"),
        location_code="HOME",
        written_on=written,
        bushels=Decimal(bushels),
        window=DeliveryWindow(date(2024, 9, 1), closes),
        flat_price=Decimal(price),
        **kw,
    )


EARLY = contract("F-01", "10000", date(2024, 10, 31), "4.50", written=date(2024, 6, 1))
LATE = contract("F-02", "10000", date(2024, 12, 31), "4.80", written=date(2024, 4, 1))


def test_ledger_starts_empty():
    ledger = ApplicationLedger()
    assert ledger.applied("F-01") == Decimal("0.0000")
    assert ledger.remaining(EARLY) == Decimal("10200.0000")
    assert not ledger.is_filled(EARLY)
    assert ledger.shortfall(EARLY) == Decimal("9800.0000")


def test_window_rule_fills_the_contract_closing_first():
    ledger = ApplicationLedger()
    applications = apply_delivery("A-1", Decimal("5000"), [LATE, EARLY], ledger, DAY)
    assert [a.contract_number for a in applications] == ["F-01"]
    assert ledger.applied("F-01") == Decimal("5000.0000")


def test_best_price_rule_fills_the_dearest_contract_first():
    ledger = ApplicationLedger()
    applications = apply_delivery(
        "A-1", Decimal("5000"), [EARLY, LATE], ledger, DAY, rule=SelectionRule.BEST_PRICE
    )
    assert [a.contract_number for a in applications] == ["F-02"]


def test_oldest_rule_fills_by_the_date_written():
    ledger = ApplicationLedger()
    applications = apply_delivery(
        "A-1", Decimal("5000"), [EARLY, LATE], ledger, DAY, rule=SelectionRule.OLDEST
    )
    assert [a.contract_number for a in applications] == ["F-02"]


def test_a_delivery_spills_onto_the_next_contract():
    ledger = ApplicationLedger()
    applications = apply_delivery("A-1", Decimal("12000"), [EARLY, LATE], ledger, DAY)
    assert [a.contract_number for a in applications] == ["F-01", "F-02"]
    assert applications[0].bushels == Decimal("10200.0000")
    assert applications[1].bushels == Decimal("1800.0000")


def test_the_tolerance_ceiling_is_what_a_contract_will_take():
    ledger = ApplicationLedger()
    apply_delivery("A-1", Decimal("10200"), [EARLY], ledger, DAY)
    assert ledger.remaining(EARLY) == Decimal("0.0000")


def test_overage_goes_to_storage_by_default():
    ledger = ApplicationLedger()
    applications = apply_delivery("A-1", Decimal("11000"), [EARLY], ledger, DAY)
    assert applications[-1].contract_number is None
    assert applications[-1].disposition == "store"
    assert applications[-1].bushels == Decimal("800.0000")


def test_overage_can_be_sold_spot_instead():
    ledger = ApplicationLedger()
    applications = apply_delivery(
        "A-1", Decimal("11000"), [EARLY], ledger, DAY, over_delivery=OverDelivery.SPOT
    )
    assert applications[-1].disposition == "spot"


def test_a_house_that_refuses_overage_says_so():
    ledger = ApplicationLedger()
    with pytest.raises(ApplicationError):
        apply_delivery(
            "A-1",
            Decimal("11000"),
            [EARLY],
            ledger,
            DAY,
            over_delivery=OverDelivery.REFUSE,
        )


def test_a_contract_is_filled_inside_its_tolerance():
    ledger = ApplicationLedger()
    apply_delivery("A-1", Decimal("9800"), [EARLY], ledger, DAY)
    assert ledger.is_filled(EARLY)
    assert ledger.shortfall(EARLY) == Decimal("0.0000")


def test_nothing_to_apply_is_an_error():
    ledger = ApplicationLedger()
    with pytest.raises(ApplicationError):
        apply_delivery("A-1", Decimal("0"), [EARLY], ledger, DAY)


def test_eligibility_filters_party_commodity_window_and_room():
    ledger = ApplicationLedger()
    other_party = contract("F-03", "5000", date(2024, 12, 31), "4.70", party_id="HOLLIS")
    other_crop = contract(
        "F-04", "5000", date(2024, 12, 31), "11.20", commodity_code="SOYB"
    )
    closed_window = contract("F-05", "5000", date(2024, 9, 30), "4.55")
    found = eligible(
        [EARLY, LATE, other_party, other_crop, closed_window],
        "CORN",
        "MERRICK",
        DAY,
        ledger,
    )
    assert [c.number for c in found] == ["F-01", "F-02"]


def test_a_full_contract_drops_out_of_eligibility():
    ledger = ApplicationLedger()
    apply_delivery("A-1", Decimal("10200"), [EARLY], ledger, DAY)
    found = eligible([EARLY, LATE], "CORN", "MERRICK", DAY, ledger)
    assert [c.number for c in found] == ["F-02"]


def test_a_cancelled_contract_is_not_eligible():
    ledger = ApplicationLedger()
    found = eligible([EARLY.cancel(), LATE], "CORN", "MERRICK", DAY, ledger)
    assert [c.number for c in found] == ["F-02"]


def test_an_unpriced_contract_sorts_last_under_best_price():
    unpriced = PurchaseContract(
        number="H-09",
        kind=ContractKind.HTA,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 4, 1),
        bushels=Decimal("5000"),
        window=DeliveryWindow(date(2024, 9, 1), date(2024, 12, 31)),
        futures_month=__import__(
            "bushelworks.domain.pricing", fromlist=["FuturesMonth"]
        ).FuturesMonth("CORN", "Z", 2024),
        futures_price=Decimal("4.85"),
    )
    ordered = order_for(SelectionRule.BEST_PRICE, [unpriced, EARLY, LATE])
    assert [c.number for c in ordered] == ["F-02", "F-01", "H-09"]


def test_an_application_of_nothing_is_refused():
    with pytest.raises(ApplicationError):
        Application(
            ticket_number="A-1",
            contract_number="F-01",
            bushels=Decimal("0"),
            applied_on=DAY,
        )


def test_ledger_reports_which_contracts_were_touched():
    ledger = ApplicationLedger()
    apply_delivery("A-1", Decimal("12000"), [EARLY, LATE], ledger, DAY)
    assert ledger.contracts_touched() == ("F-01", "F-02")
