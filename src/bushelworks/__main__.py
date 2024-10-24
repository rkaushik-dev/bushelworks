"""Run the command line with ``python -m bushelworks``.

The office box has no virtualenv on the PATH and the batch files call the
interpreter by its full path. This is how they reach the CLI without a console
script being installed anywhere.
"""

from __future__ import annotations

import sys

from .cli.main import cli


def main(argv: list[str] | None = None) -> int:
    """Run the CLI and hand its exit code back to the shell."""
    try:
        cli.main(args=argv, standalone_mode=False)
    except SystemExit as exit_signal:  # raised by click on --help and on errors
        code = exit_signal.code
        return 0 if code is None else int(code)
    except Exception as error:  # pragma: no cover - last resort for a batch log
        print(f"bushelworks: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":  # pragma: no cover - module entry point
    raise SystemExit(main())
