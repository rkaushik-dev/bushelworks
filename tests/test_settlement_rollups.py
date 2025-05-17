"""The window query and the two rollups the tax file and the board both use.

The counting tests are the point of this file. The patronage table used to ask
the database how many tickets were on a statement once per statement, which is
invisible on the three settlements a unit test makes and is four thousand round
trips on a crop year. Counting the statements is the only way that stays fixed.
"""

from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.settlement import LineKind
from bushelworks.persistence.base import (
    create_all,
    drop_all,
    engine_for,
    session_factory,
)
from bushelworks.persistence.repositories.settlements import LineTotal
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.patronage import build_year
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer
from bushelworks.services.tax import payee_year, payees
from support import Counter

HARVEST = date(2024, 10, 3)
SPOT = Decimal("4.35")


@pytest.fixture
def engine():
    eng = engine_for("sqlite+pysqlite:///:memory:")
    create_all(eng)
    yield eng
    drop_all(eng)


@pytest.fixture
def factory(engine):
    return session_factory(engine)


@pytest.fixture
def settled(factory, rules, corn_ticket):
    """Two producers, four statements, spread over two months and two sites."""
    plan = [
        ("MERRICK", "HOME", ("A-10441", "A-10442"), HARVEST),
        ("MERRICK", "HOME", ("A-10451",), date(2024, 11, 4)),
        ("DELACROIX", "HOME", ("A-10461", "A-10462", "A-10463"), date(2024, 11, 5)),
    ]
    for party, site, numbers, day in plan:
        with unit_of_work(factory) as uow:
            for number in numbers:
                record_load(
                    uow,
                    replace(corn_ticket, ticket_number=number, scale_date=day),
                    party,
                    rules,
                )
            uow.commit()
        with unit_of_work(factory) as uow:
            settle_producer(uow, party, site, rules, day, SPOT)
            uow.commit()
    return factory


class TestWindow:
    def test_a_span_picks_up_everything_settled_in_it(self, settled) -> None:
        with unit_of_work(settled) as uow:
            rows = uow.settlements.in_window(date(2024, 1, 1), date(2024, 12, 31))
        assert len(rows) == 3

    def test_a_narrower_span_picks_up_less(self, settled) -> None:
        with unit_of_work(settled) as uow:
            rows = uow.settlements.in_window(date(2024, 11, 1), date(2024, 11, 30))
        assert len(rows) == 2

    def test_it_can_be_narrowed_to_one_producer(self, settled) -> None:
        with unit_of_work(settled) as uow:
            rows = uow.settlements.in_window(
                date(2024, 1, 1), date(2024, 12, 31), party_code="DELACROIX"
            )
            assert len(rows) == 1
            assert rows[0].party_code == "DELACROIX"

    def test_it_can_be_narrowed_to_one_site(self, settled) -> None:
        with unit_of_work(settled) as uow:
            here = uow.settlements.in_window(
                date(2024, 1, 1), date(2024, 12, 31), location_code="HOME"
            )
            elsewhere = uow.settlements.in_window(
                date(2024, 1, 1), date(2024, 12, 31), location_code="NORTH"
            )
        assert len(here) == 3
        assert elsewhere == []

    def test_it_comes_back_in_number_order(self, settled) -> None:
        with unit_of_work(settled) as uow:
            numbers = [
                row.number
                for row in uow.settlements.in_window(
                    date(2024, 1, 1), date(2024, 12, 31)
                )
            ]
        assert numbers == sorted(numbers)

    def test_a_voided_statement_is_never_in_it(self, settled) -> None:
        with unit_of_work(settled) as uow:
            uow.settlements.row("S-2024-0001").voided = True
            uow.commit()
        with unit_of_work(settled) as uow:
            rows = uow.settlements.in_window(date(2024, 1, 1), date(2024, 12, 31))
            assert "S-2024-0001" not in [row.number for row in rows]

    def test_it_is_one_query(self, settled, engine) -> None:
        with unit_of_work(settled) as uow, Counter(engine) as counter:
            uow.settlements.in_window(date(2024, 1, 1), date(2024, 12, 31))
        assert counter.selecting("settlement") == 1


