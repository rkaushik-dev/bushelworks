"""What the package says about itself.

Three things used to disagree about the version: the module, the metadata and
the ``/health`` response. They are one thing now and this is what keeps them
that way.
"""

import subprocess
import sys
from importlib.metadata import version as installed_version
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import bushelworks
from bushelworks.api.app import create_app
from bushelworks.config import Settings
from bushelworks.persistence.base import create_all, engine_for, session_factory

ROOT = Path(bushelworks.__file__).resolve().parent


def test_the_module_carries_a_version():
    assert bushelworks.__version__
    assert bushelworks.__version__.count(".") == 2


def test_the_version_reads_back_as_numbers():
    major, minor, patch = bushelworks.version_tuple()
    assert isinstance(major, int)
    assert (major, minor, patch) >= (0, 1, 0)


def test_the_installed_metadata_agrees_with_the_module():
    assert installed_version("bushelworks") == bushelworks.__version__


def test_the_package_ships_a_py_typed_marker():
    assert (ROOT / "py.typed").exists()


def test_health_reports_the_same_version():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    app = create_app(
        settings=Settings(database_url="sqlite+pysqlite:///:memory:"),
        factory=session_factory(engine),
    )
    with TestClient(app) as client:
        assert client.get("/health").json()["version"] == bushelworks.__version__


def run_module(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-m", "bushelworks", *args],
        capture_output=True,
        text=True,
        timeout=60,
    )


@pytest.mark.parametrize("flag", ["--help", "-h"])
def test_the_module_runs_and_prints_help(flag):
    result = run_module(flag)
    assert result.returncode == 0
    assert "Scale house capture and settlement" in result.stdout


def test_the_module_prints_its_version():
    result = run_module("--version")
    assert result.returncode == 0
    assert bushelworks.__version__ in result.stdout


def test_the_module_lists_the_commands():
    result = run_module("--help")
    for command in ("settle", "tickets", "position", "cheques"):
        assert command in result.stdout


def test_an_unknown_command_exits_non_zero():
    result = run_module("plough")
    assert result.returncode != 0


def test_the_entry_point_returns_an_exit_code():
    from bushelworks.__main__ import main

    assert main(["--version"]) == 0
