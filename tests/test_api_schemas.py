import json
from datetime import date
from decimal import Decimal

import pytest
from pydantic import ValidationError as PydanticError

from bushelworks.api.schemas import (
    Page,
    Problem,
    SampleReading,
    SettlementLineOut,
    SplitIn,
    TicketIn,
)
from bushelworks.domain.commodity import Factor
from bushelworks.domain.tickets import Disposition, SplitRole


def a_body(**overrides) -> dict:
    body = {
        "ticket_number": "A-10441",
        "location_code": "HOME",
        "scale_date": "2024-10-03",
        "commodity_code": "CORN",
        "party_code": "MERRICK",
        "gross_pounds": "78420",
        "tare_pounds": "26180",
        "disposition": "cash_sale",
        "splits": [{"party_code": "MERRICK", "percent": "100"}],
        "sample": [
            {"factor": "moisture", "value": "18.2"},
            {"factor": "test_weight", "value": "56.5"},
        ],
    }
    body.update(overrides)
    return body


def test_a_ticket_body_parses_into_decimals():
    ticket = TicketIn.model_validate(a_body())
    assert ticket.gross_pounds == Decimal("78420")
    assert ticket.scale_date == date(2024, 10, 3)
    assert ticket.disposition is Disposition.CASH_SALE
    assert ticket.sample[0].factor is Factor.MOISTURE


def test_a_float_quantity_is_refused_with_an_explanation():
    with pytest.raises(PydanticError) as caught:
        TicketIn.model_validate(a_body(gross_pounds=78420.5))
    assert "as strings" in str(caught.value)


def test_a_quantity_that_is_not_a_number_is_refused():
    with pytest.raises(PydanticError):
        TicketIn.model_validate(a_body(gross_pounds="heavy"))


def test_a_ticket_needs_at_least_one_share():
    with pytest.raises(PydanticError):
        TicketIn.model_validate(a_body(splits=[]))


def test_an_unknown_factor_is_refused():
    with pytest.raises(PydanticError):
        SampleReading.model_validate({"factor": "colour", "value": "1"})


def test_an_unknown_disposition_is_refused():
    with pytest.raises(PydanticError):
        TicketIn.model_validate(a_body(disposition="borrowed"))


def test_a_share_defaults_to_owner():
    share = SplitIn.model_validate({"party_code": "MERRICK", "percent": "100"})
    assert share.role is SplitRole.OWNER


def test_a_role_can_be_named():
    share = SplitIn.model_validate(
        {"party_code": "BROCKMAN", "percent": "25", "role": "landlord"}
    )
    assert share.role is SplitRole.LANDLORD


def test_decimals_serialise_as_strings():
    line = SettlementLineOut(
        kind="gross",
        code="GRAIN",
        description="corn",
        dollars=Decimal("3904.70"),
        quantity=Decimal("897.6324"),
        rate=Decimal("4.3500"),
    )
    body = json.loads(line.model_dump_json())
    assert body["dollars"] == "3904.70"
    assert body["quantity"] == "897.6324"
    assert body["rate"] == "4.3500"


def test_an_absent_optional_decimal_stays_null():
    line = SettlementLineOut(
        kind="drying", code="DRY", description="drying", dollars=Decimal("102.33")
    )
    body = json.loads(line.model_dump_json())
    assert body["quantity"] is None


def test_a_page_knows_whether_there_is_more():
    assert Page(items=[1, 2], total=5, offset=0, limit=2).has_more
    assert not Page(items=[1, 2], total=2, offset=0, limit=2).has_more
    assert not Page(items=[], total=0, offset=0, limit=2).has_more


def test_a_problem_body_has_a_code_and_a_sentence():
    problem = Problem(code="tare_exceeds_gross", detail="tare exceeds gross")
    body = json.loads(problem.model_dump_json())
    assert body == {
        "code": "tare_exceeds_gross",
        "detail": "tare exceeds gross",
        "request_id": None,
    }


def test_optional_ticket_fields_default_to_empty():
    ticket = TicketIn.model_validate(a_body())
    assert ticket.truck_id == ""
    assert ticket.contract_number is None
    assert ticket.dockage_percent == Decimal("0")


def test_a_blank_ticket_number_is_refused():
    with pytest.raises(PydanticError):
        TicketIn.model_validate(a_body(ticket_number=""))
