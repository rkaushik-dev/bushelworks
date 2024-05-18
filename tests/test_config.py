from decimal import Decimal

import pytest

from bushelworks.config import Settings, load_settings
from bushelworks.errors import ConfigurationError


def test_defaults_work_with_nothing_set(monkeypatch):
    for key in list(__import__("os").environ):
        if key.startswith("BUSHELWORKS_"):
            monkeypatch.delenv(key, raising=False)
    settings = load_settings()
    assert settings.is_sqlite
    assert settings.state_code == "KS"
    assert not settings.requires_api_key


def test_the_database_url_comes_from_the_environment(monkeypatch):
    monkeypatch.setenv("BUSHELWORKS_DATABASE_URL", "postgresql+psycopg://x/y")
    settings = load_settings()
    assert settings.database_url == "postgresql+psycopg://x/y"
    assert not settings.is_sqlite


def test_a_state_code_is_upper_cased(monkeypatch):
    monkeypatch.setenv("BUSHELWORKS_STATE_CODE", "ne")
    assert load_settings().state_code == "NE"


def test_a_bad_state_code_is_refused(monkeypatch):
    monkeypatch.setenv("BUSHELWORKS_STATE_CODE", "KANSAS")
    with pytest.raises(ConfigurationError):
        load_settings()


def test_booleans_accept_the_usual_spellings(monkeypatch):
    for raw, expected in (
        ("1", True),
        ("true", True),
        ("YES", True),
        ("on", True),
        ("0", False),
        ("false", False),
        ("no", False),
        ("off", False),
    ):
        monkeypatch.setenv("BUSHELWORKS_ECHO_SQL", raw)
        assert load_settings().echo_sql is expected


def test_a_nonsense_boolean_is_refused(monkeypatch):
    monkeypatch.setenv("BUSHELWORKS_ECHO_SQL", "maybe")
    with pytest.raises(ConfigurationError):
        load_settings()


def test_a_nonsense_number_is_refused(monkeypatch):
    monkeypatch.setenv("BUSHELWORKS_MAX_PAGE_SIZE", "lots")
    with pytest.raises(ConfigurationError):
        load_settings()


def test_a_page_size_below_one_is_refused():
    with pytest.raises(ConfigurationError):
        Settings(max_page_size=0)


def test_api_keys_are_split_and_trimmed(monkeypatch):
    monkeypatch.setenv("BUSHELWORKS_API_KEYS", " one , two ,, three ")
    settings = load_settings()
    assert settings.api_keys == frozenset({"one", "two", "three"})
    assert settings.requires_api_key


def test_the_minimum_settlement_is_a_decimal(monkeypatch):
    monkeypatch.setenv("BUSHELWORKS_MINIMUM_SETTLEMENT", "25.00")
    assert load_settings().minimum_settlement == Decimal("25.00")


def test_a_negative_minimum_settlement_is_refused():
    with pytest.raises(ConfigurationError):
        Settings(minimum_settlement=Decimal("-1"))


def test_an_empty_database_url_is_refused():
    with pytest.raises(ConfigurationError):
        Settings(database_url="")
