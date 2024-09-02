from decimal import Decimal

import pytest

from bushelworks.domain.bins import Bin, BinError, BinYard
from bushelworks.domain.commodity import Factor
from bushelworks.errors import ConflictError

SAMPLE = {
    Factor.MOISTURE: Decimal("14.0"),
    Factor.TEST_WEIGHT: Decimal("56.0"),
}

WET = {
    Factor.MOISTURE: Decimal("18.0"),
    Factor.TEST_WEIGHT: Decimal("54.0"),
}


def a_bin(**overrides) -> Bin:
    base = dict(
        bin_id="B-04",
        location_code="HOME",
        capacity_bushels=Decimal("100000"),
    )
    base.update(overrides)
    return Bin(**base)


def test_a_bin_needs_a_capacity():
    with pytest.raises(BinError):
        a_bin(capacity_bushels=Decimal("0"))


def test_grain_in_a_bin_has_to_be_something():
    with pytest.raises(BinError):
        a_bin(bushels=Decimal("1000"))


def test_a_bin_cannot_hold_more_than_it_holds():
    with pytest.raises(BinError):
        a_bin(commodity_code="CORN", bushels=Decimal("200000"))


def test_an_empty_bin_has_working_headroom():
    empty = a_bin()
    assert empty.is_empty
    assert empty.working_capacity == Decimal("97000.0000")
    assert empty.headroom == Decimal("97000.0000")
    assert empty.percent_full == Decimal("0.0")


def test_filling_puts_grain_in_and_sets_the_commodity():
    filled = a_bin().fill("CORN", Decimal("40000"), SAMPLE)
    assert filled.commodity_code == "CORN"
    assert filled.bushels == Decimal("40000.0000")
    assert filled.sample[Factor.MOISTURE] == Decimal("14.0")


def test_a_second_load_blends_with_the_first():
    filled = a_bin().fill("CORN", Decimal("40000"), SAMPLE).fill(
        "CORN", Decimal("40000"), WET
    )
    assert filled.bushels == Decimal("80000.0000")
    assert filled.sample[Factor.MOISTURE] == Decimal("16.00")
    assert filled.sample[Factor.TEST_WEIGHT] == Decimal("55.00")


def test_the_blend_is_weighted_by_bushels():
    filled = a_bin().fill("CORN", Decimal("60000"), SAMPLE).fill(
        "CORN", Decimal("20000"), WET
    )
    assert filled.sample[Factor.MOISTURE] == Decimal("15.00")


def test_a_factor_only_one_load_had_drops_out_of_the_blend():
    filled = a_bin().fill("CORN", Decimal("40000"), SAMPLE).fill(
        "CORN", Decimal("40000"), {Factor.MOISTURE: Decimal("18.0")}
    )
    assert Factor.TEST_WEIGHT not in filled.sample


def test_a_bin_will_not_take_another_commodity():
    filled = a_bin().fill("CORN", Decimal("40000"), SAMPLE)
    with pytest.raises(ConflictError):
        filled.fill("SOYB", Decimal("1000"), SAMPLE)
    assert not filled.accepts("SOYB", Decimal("1000"))


def test_a_bin_will_not_take_more_than_its_headroom():
    filled = a_bin().fill("CORN", Decimal("90000"), SAMPLE)
    assert filled.headroom == Decimal("7000.0000")
    with pytest.raises(BinError):
        filled.fill("CORN", Decimal("8000"), SAMPLE)


def test_the_last_three_percent_is_not_working_capacity():
    filled = a_bin().fill("CORN", Decimal("97000"), SAMPLE)
    assert filled.is_full
    assert filled.percent_full == Decimal("97.0")


def test_filling_nothing_is_refused():
    with pytest.raises(BinError):
        a_bin().fill("CORN", Decimal("0"), SAMPLE)


def test_drawing_takes_grain_out_and_leaves_the_sample():
    filled = a_bin().fill("CORN", Decimal("40000"), SAMPLE)
    drawn = filled.draw(Decimal("15000"))
    assert drawn.bushels == Decimal("25000.0000")
    assert drawn.sample == filled.sample


def test_emptying_a_bin_clears_the_commodity():
    filled = a_bin().fill("CORN", Decimal("40000"), SAMPLE)
    empty = filled.draw(Decimal("40000"))
    assert empty.is_empty
    assert empty.commodity_code is None
    assert empty.sample == {}


