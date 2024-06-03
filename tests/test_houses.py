from datetime import date
from decimal import Decimal

from bushelworks.config import Settings
from bushelworks.domain.commodity import Factor
from bushelworks.domain.moisture import ShrinkMethod
from bushelworks.services.houses import discount_book, house_rulebook


def test_the_house_files_both_crop_years_of_corn_shrink():
    book = house_rulebook()
    assert book.shrink_for("CORN", date(2024, 7, 1)).shrink_factor == Decimal("1.20")
    assert book.shrink_for("CORN", date(2024, 10, 3)).shrink_factor == Decimal("1.18")


def test_the_older_table_is_closed_when_the_newer_one_starts():
    history = house_rulebook().shrink.history("CORN")
    assert history[0].effective_to == date(2024, 7, 31)


def test_wheat_is_filed_with_no_shrink():
    assert house_rulebook().shrink_for("HRW", date(2024, 10, 3)).method is (
        ShrinkMethod.NONE
    )


def test_only_corn_has_a_dryer_on_file():
    book = house_rulebook()
    assert book.drying_for("CORN", date(2024, 10, 3)) is not None
    assert book.drying_for("SOYB", date(2024, 10, 3)) is None


def test_all_three_commodities_have_a_storage_rate():
    book = house_rulebook()
    for code in ("CORN", "SOYB", "HRW"):
        assert book.storage_for(code, date(2024, 10, 3)) is not None


def test_the_discount_book_covers_the_factors_the_board_shows():
    book = discount_book()
    assert book.factors("CORN") == (
        Factor.BCFM,
        Factor.DAMAGE_TOTAL,
        Factor.TEST_WEIGHT,
    )
    assert book.factors("HRW") == (Factor.DOCKAGE, Factor.TEST_WEIGHT)


def test_the_policy_takes_the_state_from_settings():
    book = house_rulebook(Settings(state_code="NE"))
    assert book.policy.state_code == "NE"


def test_the_policy_takes_the_minimum_from_settings():
    book = house_rulebook(Settings(minimum_settlement=Decimal("25.00")))
    assert book.policy.minimum_settlement == Decimal("25.00")


def test_wheat_dockage_is_prorated_rather_than_stepped():
    book = discount_book()
    schedule = book.for_factor("HRW", Factor.DOCKAGE)
    assert schedule.rate_for(Decimal("1.25")) == Decimal("0.0050")


def test_light_wheat_is_charged_by_the_pound():
    schedule = discount_book().for_factor("HRW", Factor.TEST_WEIGHT)
    assert schedule.rate_for(Decimal("57.5")) == Decimal("0.0200")
    assert schedule.rate_for(Decimal("56.0")) == Decimal("0.0400")


def test_the_house_covers_the_three_commodities_it_buys():
    book = house_rulebook()
    for code in ("CORN", "SOYB", "HRW"):
        assert book.covers(code, date(2024, 10, 3), "HOME")
    assert not book.covers("MILO", date(2024, 10, 3), "HOME")


def test_nothing_is_covered_before_the_first_crop_year():
    assert not house_rulebook().covers("CORN", date(2023, 1, 1), "HOME")