class TestLineTotals:
    def test_nothing_asked_for_is_nothing_back(self, settled) -> None:
        with unit_of_work(settled) as uow:
            assert uow.settlements.line_totals([]) == []
            assert uow.settlements.totals_by_kind([]) == {}

    def test_one_row_per_statement_and_kind(self, settled) -> None:
        with unit_of_work(settled) as uow:
            rows = uow.settlements.in_window(date(2024, 1, 1), date(2024, 12, 31))
            totals = uow.settlements.line_totals([rows[0].id])
        pairs = [(line.settlement_id, line.kind) for line in totals]
        assert len(pairs) == len(set(pairs))
        assert all(isinstance(line, LineTotal) for line in totals)

    def test_the_gross_line_carries_the_bushels(self, settled) -> None:
        with unit_of_work(settled) as uow:
            rows = uow.settlements.in_window(date(2024, 1, 1), date(2024, 12, 31))
            totals = uow.settlements.line_totals([rows[0].id])
        gross = next(line for line in totals if line.kind == LineKind.GROSS.value)
        assert gross.quantity > Decimal("0")
        assert gross.dollars > Decimal("0")

    def test_the_kinds_add_back_to_the_header(self, settled) -> None:
        with unit_of_work(settled) as uow:
            rows = uow.settlements.in_window(date(2024, 1, 1), date(2024, 12, 31))
            row = rows[0]
            totals = uow.settlements.totals_by_kind([row.id])
            assert totals[LineKind.GROSS.value] == row.gross

    def test_totals_by_kind_adds_across_statements(self, settled) -> None:
        with unit_of_work(settled) as uow:
            rows = uow.settlements.in_window(date(2024, 1, 1), date(2024, 12, 31))
            everything = uow.settlements.totals_by_kind([row.id for row in rows])
            one_at_a_time: dict[str, Decimal] = {}
            for row in rows:
                for kind, dollars in uow.settlements.totals_by_kind([row.id]).items():
                    one_at_a_time[kind] = one_at_a_time.get(kind, Decimal("0")) + dollars
        assert everything == one_at_a_time

    def test_it_is_one_query_however_many_statements(self, settled, engine) -> None:
        with unit_of_work(settled) as uow:
            ids = [
                row.id
                for row in uow.settlements.in_window(
                    date(2024, 1, 1), date(2024, 12, 31)
                )
            ]
            with Counter(engine) as counter:
                uow.settlements.line_totals(ids)
        assert counter.selecting("settlement_line") == 1


class TestTicketCounts:
    def test_each_statement_carries_its_loads(self, settled) -> None:
        with unit_of_work(settled) as uow:
            rows = uow.settlements.in_window(date(2024, 1, 1), date(2024, 12, 31))
            counts = uow.settlements.ticket_counts([row.id for row in rows])
            by_number = {row.number: counts[row.id] for row in rows}
        assert by_number == {
            "S-2024-0001": 2,
            "S-2024-0002": 1,
            "S-2024-0003": 3,
        }

    def test_every_id_asked_about_comes_back(self, settled) -> None:
        with unit_of_work(settled) as uow:
            counts = uow.settlements.ticket_counts([9998, 9999])
        assert counts == {9998: 0, 9999: 0}

    def test_nothing_asked_for_is_nothing_back(self, settled) -> None:
        with unit_of_work(settled) as uow:
            assert uow.settlements.ticket_counts([]) == {}

    def test_it_is_one_query_and_not_one_each(self, settled, engine) -> None:
        with unit_of_work(settled) as uow:
            ids = [
                row.id
                for row in uow.settlements.in_window(
                    date(2024, 1, 1), date(2024, 12, 31)
                )
            ]
            with Counter(engine) as counter:
                uow.settlements.ticket_counts(ids)
        assert counter.selecting("settlement_ticket") == 1


class TestTheCallersStillAgree:
    def test_the_patronage_table_reads_the_same_as_before(self, settled) -> None:
        with unit_of_work(settled) as uow:
            year = build_year(uow, date(2024, 1, 1), date(2024, 12, 31))
        by_code = {line.party_code: line for line in year.lines}
        assert by_code["MERRICK"].loads == 3
        assert by_code["DELACROIX"].loads == 3
        assert by_code["MERRICK"].bushels > Decimal("0")
        assert by_code["MERRICK"].charges > Decimal("0")

    def test_the_patronage_gross_ties_to_the_settlement_headers(self, settled) -> None:
        with unit_of_work(settled) as uow:
            year = build_year(uow, date(2024, 1, 1), date(2024, 12, 31))
            rows = uow.settlements.in_window(date(2024, 1, 1), date(2024, 12, 31))
            expected = sum(
                row.gross for row in rows if row.party_code == "MERRICK"
            )
        found = next(line for line in year.lines if line.party_code == "MERRICK")
        assert found.gross == expected

    def test_the_tax_file_reads_the_same_as_before(self, settled) -> None:
        with unit_of_work(settled) as uow:
            found = payee_year(uow, "MERRICK", 2024)
        assert found.settlements == 2
        assert found.gross > Decimal("0")

    def test_the_tax_file_lists_both_producers(self, settled) -> None:
        with unit_of_work(settled) as uow:
            everybody = payees(uow, 2024)
        assert {p.party_code for p in everybody} == {"MERRICK", "DELACROIX"}

    def test_the_patronage_table_no_longer_queries_per_statement(
        self, settled, engine
    ) -> None:
        with unit_of_work(settled) as uow, Counter(engine) as counter:
            build_year(uow, date(2024, 1, 1), date(2024, 12, 31))
        assert counter.selecting("settlement_ticket") == 1
        assert counter.selecting("settlement_line") == 1
