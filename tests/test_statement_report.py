from datetime import date
from decimal import Decimal

from bushelworks.reports.statement import (
    WIDTH,
    register,
    render,
    render_many,
    summary_line,
)
from bushelworks.services.charges import Advance
from support import quick_statement

SETTLED = date(2024, 10, 20)
PRICE = Decimal("4.3500")


def statement(rules, corn_ticket, number="S-2024-0417"):
    return quick_statement(
        rules,
        corn_ticket,
        number=number,
        unit_price=PRICE,
        settled_on=SETTLED,
        advances=[Advance("2024-0912", Decimal("1500.00"), date(2024, 9, 12))],
        contract_number="F-2024-118",
    )


def test_no_line_runs_past_eighty_columns(rules, corn_ticket):
    text = render(statement(rules, corn_ticket))
    assert all(len(line) <= WIDTH for line in text.split("\n"))


def test_the_header_names_the_statement_and_the_producer(rules, corn_ticket):
    text = render(statement(rules, corn_ticket))
    assert "SETTLEMENT STATEMENT" in text
    assert "S-2024-0417" in text
    assert "MERRICK" in text
    assert "2024-10-20" in text


def test_the_tickets_and_contracts_are_listed(rules, corn_ticket):
    text = render(statement(rules, corn_ticket))
    assert "A-10441" in text
    assert "F-2024-118" in text


def test_section_headings_appear_for_the_kinds_present(rules, corn_ticket):
    text = render(statement(rules, corn_ticket))
    assert "GRAIN PURCHASED" in text
    assert "DRYING" in text
    assert "CHECKOFF WITHHELD" in text
    assert "ADVANCES RECOVERED" in text
    assert "OTHER DEDUCTIONS" not in text


def test_reducing_lines_print_in_brackets(rules, corn_ticket):
    text = render(statement(rules, corn_ticket))
    assert "(1,500.00)" in text


def test_the_net_is_the_last_thing_on_the_page(rules, corn_ticket):
    s = statement(rules, corn_ticket)
    text = render(s)
    last = text.split("\n")[-1]
    assert last.startswith("  NET PAYABLE")
    assert last.strip().endswith("2,293.39")
    assert s.net == Decimal("2293.39")


def test_a_section_with_one_line_gets_no_subtotal(rules, corn_ticket):
    text = render(statement(rules, corn_ticket))
    assert "DRYING TOTAL" not in text


def test_the_house_name_can_be_overridden(rules, corn_ticket):
    text = render(statement(rules, corn_ticket), house_name="LURAY GRAIN CO")
    assert "LURAY GRAIN CO" in text


def test_several_statements_are_separated_by_a_form_feed(rules, corn_ticket):
    one = statement(rules, corn_ticket, "S-1")
    two = statement(rules, corn_ticket, "S-2")
    text = render_many([one, two])
    assert text.count("\f") == 1
    assert "S-1" in text and "S-2" in text


def test_the_summary_line_is_one_row_per_statement(rules, corn_ticket):
    row = summary_line(statement(rules, corn_ticket))
    assert len(row) <= WIDTH
    assert "S-2024-0417" in row
    assert "MERRICK" in row


def test_the_register_totals_what_it_lists(rules, corn_ticket):
    rows = [
        statement(rules, corn_ticket, "S-1"),
        statement(rules, corn_ticket, "S-2"),
    ]
    text = register(rows, "20 October 2024")
    assert "SETTLEMENT REGISTER" in text
    assert "2 statements" in text
    assert "4,586.78" in text


def test_a_seven_figure_statement_stays_inside_the_form(rules, corn_ticket):
    """The regression that printed a charge twice.

    An amount over a million formats to thirteen characters and the amount
    column was twelve. The line ran to eighty one, the dot matrix wrapped it,
    and the producer read the wrapped remainder as a second charge.
    """
    from bushelworks.domain.settlement import LineKind, SettlementBuilder

    builder = SettlementBuilder("S-2024-0417", SETTLED, "MERRICK", "HOME")
    builder.add_gross(
        "GRAIN", "Corn, 3,000,000 bu", Decimal("3000000"), Decimal("4.3500")
    )
    builder.add_charge(
        LineKind.ADVANCE, "ADV", "advance of 12 September", Decimal("2000000.00")
    )
    text = render(builder.build())
    assert all(len(line) <= WIDTH for line in text.split("\n"))


def test_a_long_description_is_trimmed_rather_than_wrapped():
    from bushelworks.reports.statement import amount_row

    row = amount_row("TW", "x" * 200, Decimal("18.66"))
    assert len(row) == WIDTH


def test_a_long_total_label_is_trimmed_too():
    from bushelworks.reports.statement import total_row

    row = total_row("y" * 200, Decimal("-1234567890.12"))
    assert len(row) == WIDTH


def test_a_register_row_with_a_long_producer_stays_inside_the_form(rules, corn_ticket):
    from dataclasses import replace

    statement = quick_statement(rules, corn_ticket, number="S-2024-0417")
    long_named = replace(statement, party_id="A" * 60)
    assert len(summary_line(long_named)) <= WIDTH


def test_an_empty_register_still_renders(rules):
    text = register([], "20 October 2024")
    assert "0 statements" in text
    assert "0.00" in text
