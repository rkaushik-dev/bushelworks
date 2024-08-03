from datetime import date
from decimal import Decimal

import pytest

from bushelworks.reports.ach import (
    BLOCKING_FACTOR,
    RECORD_LENGTH,
    AchError,
    Credit,
    Originator,
    build,
    check_digit,
    routing_is_valid,
    total_cents,
)

HOUSE = Originator(
    name="BUSHELWORKS ELEVATOR",
    company_id="1481003377",
    routing_number="101000187",
    bank_name="FARMERS STATE BANK",
)

CREATED = date(2024, 10, 24)
EFFECTIVE = date(2024, 10, 25)


def a_credit(**overrides) -> Credit:
    base = dict(
        party_code="MERRICK",
        name="MERRICK FARMS LLC",
        routing_number="101000187",
        account_number="4417002391",
        dollars=Decimal("2848.01"),
        reference="S-2024-0417",
    )
    base.update(overrides)
    return Credit(**base)


def test_the_check_digit_comes_out_of_the_first_eight():
    assert check_digit("101000187") == 7
    assert routing_is_valid("101000187")


def test_a_bad_check_digit_is_caught():
    assert not routing_is_valid("101000188")


def test_a_short_routing_number_is_not_valid():
    assert not routing_is_valid("10100018")
    assert not routing_is_valid("abcdefghi")


def test_an_originator_needs_a_nine_digit_routing_number():
    with pytest.raises(AchError):
        Originator(
            name="X", company_id="1481003377", routing_number="1010001", bank_name="X"
        )


def test_an_originator_company_id_is_ten_characters():
    with pytest.raises(AchError):
        Originator(
            name="X", company_id="148100", routing_number="101000187", bank_name="X"
        )


def test_a_credit_has_to_be_positive():
    with pytest.raises(AchError):
        a_credit(dollars=Decimal("0"))


def test_a_credit_needs_an_account_number():
    with pytest.raises(AchError):
        a_credit(account_number="")


def test_a_credit_carries_its_amount_in_cents():
    assert a_credit().cents == 284801


def test_every_record_is_ninety_four_characters():
    text = build(HOUSE, [a_credit()], CREATED, EFFECTIVE, "101000187")
    for line in text.strip().split("\n"):
        assert len(line) == RECORD_LENGTH


def test_the_file_is_blocked_to_ten_records():
    text = build(HOUSE, [a_credit()], CREATED, EFFECTIVE, "101000187")
    lines = text.strip().split("\n")
    assert len(lines) % BLOCKING_FACTOR == 0


def test_the_padding_is_all_nines():
    text = build(HOUSE, [a_credit()], CREATED, EFFECTIVE, "101000187")
    lines = text.strip().split("\n")
    assert lines[-1] == "9" * RECORD_LENGTH


def test_the_file_starts_with_a_header_record():
    text = build(HOUSE, [a_credit()], CREATED, EFFECTIVE, "101000187")
    assert text.startswith("101")
    assert "FARMERS STATE BANK" in text
    assert "BUSHELWORKS ELEVATOR" in text


def test_the_batch_header_names_the_effective_day():
    text = build(HOUSE, [a_credit()], CREATED, EFFECTIVE, "101000187")
    assert EFFECTIVE.strftime("%y%m%d") in text


def test_an_entry_carries_the_producer_and_the_amount():
    text = build(HOUSE, [a_credit()], CREATED, EFFECTIVE, "101000187")
    entry_line = [line for line in text.split("\n") if line.startswith("622")][0]
    assert "MERRICK" in entry_line
    assert "0000284801" in entry_line


def test_the_batch_control_totals_the_entries():
    credits = [a_credit(), a_credit(party_code="HOLLIS", dollars=Decimal("1000.00"))]
    text = build(HOUSE, credits, CREATED, EFFECTIVE, "101000187")
    control = [line for line in text.split("\n") if line.startswith("8220")][0]
    assert str(total_cents(credits)).zfill(12) in control
    assert control[4:10] == "000002"


def test_the_file_control_totals_the_same_amount():
    credits = [a_credit(), a_credit(party_code="HOLLIS", dollars=Decimal("1000.00"))]
    text = build(HOUSE, credits, CREATED, EFFECTIVE, "101000187")
    control = [line for line in text.split("\n") if line.startswith("9") and len(set(line)) > 1][-1]
    assert str(total_cents(credits)).zfill(12) in control


def test_entries_are_numbered_in_order():
    credits = [a_credit(), a_credit(party_code="HOLLIS")]
    text = build(HOUSE, credits, CREATED, EFFECTIVE, "101000187")
    entries = [line for line in text.split("\n") if line.startswith("622")]
    assert entries[0].endswith("0000001")
    assert entries[1].endswith("0000002")


def test_an_empty_file_is_refused():
    with pytest.raises(AchError):
        build(HOUSE, [], CREATED, EFFECTIVE, "101000187")


def test_the_file_ends_with_a_newline():
    text = build(HOUSE, [a_credit()], CREATED, EFFECTIVE, "101000187")
    assert text.endswith("\n")


def test_total_cents_adds_the_credits():
    credits = [a_credit(), a_credit(dollars=Decimal("0.99"))]
    assert total_cents(credits) == 284801 + 99