def test_drawing_more_than_is_there_is_refused():
    filled = a_bin().fill("CORN", Decimal("40000"), SAMPLE)
    with pytest.raises(BinError):
        filled.draw(Decimal("40001"))


def test_drawing_nothing_is_refused():
    with pytest.raises(BinError):
        a_bin().fill("CORN", Decimal("100"), SAMPLE).draw(Decimal("0"))


def test_a_bin_out_of_service_takes_nothing():
    out = a_bin().take_out_of_service("fan motor")
    assert not out.accepts("CORN", Decimal("100"))
    with pytest.raises(ConflictError):
        out.fill("CORN", Decimal("100"), SAMPLE)


def test_taking_a_bin_out_twice_is_a_conflict():
    out = a_bin().take_out_of_service("fan motor")
    with pytest.raises(ConflictError):
        out.take_out_of_service("again")


def test_a_bin_can_come_back():
    back = a_bin().take_out_of_service("fan motor").return_to_service()
    assert back.accepts("CORN", Decimal("100"))
    with pytest.raises(ConflictError):
        back.return_to_service()


def test_taking_a_bin_out_needs_a_reason():
    with pytest.raises(BinError):
        a_bin().take_out_of_service("")


def a_yard() -> BinYard:
    return BinYard(
        "HOME",
        [
            a_bin(bin_id="B-01", capacity_bushels=Decimal("40000")),
            a_bin(bin_id="B-02", capacity_bushels=Decimal("100000")),
            a_bin(bin_id="B-03", capacity_bushels=Decimal("250000")),
        ],
    )


def test_a_yard_holds_its_bins_in_order():
    assert [one.bin_id for one in a_yard().bins()] == ["B-01", "B-02", "B-03"]


def test_a_bin_from_another_site_is_refused():
    with pytest.raises(BinError):
        BinYard("HOME", [a_bin(location_code="LURAY")])


def test_the_same_bin_twice_is_a_conflict():
    yard = a_yard()
    with pytest.raises(ConflictError):
        yard.add(a_bin(bin_id="B-01", capacity_bushels=Decimal("40000")))


def test_a_missing_bin_says_so():
    with pytest.raises(BinError):
        a_yard().get("B-99")


def test_the_yard_totals_what_is_in_it():
    yard = a_yard()
    yard.replace(yard.get("B-02").fill("CORN", Decimal("50000"), SAMPLE))
    yard.replace(yard.get("B-03").fill("SOYB", Decimal("20000"), SAMPLE))
    assert yard.total_bushels() == Decimal("70000.0000")
    assert yard.total_bushels("CORN") == Decimal("50000.0000")


def test_the_yard_reports_which_bins_hold_a_commodity():
    yard = a_yard()
    yard.replace(yard.get("B-02").fill("CORN", Decimal("50000"), SAMPLE))
    assert [one.bin_id for one in yard.holding("CORN")] == ["B-02"]


def test_room_for_a_commodity_skips_the_bins_holding_something_else():
    yard = a_yard()
    yard.replace(yard.get("B-03").fill("SOYB", Decimal("20000"), SAMPLE))
    assert yard.room_for("CORN") == Decimal("135800.0000")


def test_room_skips_a_bin_out_of_service():
    yard = a_yard()
    yard.replace(yard.get("B-01").take_out_of_service("fan motor"))
    assert yard.room_for("CORN") == Decimal("339500.0000")


def test_a_load_goes_into_a_bin_already_holding_the_commodity():
    yard = a_yard()
    yard.replace(yard.get("B-02").fill("CORN", Decimal("50000"), SAMPLE))
    chosen = yard.best_bin_for("CORN", Decimal("1000"))
    assert chosen.bin_id == "B-02"


def test_the_fullest_matching_bin_gets_finished_first():
    yard = a_yard()
    yard.replace(yard.get("B-02").fill("CORN", Decimal("20000"), SAMPLE))
    yard.replace(yard.get("B-03").fill("CORN", Decimal("50000"), SAMPLE))
    assert yard.best_bin_for("CORN", Decimal("1000")).bin_id == "B-03"


def test_an_empty_yard_puts_a_load_in_the_smallest_bin_that_takes_it():
    assert a_yard().best_bin_for("CORN", Decimal("1000")).bin_id == "B-01"


def test_a_load_nothing_can_take_finds_no_bin():
    assert a_yard().best_bin_for("CORN", Decimal("500000")) is None
