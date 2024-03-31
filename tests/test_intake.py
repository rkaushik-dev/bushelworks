from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.commodity import Factor
from bushelworks.domain.grade import NumericalGrade
from bushelworks.domain.moisture import ShrinkMethod, ShrinkSchedule
from bushelworks.services.intake import IntakeError, drying_charge, receive, restate_for


def test_a_clean_wet_load_comes_through_intake_whole(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    assert load.net_pounds == Decimal("52240")
    assert load.clean_bushels == Decimal("932.8571")
    # 3.2 points over base at 1.18 is 3.776 percent
    assert load.shrink_percent == Decimal("3.7760")
    assert load.dry_bushels == Decimal("897.6324")
    assert load.shrink_bushels == Decimal("35.2247")
    assert load.clean_bushels == load.dry_bushels + load.shrink_bushels


def test_intake_grades_the_sample(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    assert load.grade.grade is NumericalGrade.US_1
    assert not load.is_sample_grade


def test_moisture_points_are_measured_against_the_filed_base(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    assert load.moisture_points == Decimal("3.2")


def test_a_dry_load_neither_shrinks_nor_dries(rules, corn_ticket):
    dry = replace(
        corn_ticket,
        sample={**corn_ticket.sample, Factor.MOISTURE: Decimal("14.1")},
    )
    load = receive(dry, rules)
    assert load.shrink_percent == Decimal("0.0000")
    assert load.dry_bushels == load.clean_bushels
    assert drying_charge(load, rules) == Decimal("0.00")


def test_discounts_come_back_per_bushel_and_itemised(rules, corn_ticket):
    dirty = replace(
        corn_ticket,
        sample={
            **corn_ticket.sample,
            Factor.TEST_WEIGHT: Decimal("53.4"),
            Factor.BCFM: Decimal("3.35"),
        },
    )
    load = receive(dirty, rules)
    assert load.discount_detail == {
        Factor.TEST_WEIGHT: Decimal("0.0200"),
        Factor.BCFM: Decimal("0.0070"),
    }
    assert load.discount_rate == Decimal("0.0270")


def test_a_clean_load_carries_no_discount(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    assert load.discount_rate == Decimal("0.0000")
    assert load.discount_detail == {}


def test_dockage_is_out_before_bushels_are_counted(rules, corn_ticket):
    docked = replace(corn_ticket, dockage_percent=Decimal("2.0"))
    load = receive(docked, rules)
    assert load.dockage_pounds == Decimal("1045")
    assert load.gross_bushels == Decimal("932.8571")
    assert load.clean_bushels == Decimal("914.1964")


def test_drying_is_charged_on_the_wet_bushels_by_default(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    # 2 points at 0.03 plus 1.2 at 0.045 is 0.114 per bushel
    assert drying_charge(load, rules) == Decimal("106.35")


def test_drying_can_be_charged_on_the_dry_bushels_instead(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    assert drying_charge(load, rules, on_dry_bushels=True) == Decimal("102.33")


def test_a_house_with_no_dryer_filed_charges_nothing(rules, corn_ticket):
    soy = replace(
        corn_ticket,
        commodity_code="SOYB",
        sample={
            Factor.TEST_WEIGHT: Decimal("56.0"),
            Factor.MOISTURE: Decimal("15.0"),
            Factor.FOREIGN_MATERIAL: Decimal("0.5"),
            Factor.DAMAGE_TOTAL: Decimal("1.0"),
            Factor.SPLITS: Decimal("8.0"),
        },
    )
    load = receive(soy, rules)
    assert drying_charge(load, rules) == Decimal("0.00")


def test_a_voided_ticket_is_refused(rules, corn_ticket):
    with pytest.raises(IntakeError):
        receive(corn_ticket.void("reweigh"), rules)


def test_a_commodity_with_no_shrink_table_is_refused(rules, corn_ticket):
    milo = replace(
        corn_ticket,
        commodity_code="MILO",
        sample={
            Factor.TEST_WEIGHT: Decimal("57.0"),
            Factor.MOISTURE: Decimal("13.0"),
            Factor.FOREIGN_MATERIAL: Decimal("1.0"),
            Factor.DAMAGE_TOTAL: Decimal("1.0"),
        },
    )
    with pytest.raises(IntakeError):
        receive(milo, rules)


def test_a_ticket_before_the_season_opens_is_refused(rules, corn_ticket):
    early = replace(corn_ticket, scale_date=date(2024, 7, 15))
    with pytest.raises(IntakeError):
        receive(early, rules)


def test_wheat_filed_with_no_shrink_keeps_all_its_bushels(rules, corn_ticket):
    wheat = replace(
        corn_ticket,
        commodity_code="HRW",
        dockage_percent=Decimal("1.5"),
        sample={
            Factor.TEST_WEIGHT: Decimal("60.5"),
            Factor.MOISTURE: Decimal("14.8"),
            Factor.DOCKAGE: Decimal("1.5"),
            Factor.DAMAGE_TOTAL: Decimal("1.0"),
            Factor.SHRUNKEN_BROKEN: Decimal("1.0"),
            Factor.TOTAL_DEFECTS: Decimal("2.0"),
        },
    )
    load = receive(wheat, rules)
    assert load.shrink_percent == Decimal("0.0000")
    assert load.dry_bushels == load.clean_bushels


def test_restating_a_moisture_reading_redoes_only_the_shrink(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    schedule = ShrinkSchedule(
        commodity_code="CORN",
        base_moisture=Decimal("15.0"),
        method=ShrinkMethod.TABLE,
        shrink_factor=Decimal("1.18"),
    )
    corrected = restate_for(load, schedule, Decimal("17.0"))
    assert corrected.moisture_points == Decimal("2.0")
    assert corrected.shrink_percent == Decimal("2.3600")
    assert corrected.clean_bushels == load.clean_bushels
    assert corrected.grade == load.grade


def test_settled_bushels_is_the_dry_count(rules, corn_ticket):
    load = receive(corn_ticket, rules)
    assert load.settled_bushels == load.dry_bushels
