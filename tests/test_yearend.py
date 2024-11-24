from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.settlement import LineKind
from bushelworks.reports.yearend import (
    Summary,
    YearEndError,
    build,
    busiest_month,
    crop_year_span,
    render,
    statement_index,
)
from bushelworks.services.charges import Advance
from support import quick_statement


@pytest.fixture
def statements(rules, corn_ticket):
    from dataclasses import replace

    return [
        quick_statement(
            rules,
            corn_ticket,
            number="S-2024-0417",
            settled_on=date(2024, 10, 20),
        ),
        quick_statement(
            rules,
            replace(corn_ticket, ticket_number="A-10442"),
            number="S-2024-0418",
            settled_on=date(2024, 11, 4),
            advances=[Advance("A", Decimal("500.00"), date(2024, 9, 12))],
        ),
        quick_statement(
            rules,
            replace(corn_ticket, ticket_number="A-10443"),
            number="S-2025-0001",
            settled_on=date(2025, 1, 8),
        ),
    ]


def test_a_summary_gathers_only_the_year_asked_for(statements):
    summary = build("MERRICK", 2024, statements)
    assert summary.count == 2
    assert summary.year == 2024


def test_another_producers_statements_are_left_out(statements, rules, corn_ticket):
    other = quick_statement(
        rules, corn_ticket, number="S-2024-0500", party_code="HOLLIS"
    )
    summary = build("MERRICK", 2024, [*statements, other])
    assert summary.count == 2


def test_a_statement_from_the_wrong_year_is_refused():
    summary = Summary(party_code="MERRICK", year=2024)
    with pytest.raises(YearEndError):
        summary.add(_stub(date(2025, 1, 8)))


def test_a_statement_from_the_wrong_producer_is_refused():
    summary = Summary(party_code="MERRICK", year=2024)
    with pytest.raises(YearEndError):
        summary.add(_stub(date(2024, 10, 20), party="HOLLIS"))


def _stub(settled_on, party="MERRICK"):
    from bushelworks.domain.settlement import Settlement, SettlementLine

    return Settlement(
        number="S-X",
        settled_on=settled_on,
        party_id=party,
        location_code="HOME",
        lines=(
            SettlementLine(
                kind=LineKind.GROSS, code="GRAIN", description="corn",
                dollars=Decimal("100.00"),
            ),
        ),
    )


def test_the_gross_and_the_net_add_the_statements_up(statements):
    summary = build("MERRICK", 2024, statements)
    assert summary.gross == statements[0].gross + statements[1].gross
    assert summary.net == statements[0].net + statements[1].net


def test_the_tickets_are_listed_once_each(statements):
    summary = build("MERRICK", 2024, statements)
    assert summary.tickets == ("A-10441", "A-10442")


def test_the_kinds_are_totalled_in_canonical_order(statements):
    kinds = list(build("MERRICK", 2024, statements).by_kind())
    assert kinds[0] is LineKind.GROSS
    assert LineKind.ADVANCE in kinds
    assert kinds.index(LineKind.ADVANCE) > kinds.index(LineKind.GROSS)


def test_the_summary_balances_against_the_statements(statements):
    build("MERRICK", 2024, statements).check_balances()


def test_a_tampered_summary_does_not_balance(statements):
    summary = build("MERRICK", 2024, statements)
    summary.settlements = summary.settlements[:1]
    summary.check_balances()
    summary.settlements.append(statements[2])
    with pytest.raises(YearEndError):
        summary.add(statements[2])


def test_an_empty_year_summarises_to_nothing(statements):
    summary = build("MERRICK", 2023, statements)
    assert summary.count == 0
    assert summary.net == Decimal("0.00")
    assert summary.by_kind() == {}


def test_the_page_names_the_producer_and_the_year(statements):
    text = render(build("MERRICK", 2024, statements))
    assert "YEAR END SUMMARY 2024" in text
    assert "MERRICK" in text
    assert "NET PAID" in text


def test_the_page_fits_its_width(statements):
    text = render(build("MERRICK", 2024, statements))
    assert all(len(line) <= 80 for line in text.split("\n"))


def test_reductions_print_in_brackets(statements):
    text = render(build("MERRICK", 2024, statements))
    assert "(" in text


def test_an_empty_year_says_so_on_the_page(statements):
    text = render(build("MERRICK", 2023, statements))
    assert "Nothing settled in this year." in text


def test_the_index_lists_every_statement(statements):
    text = statement_index(build("MERRICK", 2024, statements))
    assert "S-2024-0417" in text
    assert "S-2024-0418" in text
    assert "2 statements" in text


def test_the_index_is_in_date_order(statements):
    text = statement_index(build("MERRICK", 2024, statements))
    first = text.index("S-2024-0417")
    second = text.index("S-2024-0418")
    assert first < second


def test_the_busiest_month_is_the_one_paid_most(statements):
    month, paid = busiest_month(build("MERRICK", 2024, statements))
    assert month == 10
    assert paid == statements[0].net


def test_an_empty_year_has_no_busiest_month(statements):
    assert busiest_month(build("MERRICK", 2023, statements)) is None


def test_a_crop_year_runs_september_to_august():
    opens, closes = crop_year_span(2024)
    assert opens == date(2023, 9, 1)
    assert closes == date(2024, 8, 31)
