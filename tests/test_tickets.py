from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain import commodity as cm
from bushelworks.domain.tickets import (
    Disposition,
    ScaleTicket,
    SplitRole,
    SplitShare,
    TicketError,
    split_bushels,
)


def ticket(**overrides) -> ScaleTicket:
    base = dict(
        ticket_number="A-10441",
        scale_date=date(2024, 10, 3),
        location_code="HOME",
        commodity_code="CORN",
        gross_pounds=Decimal("78420"),
        tare_pounds=Decimal("26180"),
        disposition=Disposition.CASH_SALE,
        splits=[SplitShare("MERRICK", Decimal("100"))],
        sample={
            cm.Factor.TEST_WEIGHT: Decimal("56.5"),
            cm.Factor.MOISTURE: Decimal("16.4"),
            cm.Factor.BCFM: Decimal("1.2"),
            cm.Factor.DAMAGE_TOTAL: Decimal("1.0"),
        },
    )
    base.update(overrides)
    return ScaleTicket(**base)


def test_net_weight_is_gross_less_tare():
    assert ticket().net_pounds == Decimal("52240")


def test_no_dockage_leaves_the_clean_weight_alone():
    t = ticket()
    assert t.dockage_pounds == Decimal("0")
    assert t.clean_pounds == t.net_pounds


def test_dockage_comes_off_before_bushels():
    t = ticket(commodity_code="HRW", dockage_percent=Decimal("1.5"))
    assert t.dockage_pounds == Decimal("784")
    assert t.clean_pounds == Decimal("51456")


def test_gross_bushels_ignore_dockage_and_clean_bushels_do_not():
    t = ticket(dockage_percent=Decimal("1.5"))
    assert t.gross_bushels(cm.CORN) == Decimal("932.8571")
    assert t.clean_bushels(cm.CORN) == Decimal("918.8571")


def test_shares_have_to_total_the_whole_load():
    with pytest.raises(TicketError):
        ticket(
            splits=[
                SplitShare("MERRICK", Decimal("60")),
                SplitShare("HOLLIS", Decimal("30")),
            ]
        )


def test_a_party_cannot_appear_twice_on_the_split():
    with pytest.raises(TicketError):
        ticket(
            splits=[
                SplitShare("MERRICK", Decimal("50")),
                SplitShare("MERRICK", Decimal("50")),
            ]
        )


def test_a_share_of_zero_is_refused():
    with pytest.raises(TicketError):
        SplitShare("MERRICK", Decimal("0"))


def test_crop_share_lease_splits_two_ways():
    t = ticket(
        splits=[
            SplitShare("BROCKMAN", Decimal("25"), SplitRole.LANDLORD),
            SplitShare("MERRICK", Decimal("75"), SplitRole.TENANT),
        ]
    )
    assert t.parties() == ("BROCKMAN", "MERRICK")
    assert t.share_of("BROCKMAN") == Decimal("25")


def test_asking_for_a_share_nobody_holds_is_an_error():
    with pytest.raises(TicketError):
        ticket().share_of("HOLLIS")


def test_contract_delivery_needs_a_contract_number():
    with pytest.raises(TicketError):
        ticket(disposition=Disposition.CONTRACT)
    assert (
        ticket(disposition=Disposition.CONTRACT, contract_number="F-2024-118")
        .contract_number
        == "F-2024-118"
    )


def test_tare_above_gross_is_refused():
    with pytest.raises(Exception):
        ticket(gross_pounds=Decimal("26180"), tare_pounds=Decimal("78420")).net_pounds


def test_dockage_out_of_range_is_refused():
    with pytest.raises(TicketError):
        ticket(dockage_percent=Decimal("-1"))
    with pytest.raises(TicketError):
        ticket(dockage_percent=Decimal("100"))


def test_factor_lookup_and_requirement():
    t = ticket()
    assert t.factor(cm.Factor.MOISTURE) == Decimal("16.4")
    assert t.factor(cm.Factor.PROTEIN) is None
    assert t.require_factor(cm.Factor.BCFM) == Decimal("1.2")
    with pytest.raises(TicketError):
        t.require_factor(cm.Factor.PROTEIN)


def test_void_keeps_the_weights_and_carries_the_reason():
    voided = ticket().void("reweighed, driver had the wrong tare")
    assert voided.voided
    assert voided.net_pounds == Decimal("52240")
    assert voided.remarks.startswith("VOID: ")


def test_a_ticket_cannot_be_voided_twice():
    voided = ticket().void("scale fault")
    with pytest.raises(TicketError):
        voided.void("again")


def test_a_void_needs_a_reason():
    with pytest.raises(TicketError):
        ticket().void("")


def test_splitting_bushels_adds_back_to_the_whole():
    shares = [
        SplitShare("BROCKMAN", Decimal("25"), SplitRole.LANDLORD),
        SplitShare("MERRICK", Decimal("75"), SplitRole.TENANT),
    ]
    parts = split_bushels(Decimal("932.8571"), shares)
    assert sum(parts.values()) == Decimal("932.8571")
    assert parts["BROCKMAN"] == Decimal("233.2143")


def test_three_way_split_residue_lands_on_the_largest_share():
    shares = [
        SplitShare("A", Decimal("33")),
        SplitShare("B", Decimal("33")),
        SplitShare("C", Decimal("34")),
    ]
    parts = split_bushels(Decimal("1000.0001"), shares)
    assert sum(parts.values()) == Decimal("1000.0001")


def test_splitting_across_nothing_is_an_error():
    with pytest.raises(TicketError):
        split_bushels(Decimal("100"), [])
