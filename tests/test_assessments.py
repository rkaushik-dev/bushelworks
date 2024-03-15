from decimal import Decimal

import pytest

from bushelworks.domain.assessments import (
    Assessment,
    AssessmentBasis,
    AssessmentBook,
    AssessmentError,
    ValueBasis,
    standard_book,
)


def test_per_bushel_levy_extends_over_bushels():
    levy = Assessment(
        code="KSCORN",
        name="Kansas Corn Commission",
        authority="KDA",
        commodity_code="CORN",
        rate=Decimal("0.0100"),
        state_code="KS",
    )
    assert levy.withhold(Decimal("932.8571"), Decimal("4057.93"), Decimal("4000.00")) == (
        Decimal("9.33")
    )


def test_percent_levy_uses_the_net_value_by_default():
    levy = Assessment(
        code="USB",
        name="United Soybean Board",
        authority="USDA",
        commodity_code="SOYB",
        rate=Decimal("0.5"),
        basis=AssessmentBasis.PERCENT_OF_VALUE,
    )
    assert levy.withhold(Decimal("800"), Decimal("9000.00"), Decimal("8800.00")) == (
        Decimal("44.00")
    )


def test_percent_levy_can_be_filed_against_the_gross():
    levy = Assessment(
        code="USB",
        name="United Soybean Board",
        authority="USDA",
        commodity_code="SOYB",
        rate=Decimal("0.5"),
        basis=AssessmentBasis.PERCENT_OF_VALUE,
        value_basis=ValueBasis.GROSS,
    )
    assert levy.withhold(Decimal("800"), Decimal("9000.00"), Decimal("8800.00")) == (
        Decimal("45.00")
    )


def test_a_percent_levy_on_a_worthless_load_withholds_nothing():
    levy = Assessment(
        code="USB",
        name="United Soybean Board",
        authority="USDA",
        commodity_code="SOYB",
        rate=Decimal("0.5"),
        basis=AssessmentBasis.PERCENT_OF_VALUE,
    )
    assert levy.withhold(Decimal("800"), Decimal("0"), Decimal("0")) == Decimal("0.00")


def test_a_negative_rate_is_refused():
    with pytest.raises(AssessmentError):
        Assessment(
            code="X",
            name="X",
            authority="X",
            commodity_code="CORN",
            rate=Decimal("-0.01"),
        )


def test_an_implausible_percentage_is_refused():
    with pytest.raises(AssessmentError):
        Assessment(
            code="X",
            name="X",
            authority="X",
            commodity_code="SOYB",
            rate=Decimal("50"),
            basis=AssessmentBasis.PERCENT_OF_VALUE,
        )


def test_state_filter_picks_only_the_levies_that_apply():
    book = standard_book()
    kansas = [a.code for a in book.applicable("CORN", "KS")]
    nebraska = [a.code for a in book.applicable("CORN", "NE")]
    assert "KSCORN" in kansas and "NECORN" not in kansas
    assert "NECORN" in nebraska and "KSCORN" not in nebraska


def test_a_wildcard_levy_applies_everywhere():
    book = standard_book()
    assert "NCB" in [a.code for a in book.applicable("CORN", "KS")]
    assert "NCB" in [a.code for a in book.applicable("CORN", "NE")]


def test_a_zero_rate_levy_produces_no_line():
    book = standard_book()
    lines = book.withhold(
        "CORN", "KS", Decimal("10000"), Decimal("45000.00"), Decimal("44000.00")
    )
    assert [line.code for line in lines] == ["KSCORN"]
    assert lines[0].dollars == Decimal("100.00")


def test_exempt_codes_are_skipped():
    book = standard_book()
    lines = book.withhold(
        "CORN",
        "KS",
        Decimal("10000"),
        Decimal("45000.00"),
        Decimal("44000.00"),
        exempt_codes=["KSCORN"],
    )
    assert lines == []


def test_totals_and_the_refundable_portion():
    book = standard_book()
    lines = book.withhold(
        "CORN", "KS", Decimal("10000"), Decimal("45000.00"), Decimal("44000.00")
    )
    assert book.total_withheld(lines) == Decimal("100.00")
    assert book.refundable_portion(lines) == Decimal("100.00")


def test_a_non_refundable_levy_is_not_in_the_refundable_total():
    book = standard_book()
    lines = book.withhold(
        "CORN", "NE", Decimal("10000"), Decimal("45000.00"), Decimal("44000.00")
    )
    assert book.total_withheld(lines) == Decimal("50.00")
    assert book.refundable_portion(lines) == Decimal("0.00")


def test_the_book_refuses_the_same_levy_twice():
    book = AssessmentBook()
    levy = Assessment(
        code="KSCORN",
        name="Kansas Corn",
        authority="KDA",
        commodity_code="CORN",
        rate=Decimal("0.01"),
        state_code="KS",
    )
    book.add(levy)
    with pytest.raises(AssessmentError):
        book.add(levy)


def test_the_book_lists_its_codes():
    assert standard_book().codes() == (
        "KSCORN",
        "KSWHEAT",
        "NCB",
        "NECORN",
        "USB",
    )


def test_a_commodity_with_no_levy_in_that_state_gets_nothing():
    book = standard_book()
    assert book.applicable("HRW", "NE") == []
