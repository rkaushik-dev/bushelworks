"""Scale house capture and settlement for country grain elevators.

The version lives here and nowhere else. ``pyproject.toml`` reads it out of this
module, the API reports it on ``/health`` and the CLI prints it, so there is one
place to change it and no chance of the three disagreeing.
"""

__version__ = "0.6.0"

__all__ = ["__version__", "version_tuple"]


def version_tuple() -> tuple[int, int, int]:
    """The version as numbers, for a caller that wants to compare it."""
    major, minor, patch = __version__.split(".")
    return (int(major), int(minor), int(patch))
