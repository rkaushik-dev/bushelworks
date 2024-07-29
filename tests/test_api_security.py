import pytest
from fastapi.testclient import TestClient

from bushelworks.api.app import create_app
from bushelworks.api.security import (
    ANONYMOUS,
    Caller,
    Forbidden,
    NotAuthorised,
    Role,
    parse_keys,
    resolve,
)
from bushelworks.config import Settings
from bushelworks.persistence.base import create_all, engine_for, session_factory

OFFICE_KEY = "office-key-value"
SCALE_KEY = "scale-key-value"
READ_KEY = "read-key-value"

KEYS = frozenset(
    {
        f"k1:office:{OFFICE_KEY}",
        f"k2:scale:{SCALE_KEY}",
        f"k3:read:{READ_KEY}",
    }
)


def build(settings: Settings) -> TestClient:
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    app = create_app(settings=settings, factory=session_factory(engine))
    return TestClient(app)


@pytest.fixture
def open_house():
    with build(Settings(database_url="sqlite+pysqlite:///:memory:")) as client:
        yield client


@pytest.fixture
def locked_house():
    with build(
        Settings(database_url="sqlite+pysqlite:///:memory:", api_keys=KEYS)
    ) as client:
        yield client


def test_parsing_a_three_part_key():
    assert parse_keys("k1:office:secret") == {"secret": Role.OFFICE}


def test_a_bare_key_is_treated_as_office():
    assert parse_keys("plainsecret") == {"plainsecret": Role.OFFICE}


def test_several_keys_parse_together():
    parsed = parse_keys("k1:office:a, k2:read:b ,")
    assert parsed == {"a": Role.OFFICE, "b": Role.READ}


def test_a_malformed_key_is_refused():
    with pytest.raises(ValueError):
        parse_keys("k1:office")


def test_an_unknown_role_is_refused():
    with pytest.raises(ValueError):
        parse_keys("k1:manager:secret")


def test_roles_rank_from_read_up_to_office():
    assert Role.OFFICE.rank > Role.SCALE.rank > Role.READ.rank


def test_a_caller_can_do_anything_at_or_below_its_role():
    caller = Caller(key_id="k", role=Role.SCALE)
    assert caller.can(Role.READ)
    assert caller.can(Role.SCALE)
    assert not caller.can(Role.OFFICE)


def test_a_house_with_no_keys_lets_anybody_in():
    settings = Settings(database_url="sqlite+pysqlite:///:memory:")
    assert resolve(settings, None) == ANONYMOUS
    assert ANONYMOUS.can(Role.OFFICE)


def test_a_locked_house_wants_a_header():
    settings = Settings(database_url="sqlite+pysqlite:///:memory:", api_keys=KEYS)
    with pytest.raises(NotAuthorised):
        resolve(settings, None)


def test_a_wrong_key_is_refused():
    settings = Settings(database_url="sqlite+pysqlite:///:memory:", api_keys=KEYS)
    with pytest.raises(NotAuthorised):
        resolve(settings, "not-a-key")


def test_a_right_key_carries_its_role():
    settings = Settings(database_url="sqlite+pysqlite:///:memory:", api_keys=KEYS)
    assert resolve(settings, SCALE_KEY).role is Role.SCALE
    assert resolve(settings, READ_KEY).role is Role.READ


def test_the_key_id_does_not_echo_the_whole_secret():
    settings = Settings(database_url="sqlite+pysqlite:///:memory:", api_keys=KEYS)
    caller = resolve(settings, OFFICE_KEY)
    assert OFFICE_KEY not in caller.key_id
    assert caller.key_id.endswith("...")


def test_an_open_house_serves_without_a_key(open_house):
    assert open_house.get("/positions/2024-11-01").status_code == 200


def test_a_locked_house_refuses_without_a_key(locked_house):
    response = locked_house.get("/positions/2024-11-01")
    assert response.status_code == 401
    assert response.json()["code"] == "not_authorised"


def test_a_locked_house_serves_with_a_key(locked_house):
    response = locked_house.get(
        "/positions/2024-11-01", headers={"X-API-Key": READ_KEY}
    )
    assert response.status_code == 200


def test_a_bad_key_gets_the_same_401(locked_house):
    response = locked_house.get(
        "/positions/2024-11-01", headers={"X-API-Key": "wrong"}
    )
    assert response.status_code == 401


def test_health_is_open_even_on_a_locked_house(locked_house):
    assert locked_house.get("/health").status_code == 200


def test_a_forbidden_caller_is_a_403():
    caller = Caller(key_id="k", role=Role.READ)
    error = Forbidden("needs office")
    assert error.code == "forbidden"
    assert not caller.can(Role.OFFICE)
