"""The statement form, compared character for character.

The other statement tests assert that a number appears somewhere on the page.
These assert the page. That is the difference between knowing the drying charge
was calculated and knowing it printed in the column the producer reads it in.

A failure here is not automatically a bug. It means the form moved, and somebody
has to look at the diff and decide whether it was meant to.
"""

from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.commodity import Factor
from bushelworks.domain.tickets import Disposition, ScaleTicket, SplitShare
from bushelworks.reports.statement import WIDTH, amount_row, render
from bushelworks.services.charges import Advance, StorageCharge
from golden_statements import BIG, DRY, WET
from support import quick_statement

SETTLED = date(2024, 10, 20)
ADVANCE = Advance("2024-0912", Decimal("1500.00"), date(2024, 9, 12))
STORAGE = StorageCharge(
    lot_id="L-1", dollars=Decimal("412.50"), through=date(2024, 10, 20)
)

WET_SAMPLE = {
    Factor.MOISTURE: Decimal("18.2"),
    Factor.TEST_WEIGHT: Decimal("56.5"),
    Factor.BCFM: Decimal("1.2"),
    Factor.DAMAGE_TOTAL: Decimal("1.0"),
}
DRY_SAMPLE = {
    Factor.MOISTURE: Decimal("13.5"),
    Factor.TEST_WEIGHT: Decimal("58.0"),
    Factor.BCFM: Decimal("0.8"),
    Factor.DAMAGE_TOTAL: Decimal("0.5"),
}


def a_ticket(**overrides) -> ScaleTicket:
    base = dict(
        ticket_number="A-10441",
        scale_date=date(2024, 10, 3),
        location_code="HOME",
        commodity_code="CORN",
        gross_pounds=Decimal("78420"),
        tare_pounds=Decimal("26180"),
        disposition=Disposition.CASH_SALE,
        splits=[SplitShare(party_id="MERRICK", percent=Decimal("100"))],
        sample=dict(WET_SAMPLE),
        truck_id="T-14",
        driver="R. Merrick",
        farm="Home place",
    )
    base.update(overrides)
    return ScaleTicket(**base)


def wet_page(rules) -> str:
    return render(
        quick_statement(
            rules,
            a_ticket(),
            settled_on=SETTLED,
            advances=[ADVANCE],
            contract_number="F-2024-118",
        )
    )


def dry_page(rules) -> str:
    return render(
        quick_statement(rules, a_ticket(sample=dict(DRY_SAMPLE)), settled_on=SETTLED)
    )


def big_page(rules) -> str:
    return render(
        quick_statement(
            rules,
            a_ticket(gross_pounds=Decimal("80000")),
            unit_price=Decimal("1450.0000"),
            settled_on=SETTLED,
            storage=[STORAGE],
        )
    )


PAGES = {"wet": (wet_page, WET), "dry": (dry_page, DRY), "big": (big_page, BIG)}


@pytest.mark.parametrize("name", sorted(PAGES))
def test_the_page_is_exactly_what_it_was(name, rules):
    build, expected = PAGES[name]
    assert build(rules) == expected.rstrip("\n")


@pytest.mark.parametrize("name", sorted(PAGES))
def test_no_line_runs_past_the_form(name, rules):
    build, _ = PAGES[name]
    assert all(len(line) <= WIDTH for line in build(rules).split("\n"))


@pytest.mark.parametrize("name", sorted(PAGES))
def test_the_page_has_no_trailing_whitespace(name, rules):
    build, _ = PAGES[name]
    ends = [line for line in build(rules).split("\n") if line != line.rstrip()]
    assert ends == []


def test_the_wet_page_carries_the_drying_charge(rules):
    assert "drying 3.2 points" in wet_page(rules)


def test_the_dry_page_has_no_drying_section(rules):
    assert "DRYING" not in dry_page(rules)


def test_the_dry_page_still_withholds_the_checkoff(rules):
    assert "Kansas Corn Commission" in dry_page(rules)


def test_the_big_page_keeps_the_amount_column_on_the_form(rules):
    line = next(
        row for row in big_page(rules).split("\n") if "NET PAYABLE" in row
    )
    assert len(line) == WIDTH
    assert line.endswith("1,340,405.71")


def test_a_thirteen_character_code_still_leaves_a_gap():
    """The bug the golden pages caught.

    ``ADV-2024-0912`` is thirteen characters and the code column is twelve, so
    it filled the column exactly and the description started with no space in
    front of it. The line read ``ADV-2024-091advance of 2024-09-12``, which
    looks like one word and hides that the code was cut short.
    """
    row = amount_row("ADV-2024-0912", "advance of 2024-09-12", Decimal("-1500.00"))
    assert "ADV-2024-09 advance" in row


def test_a_short_code_is_padded_the_same_way():
    row = amount_row("DRY", "drying 3.2 points", Decimal("-102.33"))
    assert row.startswith("  DRY         drying")


def test_a_code_is_never_longer_than_its_column():
    row = amount_row("A" * 40, "something", Decimal("-1.00"))
    assert row[2:14].strip() == "A" * 11


def test_the_row_is_the_form_width_whatever_the_code():
    for code in ("D", "ADV-2024-0912", "X" * 40):
        assert len(amount_row(code, "a description", Decimal("-1.00"))) == WIDTH


def test_the_pages_are_not_all_the_same():
    """A guard on the fixtures themselves.

    Three constants that quietly became one constant would still pass every
    comparison above and prove nothing at all.
    """
    assert len({WET, DRY, BIG}) == 3
