"""The command line, which is how the grain accountant actually works.

The touch screen in the scale house is for the driveway. Month end, the storage
run, the position report and anything that needs to be piped into a spreadsheet
happen here.
"""

from .main import cli

__all__ = ["cli"]
