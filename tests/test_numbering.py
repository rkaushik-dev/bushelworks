from datetime import date

import pytest

from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.services.numbering import DocumentNumber, Numbering, NumberingError

OCTOBER = date(2024, 10, 20)


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def test_a_number_renders_with_a_padded_sequence():
    assert str(DocumentNumber("S", 2024, 417)) == "S-2024-0417"
    assert str(DocumentNumber("S", 2024, 7)) == "S-2024-0007"
    assert str(DocumentNumber("S", 2024, 12345)) == "S-2024-12345"


def test_a_number_parses_back():
    parsed = DocumentNumber.parse("S-2024-0417")
    assert parsed == DocumentNumber("S", 2024, 417)


def test_parsing_rejects_what_is_not_a_number():
    for bad in ("S2024417", "S-24-0417", "S-2024-abc", "-2024-0417", "S-2024"):
        with pytest.raises(NumberingError):
            DocumentNumber.parse(bad)


def test_next_steps_the_sequence_and_keeps_the_year():
    assert DocumentNumber("S", 2024, 417).next() == DocumentNumber("S", 2024, 418)


def test_the_first_number_of_a_series_is_one(factory):
    with factory() as session:
        numbering = Numbering(session)
        assert str(numbering.allocate("S", OCTOBER)) == "S-2024-0001"


def test_numbers_run_consecutively(factory):
    with factory() as session:
        numbering = Numbering(session)
        issued = [str(numbering.allocate("S", OCTOBER)) for _ in range(3)]
    assert issued == ["S-2024-0001", "S-2024-0002", "S-2024-0003"]


def test_the_counter_restarts_each_year(factory):
    with factory() as session:
        numbering = Numbering(session)
        numbering.allocate("S", OCTOBER)
        numbering.allocate("S", OCTOBER)
        assert str(numbering.allocate("S", date(2025, 1, 3))) == "S-2025-0001"


def test_series_do_not_share_a_counter(factory):
    with factory() as session:
        numbering = Numbering(session)
        numbering.allocate("S", OCTOBER)
        assert str(numbering.allocate("ADV", OCTOBER)) == "ADV-2024-0001"


def test_peek_does_not_consume_a_number(factory):
    with factory() as session:
        numbering = Numbering(session)
        assert numbering.peek("S", OCTOBER) == 1
        numbering.allocate("S", OCTOBER)
        assert numbering.peek("S", OCTOBER) == 2
        assert numbering.peek("S", OCTOBER) == 2


def test_a_rollback_puts_the_number_back(factory):
    with factory() as session:
        Numbering(session).allocate("S", OCTOBER)
        session.rollback()
    with factory() as session:
        assert str(Numbering(session).allocate("S", OCTOBER)) == "S-2024-0001"


def test_a_committed_number_is_not_reissued(factory):
    with factory() as session:
        Numbering(session).allocate("S", OCTOBER)
        session.commit()
    with factory() as session:
        assert str(Numbering(session).allocate("S", OCTOBER)) == "S-2024-0002"


def test_a_block_of_numbers_comes_back_in_order(factory):
    with factory() as session:
        block = Numbering(session).reserve_block("S", OCTOBER, 4)
    assert [n.sequence for n in block] == [1, 2, 3, 4]


def test_a_block_moves_the_counter_past_it(factory):
    with factory() as session:
        numbering = Numbering(session)
        numbering.reserve_block("S", OCTOBER, 4)
        assert str(numbering.allocate("S", OCTOBER)) == "S-2024-0005"


def test_an_empty_block_is_refused(factory):
    with factory() as session:
        with pytest.raises(NumberingError):
            Numbering(session).reserve_block("S", OCTOBER, 0)


def test_an_unnamed_series_is_refused(factory):
    with factory() as session:
        with pytest.raises(NumberingError):
            Numbering(session).allocate("", OCTOBER)


def test_a_gapless_year_passes_the_check(factory):
    with factory() as session:
        Numbering(session).check_gapless("S", 2024, [1, 2, 3, 4])


def test_an_empty_year_passes_the_check(factory):
    with factory() as session:
        Numbering(session).check_gapless("S", 2024, [])


def test_a_hole_in_the_year_is_reported(factory):
    with factory() as session:
        with pytest.raises(ConflictError) as caught:
            Numbering(session).check_gapless("S", 2024, [1, 2, 4, 5])
    assert "missing 1 numbers: 3" in str(caught.value)
