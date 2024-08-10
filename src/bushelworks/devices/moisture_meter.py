"""Reading the moisture meter on the grading bench.

The meter prints a short report over serial after every test. It is line
oriented and human readable, which makes it easy to parse and easy to get
wrong, because the operator can key a sample id with a colon in it and the
naive split on the first colon then produces nonsense.

A report looks like this:

    GAC2500-UGMA
    SAMPLE: A-10441-2
    CALIB: YELLOW CORN 12.4
    MOIST: 18.2 %
    TEST WT: 56.5 LB/BU
    TEMP: 14.2 C
    END

The calibration line matters as much as the reading. A corn sample measured on
the soybean calibration reads about a point and a half low, and the only way to
catch it afterwards is to have kept the calibration name.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

from ..domain.commodity import Factor
from ..errors import ValidationError

END_MARKER = "END"

# The meter's calibration names against the commodity codes the house uses.
CALIBRATIONS: dict[str, str] = {
    "YELLOW CORN": "CORN",
    "SOYBEANS": "SOYB",
    "HRW WHEAT": "HRW",
    "GRAIN SORGHUM": "MILO",
    "SUNFLOWER OIL": "SUNF",
}


class MeterError(ValidationError):
    """Raised when a meter report cannot be read."""

    code = "meter_error"


@dataclass(frozen=True, slots=True)
class MeterReading:
    """One test off the bench."""

    sample_id: str
    commodity_code: str
    calibration: str
    moisture: Decimal
    test_weight: Decimal | None
    temperature: Decimal | None
    instrument: str

    def as_sample(self) -> dict[Factor, Decimal]:
        """The readings as grade factors, ready to go onto a ticket."""
        sample: dict[Factor, Decimal] = {Factor.MOISTURE: self.moisture}
        if self.test_weight is not None:
            sample[Factor.TEST_WEIGHT] = self.test_weight
        return sample

    def matches(self, commodity_code: str) -> bool:
        """Whether this test was run on the right calibration."""
        return self.commodity_code == commodity_code


def _decimal(text: str, label: str) -> Decimal:
    cleaned = text.strip().split(" ")[0]
    try:
        return Decimal(cleaned)
    except InvalidOperation as exc:
        raise MeterError(f"{label}: {text.strip()!r} is not a number") from exc


def parse_report(text: str) -> MeterReading:
    """Read one report, or say which line could not be read."""
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        raise MeterError("empty report")
    if lines[-1] != END_MARKER:
        raise MeterError("report does not end with END, it may be truncated")

    instrument = lines[0]
    fields: dict[str, str] = {}
    for line in lines[1:-1]:
        if ":" not in line:
            raise MeterError(f"cannot read {line!r}")
        # Split once only. A sample id can contain a colon and often does.
        key, value = line.split(":", 1)
        fields[key.strip().upper()] = value.strip()

    for required in ("SAMPLE", "CALIB", "MOIST"):
        if required not in fields:
            raise MeterError(f"report has no {required} line")

    calibration_line = fields["CALIB"]
    calibration = calibration_line.rsplit(" ", 1)[0].strip()
    commodity_code = CALIBRATIONS.get(calibration.upper())
    if commodity_code is None:
        raise MeterError(f"{calibration!r} is not a calibration this house uses")

    moisture = _decimal(fields["MOIST"], "MOIST")
    if not (Decimal("0") < moisture < Decimal("60")):
        raise MeterError(f"moisture of {moisture} is outside what the meter can read")

    test_weight = None
    if "TEST WT" in fields:
        test_weight = _decimal(fields["TEST WT"], "TEST WT")
        if not (Decimal("20") < test_weight < Decimal("70")):
            raise MeterError(f"test weight of {test_weight} is implausible")

    temperature = _decimal(fields["TEMP"], "TEMP") if "TEMP" in fields else None

    return MeterReading(
        sample_id=fields["SAMPLE"],
        commodity_code=commodity_code,
        calibration=calibration,
        moisture=moisture,
        test_weight=test_weight,
        temperature=temperature,
        instrument=instrument,
    )


def parse_batch(text: str) -> list[MeterReading]:
    """Split a spool of reports and read each one.

    Anything that will not parse is dropped rather than failing the batch. The
    spool is what the bench printed all morning and one bad report should not
    cost the other forty.
    """
    readings: list[MeterReading] = []
    current: list[str] = []
    for line in text.splitlines():
        current.append(line)
        if line.strip() == END_MARKER:
            try:
                readings.append(parse_report("\n".join(current)))
            except MeterError:
                pass
            current = []
    return readings


def check_calibration(reading: MeterReading, commodity_code: str) -> None:
    """Refuse a reading taken on the wrong calibration."""
    if not reading.matches(commodity_code):
        raise MeterError(
            f"{reading.sample_id} was tested on {reading.calibration}, which is "
            f"{reading.commodity_code}, not {commodity_code}"
        )


def temperature_warning(reading: MeterReading) -> str | None:
    """Whether the sample was too cold or too hot to trust.

    Below zero the meter reads low and above thirty five it reads high, both by
    enough to move a settlement. The bench is supposed to let a sample sit.
    """
    if reading.temperature is None:
        return None
    if reading.temperature < Decimal("0"):
        return f"{reading.sample_id} was tested at {reading.temperature}C, too cold"
    if reading.temperature > Decimal("35"):
        return f"{reading.sample_id} was tested at {reading.temperature}C, too warm"
    return None
