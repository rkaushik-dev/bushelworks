from datetime import date
from decimal import Decimal

import pytest

from bushelworks.services.legacy import (
    RECORD_LENGTH,
    ImportError_,
    duplicates,
    parse_file,
    parse_line,
    summary,
)


def a_line(
    ticket="A-10441   ",
    scale_date="20221014",
    party="MRK001",
    commodity="CORN",
    location="HOME",
    gross="     78420",
    tare="     26180",
    moisture="  18.2",
    test_weight="  56.5",
    damage="   1.0",
    disposition="CASH    ",
    void=" ",
) -> str:
    return (
        ticket
        + scale_date
        + party
        + commodity
        + location
        + gross
        + tare
        + moisture
        + test_weight
        + damage
        + disposition
        + void
    )


def test_the_layout_adds_up_to_the_record_length():
    assert len(a_line()) == RECORD_LENGTH


def test_a_clean_line_reads():
    row = parse_line(a_line(), 1)
    assert row.ticket_number == "A-10441"
    assert row.scale_date == date(2022, 10, 14)
    assert row.party_code == "MRK001"
    assert row.gross_pounds == Decimal("78420")
    assert row.net_pounds == Decimal("52240")
    assert row.disposition == "cash_sale"
    assert not row.voided


def test_the_factors_come_across():
    row = parse_line(a_line(), 1)
    assert row.moisture == Decimal("18.2")
    assert row.test_weight == Decimal("56.5")
    assert row.damage == Decimal("1.0")


def test_a_blank_factor_reads_as_not_tested_rather_than_zero():
    row = parse_line(a_line(moisture="      "), 1)
    assert row.moisture is None


def test_a_factor_that_is_not_a_number_reads_as_not_tested():
    row = parse_line(a_line(damage="  N/A "), 1)
    assert row.damage is None


def test_a_weight_with_a_comma_still_reads():
    row = parse_line(a_line(gross="    78,420"), 1)
    assert row.gross_pounds == Decimal("78420")


def test_a_void_flag_is_read():
    assert parse_line(a_line(void="V"), 1).voided


def test_the_party_code_can_be_mapped():
    row = parse_line(a_line(), 1, {"MRK001": "MERRICK"})
    assert row.party_code == "MERRICK"


def test_an_unmapped_party_code_comes_across_as_it_is():
    row = parse_line(a_line(), 1, {"OTH999": "OTHER"})
    assert row.party_code == "MRK001"


def test_a_short_line_is_refused():
    with pytest.raises(ImportError_):
        parse_line(a_line()[:40], 1)


def test_a_line_with_no_ticket_number_is_refused():
    with pytest.raises(ImportError_):
        parse_line(a_line(ticket="          "), 1)


def test_a_line_with_no_party_is_refused():
    with pytest.raises(ImportError_):
        parse_line(a_line(party="      "), 1)


def test_a_bad_date_is_refused_naming_it():
    with pytest.raises(ImportError_) as caught:
        parse_line(a_line(scale_date="20221345"), 1)
    assert "20221345" in str(caught.value)


def test_a_blank_weight_is_refused():
    with pytest.raises(ImportError_):
        parse_line(a_line(gross="          "), 1)


def test_a_weight_that_is_not_a_number_is_refused():
    with pytest.raises(ImportError_):
        parse_line(a_line(tare="     ABCDE"), 1)


def test_a_tare_above_gross_is_refused():
    with pytest.raises(ImportError_):
        parse_line(a_line(gross="     26180", tare="     78420"), 1)


def test_an_unknown_disposition_is_refused():
    with pytest.raises(ImportError_):
        parse_line(a_line(disposition="BORROWED"), 1)


def test_a_whole_file_reads():
    text = "\n".join([a_line(), a_line(ticket="A-10442   ")])
    result = parse_file(text)
    assert result.read == 2
    assert result.is_clean


def test_blank_lines_are_skipped():
    text = a_line() + "\n\n" + a_line(ticket="A-10442   ")
    assert parse_file(text).read == 2


def test_a_bad_row_is_kept_with_its_reason():
    text = "\n".join([a_line(), a_line(disposition="BORROWED")])
    result = parse_file(text)
    assert result.read == 1
    assert result.refused == 1
    assert "disposition" in result.rejected[0].reason
    assert result.rejected[0].line_number == 2


def test_a_bad_row_does_not_cost_the_good_ones():
    text = "\n".join(
        [a_line(), a_line(scale_date="XXXXXXXX"), a_line(ticket="A-10443   ")]
    )
    result = parse_file(text)
    assert [row.ticket_number for row in result.rows] == ["A-10441", "A-10443"]


def test_rows_can_be_pulled_out_by_producer():
    text = "\n".join([a_line(), a_line(ticket="A-10442   ", party="HOL001")])
    result = parse_file(text)
    assert len(result.by_party("MRK001")) == 1


def test_a_repeated_ticket_number_at_one_site_is_reported():
    text = "\n".join([a_line(), a_line(gross="     78500")])
    assert duplicates(parse_file(text)) == ["HOME/A-10441"]


def test_the_same_number_at_two_sites_is_not_a_duplicate():
    text = "\n".join([a_line(), a_line(location="LURA")])
    assert duplicates(parse_file(text)) == []


def test_the_summary_of_a_clean_file():
    assert summary(parse_file(a_line())) == "1 tickets read, none refused"


def test_the_summary_names_what_went_wrong():
    text = "\n".join([a_line(), a_line(disposition="BORROWED")])
    text_summary = summary(parse_file(text))
    assert "1 refused" in text_summary
    assert "disposition" in text_summary
