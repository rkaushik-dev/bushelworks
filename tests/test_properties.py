"""Properties that have to hold for every input, not just the worked examples.

The example based tests check the numbers the office would tie out by hand.
These check the things that have to be true whatever comes in, and they have
already found two: an allocation that lost a cent on a three way split of an odd
amount, and a shrink that could return more dry bushels than went in when the
moisture came in exactly at base.
"""

from decimal import Decimal

from hypothesis import assume, given, settings
from hypothesis import strategies as st

from bushelworks.domain.moisture import ShrinkMethod, ShrinkSchedule
from bushelworks.money import allocate, amount, extend, total
from bushelworks.units import (
    BUSHEL_PLACES,
    bushels_to_pounds,
    net_pounds,
    pounds_to_bushels,
    quantize,
)

# Weights a truck scale can actually report. Six digits, whole pounds.
pounds = st.integers(min_value=0, max_value=999_999).map(Decimal)

# Money in a range a settlement reaches, at cents.
amounts = st.decimals(
    min_value=Decimal("-1000000"),
    max_value=Decimal("1000000"),
    places=2,
    allow_nan=False,
    allow_infinity=False,
)

# Percentage shares of a split, which must add to a hundred.
share_counts = st.integers(min_value=1, max_value=6)

test_weights = st.sampled_from(
    [Decimal("25"), Decimal("32"), Decimal("56"), Decimal("60")]
)

moistures = st.decimals(
    min_value=Decimal("0"), max_value=Decimal("40"), places=1, allow_nan=False
)


@given(gross=pounds, tare=pounds)
def test_net_weight_is_never_more_than_gross(gross, tare):
    assume(tare <= gross)
    assert net_pounds(gross, tare) <= gross


@given(gross=pounds, tare=pounds)
def test_net_weight_is_never_negative(gross, tare):
    assume(tare <= gross)
    assert net_pounds(gross, tare) >= 0


@given(weight=pounds, standard=test_weights)
def test_bushels_are_never_negative(weight, standard):
    assert pounds_to_bushels(weight, standard) >= 0


@given(weight=pounds, standard=test_weights)
def test_converting_back_lands_within_a_bushel(weight, standard):
    bushels = pounds_to_bushels(weight, standard)
    back = bushels_to_pounds(bushels, standard)
    assert abs(back - weight) <= standard


@given(weight=pounds, standard=test_weights)
def test_a_heavier_load_is_never_fewer_bushels(weight, standard):
    heavier = weight + Decimal("1000")
    assert pounds_to_bushels(heavier, standard) >= pounds_to_bushels(weight, standard)


@given(value=amounts, count=share_counts)
def test_an_allocation_always_adds_back_to_the_whole(value, count):
    weights = [Decimal(1)] * count
    assert sum(allocate(value, weights)) == amount(value)


@given(value=amounts, first=st.integers(1, 99))
def test_a_two_way_allocation_adds_back_whatever_the_split(value, first):
    parts = allocate(value, [Decimal(first), Decimal(100 - first)])
    assert sum(parts) == amount(value)


@given(value=amounts, count=share_counts)
def test_every_part_of_an_allocation_is_at_cents(value, count):
    for part in allocate(value, [Decimal(1)] * count):
        assert part == part.quantize(Decimal("0.01"))


@given(value=amounts, count=share_counts)
def test_an_allocation_never_flips_the_sign(value, count):
    assume(value != 0)
    parts = allocate(value, [Decimal(1)] * count)
    if value > 0:
        assert all(part >= 0 for part in parts)
    else:
        assert all(part <= 0 for part in parts)


@given(values=st.lists(amounts, min_size=0, max_size=20))
def test_a_total_is_the_sum_of_its_parts(values):
    assert total(values) == amount(sum(values, Decimal("0")))


@given(
    quantity=st.decimals(
        min_value=Decimal("0"), max_value=Decimal("100000"), places=4, allow_nan=False
    ),
    rate=st.decimals(
        min_value=Decimal("0"), max_value=Decimal("50"), places=4, allow_nan=False
    ),
)
def test_extending_is_never_negative_for_positive_inputs(quantity, rate):
    assert extend(quantity, rate) >= 0


@given(
    quantity=st.decimals(
        min_value=Decimal("0"), max_value=Decimal("100000"), places=4, allow_nan=False
    ),
    rate=st.decimals(
        min_value=Decimal("0"), max_value=Decimal("50"), places=4, allow_nan=False
    ),
)
def test_extending_lands_at_cents(quantity, rate):
    value = extend(quantity, rate)
    assert value == value.quantize(Decimal("0.01"))


@settings(max_examples=200)
@given(moisture=moistures, factor=st.sampled_from([Decimal("1.15"), Decimal("1.18")]))
def test_shrink_never_makes_more_bushels_than_went_in(moisture, factor):
    schedule = ShrinkSchedule(
        commodity_code="CORN",
        base_moisture=Decimal("15.0"),
        method=ShrinkMethod.TABLE,
        shrink_factor=factor,
    )
    wet = Decimal("1000.0000")
    assert schedule.dry_bushels(wet, moisture) <= wet


@given(moisture=moistures)
def test_dry_and_shrunk_always_add_back_to_wet(moisture):
    schedule = ShrinkSchedule(
        commodity_code="CORN",
        base_moisture=Decimal("15.0"),
        method=ShrinkMethod.TABLE,
        shrink_factor=Decimal("1.18"),
    )
    wet = Decimal("1000.0000")
    dry = schedule.dry_bushels(wet, moisture)
    gone = schedule.shrunk_bushels(wet, moisture)
    assert dry + gone == wet


@given(moisture=moistures)
def test_wetter_grain_never_shrinks_less(moisture):
    schedule = ShrinkSchedule(
        commodity_code="CORN",
        base_moisture=Decimal("15.0"),
        method=ShrinkMethod.TABLE,
        shrink_factor=Decimal("1.18"),
    )
    assume(moisture < Decimal("39"))
    wetter = moisture + Decimal("1")
    assert schedule.shrink_percent(wetter) >= schedule.shrink_percent(moisture)


@given(
    value=st.decimals(
        min_value=Decimal("-1000"), max_value=Decimal("1000"), places=6, allow_nan=False
    )
)
def test_quantizing_is_idempotent(value):
    once = quantize(value, BUSHEL_PLACES)
    assert quantize(once, BUSHEL_PLACES) == once
