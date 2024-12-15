"""A house with something in it, for a demonstration or a fresh install.

An empty database is hard to look at. This puts one real looking elevator in
it: two sites, six producers, the commodity master, a yard of bins and a week of
harvest. It is deterministic, so two people running it get the same numbers and
can talk about the same ticket.

Nothing here is fixture data for the tests. The tests build exactly what they
need; this is for the screen the manager is shown before he buys anything, and
for a developer who wants to see a statement without keying nine loads.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal

from ..domain import commodity as cm
from ..domain.tickets import Disposition, ScaleTicket, SplitRole, SplitShare
from ..errors import ConflictError
from ..logging import event, get_logger
from ..persistence.models import BinRow, CommodityRow, Location, Party
from ..persistence.unitofwork import UnitOfWork
from .parties import file_agreement, open_account
from .scalehouse import record_load

_log = get_logger("seeding")

SEASON_OPENS = date(2024, 9, 16)

SITES = (
    ("HOME", "Home elevator", "KS-1174", 1_450_000),
    ("LURAY", "Luray branch", "KS-1175", 620_000),
)

PRODUCERS = (
    ("MERRICK", "Merrick Farms LLC"),
    ("BROCKMAN", "Ellen Brockman"),
    ("HOLLIS", "Hollis Brothers"),
    ("STROH", "Stroh Cattle Co"),
    ("VOSS", "Voss Land and Grain"),
    ("KREHBIEL", "Krehbiel Seed Farms"),
)

BINS = (
    ("HOME", "B-01", 40_000),
    ("HOME", "B-02", 100_000),
    ("HOME", "B-03", 250_000),
    ("HOME", "B-04", 250_000),
    ("LURAY", "B-11", 80_000),
    ("LURAY", "B-12", 120_000),
)

# One week of harvest, deterministic and a bit varied. Weight, moisture, test
# weight, damage, and who hauled it.
LOADS = (
    ("MERRICK", 78420, 26180, "18.2", "56.5", "1.0"),
    ("MERRICK", 79100, 26180, "17.4", "56.1", "1.4"),
    ("HOLLIS", 77980, 26240, "15.8", "55.2", "2.6"),
    ("VOSS", 78650, 26180, "14.6", "57.0", "0.8"),
    ("BROCKMAN", 76400, 25900, "16.9", "53.4", "4.2"),
    ("KREHBIEL", 79220, 26180, "13.9", "56.8", "1.1"),
    ("MERRICK", 78040, 26180, "15.1", "55.9", "1.9"),
    ("STROH", 77500, 26000, "19.4", "54.0", "3.1"),
    ("HOLLIS", 78880, 26240, "14.2", "56.4", "0.9"),
)


@dataclass(frozen=True, slots=True)
class Seeded:
    """What went in."""

    parties: int
    locations: int
    bins: int
    tickets: int

    @property
    def is_empty(self) -> bool:
        return self.tickets == 0


def is_seeded(uow: UnitOfWork) -> bool:
    """Whether anything is already here. Seeding twice is refused."""
    from sqlalchemy import func, select

    count = uow.session.scalar(select(func.count()).select_from(Party))
    return bool(count)


def seed(uow: UnitOfWork, rules, force: bool = False) -> Seeded:
    """Fill an empty database with one week of a working elevator."""
    if is_seeded(uow) and not force:
        raise ConflictError(
            "there is already a producer file here; seed a fresh database"
        )

    for code, name, licence, capacity in SITES:
        uow.session.add(
            Location(
                code=code,
                name=name,
                state_code="KS",
                warehouse_licence=licence,
                licensed_capacity=capacity,
            )
        )

    for code in cm.codes():
        one = cm.get(code)
        uow.session.add(
            CommodityRow(
                code=one.code,
                name=one.name,
                standard_test_weight=one.standard_test_weight,
                base_moisture=one.base_moisture,
                price_unit=one.price_unit.value,
            )
        )

    for code, name in PRODUCERS:
        open_account(uow, code, name, opened_on=date(2014, 3, 1))

    for location_code, bin_id, capacity in BINS:
        uow.session.add(
            BinRow(
                bin_id=bin_id,
                location_code=location_code,
                capacity_bushels=Decimal(capacity),
                is_aerated=True,
            )
        )
    uow.flush()

    file_agreement(
        uow,
        "HOME-PLACE",
        [
            SplitShare("BROCKMAN", Decimal("25"), SplitRole.LANDLORD),
            SplitShare("MERRICK", Decimal("75"), SplitRole.TENANT),
        ],
        date(2024, 3, 1),
    )

    written = 0
    for index, (party, gross, tare, moisture, test_weight, damage) in enumerate(LOADS):
        ticket = ScaleTicket(
            ticket_number=f"A-{10441 + index}",
            scale_date=SEASON_OPENS + timedelta(days=index // 2),
            location_code="HOME" if index % 3 else "LURAY",
            commodity_code="CORN",
            gross_pounds=Decimal(gross),
            tare_pounds=Decimal(tare),
            disposition=Disposition.CASH_SALE,
            splits=[SplitShare(party, Decimal("100"))],
            truck_id=f"T-{10 + index}",
            farm="Home place" if party == "MERRICK" else "",
            sample={
                cm.Factor.TEST_WEIGHT: Decimal(test_weight),
                cm.Factor.MOISTURE: Decimal(moisture),
                cm.Factor.BCFM: Decimal("1.2"),
                cm.Factor.DAMAGE_TOTAL: Decimal(damage),
            },
        )
        record_load(uow, ticket, party, rules, actor="seed")
        written += 1

    uow.flush()
    result = Seeded(
        parties=len(PRODUCERS),
        locations=len(SITES),
        bins=len(BINS),
        tickets=written,
    )
    event(
        _log,
        "house.seeded",
        parties=result.parties,
        locations=result.locations,
        bins=result.bins,
        tickets=result.tickets,
    )
    return result


def describe(result: Seeded) -> str:
    """One line for the console."""
    return (
        f"{result.locations} sites, {result.parties} producers, "
        f"{result.bins} bins, {result.tickets} tickets"
    )
