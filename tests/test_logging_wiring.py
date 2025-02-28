"""The events the office greps for have to actually be emitted.

Every one of these is a line somebody has asked for at least once while working
out where a load went. The names are the contract; the fields can change.
"""

from datetime import date
from decimal import Decimal

import pytest
from fastapi.testclient import TestClient

from bushelworks.api.app import create_app
from bushelworks.config import Settings
from bushelworks.errors import ConfigurationError
from bushelworks.logging import Capture
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer
from bushelworks.services.warehousing import accrue, store


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def test_recording_a_load_logs_it(factory, rules, corn_ticket):
    with Capture() as log:
        with unit_of_work(factory) as uow:
            record_load(uow, corn_ticket, "MERRICK", rules)
            uow.commit()
    lines = log.find("ticket.recorded")
    assert len(lines) == 1
    assert "ticket=A-10441" in lines[0]
    assert "party=MERRICK" in lines[0]
    assert "dry_bushels=897.6324" in lines[0]
    assert "grade=1" in lines[0]


def test_a_load_with_no_contract_logs_the_open_bushels(factory, rules, corn_ticket):
    with Capture() as log:
        with unit_of_work(factory) as uow:
            record_load(uow, corn_ticket, "MERRICK", rules)
            uow.commit()
    assert "contracts=-" in log.find("ticket.recorded")[0]


def test_a_sample_grade_load_raises_a_warning(factory, rules, corn_ticket):
    from dataclasses import replace

    from bushelworks.domain.commodity import Factor

    bad = replace(
        corn_ticket,
        sample={**corn_ticket.sample, Factor.DAMAGE_TOTAL: Decimal("30.0")},
    )
    with Capture() as log:
        with unit_of_work(factory) as uow:
            record_load(uow, bad, "MERRICK", rules)
            uow.commit()
    lines = log.find("ticket.sample_grade")
    assert len(lines) == 1
    assert "damage_total" in lines[0]


def test_a_clean_load_raises_no_warning(factory, rules, corn_ticket):
    with Capture() as log:
        with unit_of_work(factory) as uow:
            record_load(uow, corn_ticket, "MERRICK", rules)
            uow.commit()
    assert log.find("ticket.sample_grade") == []


def test_settling_logs_the_totals(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    with Capture() as log:
        with unit_of_work(factory) as uow:
            settle_producer(
                uow, "MERRICK", "HOME", rules, date(2024, 10, 20), Decimal("4.35")
            )
            uow.commit()
    lines = log.find("settlement.written")
    assert len(lines) == 1
    assert "number=S-2024-0001" in lines[0]
    assert "tickets=1" in lines[0]
    assert "net=" in lines[0]


def test_the_accrual_run_logs_what_it_billed(factory, rules):
    with unit_of_work(factory) as uow:
        store(uow, "L-1", "MERRICK", "CORN", "HOME", Decimal("8000"), date(2024, 10, 3))
        uow.commit()
    with Capture() as log:
        with unit_of_work(factory) as uow:
            accrue(uow, rules, date(2024, 12, 14))
            uow.commit()
    lines = log.find("storage.accrued")
    assert len(lines) == 1
    assert "lots=1" in lines[0]
    assert "recorded=yes" in lines[0]


def test_a_dry_run_says_it_recorded_nothing(factory, rules):
    with unit_of_work(factory) as uow:
        store(uow, "L-1", "MERRICK", "CORN", "HOME", Decimal("8000"), date(2024, 10, 3))
        uow.commit()
    with Capture() as log:
        with unit_of_work(factory) as uow:
            accrue(uow, rules, date(2024, 12, 14), record=False)
    assert "recorded=no" in log.find("storage.accrued")[0]


def test_every_served_request_is_logged():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    app = create_app(
        settings=Settings(database_url="sqlite+pysqlite:///:memory:"),
        factory=session_factory(engine),
    )
    with Capture() as log, TestClient(app) as client:
        client.get("/health")
    lines = log.find("request.served")
    assert len(lines) == 1
    assert "path=/health" in lines[0]
    assert "status=200" in lines[0]
    assert "request_id=" in lines[0]


def test_the_log_level_comes_from_settings():
    assert Settings().log_level == "INFO"
    assert Settings(log_level="ERROR").log_level == "ERROR"


def test_a_nonsense_log_level_is_refused():
    with pytest.raises(ConfigurationError):
        Settings(log_level="CHATTY")


def test_the_log_level_is_read_from_the_environment(monkeypatch):
    from bushelworks.config import load_settings

    monkeypatch.setenv("BUSHELWORKS_LOG_LEVEL", "warning")
    assert load_settings().log_level == "WARNING"
