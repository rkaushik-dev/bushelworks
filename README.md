# bushelworks

Scale house capture and settlement for country grain elevators.

The scale house is where a load of grain stops being a truck and starts being a
number. This package holds the arithmetic that turns one into the other: gross
and tare weights, dockage, moisture shrink, grade factors, discount schedules,
contract application, storage accrual and the settlement statement that comes
out the far end.

Everything in the settlement path is exact decimal. There are no floats in it
anywhere, and quantities cross the HTTP boundary as strings so they cannot
acquire any.

## What is in here

| package | what it holds |
| --- | --- |
| `bushelworks.domain` | the rules. No database, no clock, no network. |
| `bushelworks.schedules` | effective dated lookup for the filed tables |
| `bushelworks.services` | the order the rules run in |
| `bushelworks.persistence` | tables, mappers, repositories, unit of work |
| `bushelworks.api` | the HTTP interface the driveway terminal calls |
| `bushelworks.cli` | what the grain accountant runs at six in the morning |
| `bushelworks.reports` | statements, the position board, exports |
| `bushelworks.devices` | the scale indicator and the moisture meter |

The dependency runs one way. `domain` imports nothing from the rest, and
`persistence` maps rows onto domain objects rather than the other way round.

## The two rules worth knowing before reading any of it

**A rate is resolved as of the ticket date, never as of today.** A ticket
written in October settles on October's tables however many times it is
reprinted. Nothing in `bushelworks.domain` reads the clock.

**A correction is a second document.** Nothing is deleted and nothing is edited
in place. A ticket is voided and reissued, a settlement is reversed by writing
its opposite, and both halves stay on file. The state inspector reads the pair.

## Running it

    pip install -e ".[dev]"
    bushelworks init-db
    bushelworks --help

The command list, the commodity master and the grade ladders are generated from
the code rather than written out here, because the ones written out here went
stale twice:

    python -m bushelworks.reference

## A worked load

    gross          78420 lb
    tare           26180 lb
    net            52240 lb
    bushels        932.8571 at 56 lb/bu
    moisture       18.2% against a 15.0% base
    shrink         3.7760%
    dry bushels    897.6324

That load settles on 897.6324 bushels. Shrink comes off before anything is
priced, discounts come off the value, the checkoff sits on the value after
discounts, and an advance is recovered last. The order is enforced by the
statement builder rather than by the caller remembering it.

## Tests

    pytest -q

The suite is example based where the office would tie the number out by hand,
and property based where something has to hold for every input. The end to end
test works one producer through four loads of a harvest day and asserts the
figures a grain accountant would check.

## Migrations

    alembic upgrade head

The models and the migrations are compared against each other in the test suite.
A table added to one and forgotten in the other fails there rather than on the
office box on a Monday.
