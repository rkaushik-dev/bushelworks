from datetime import date

import pytest

from bushelworks.reports.spool import (
    FORM_FEED,
    PAGE_LINES,
    Document,
    Spool,
    SpoolError,
    manifest,
    safe_filename,
    to_matrix,
    to_pages,
    write_files,
)

DAY = date(2024, 10, 20)


def a_document(name="S-2024-0417", party="MERRICK", lines=20) -> Document:
    text = "\n".join(f"line {index}" for index in range(lines))
    return Document(name=name, party_code=party, text=text)


def a_spool() -> Spool:
    spool = Spool(day=DAY)
    spool.add(a_document())
    spool.add(a_document(name="S-2024-0418", party="HOLLIS"))
    return spool


def test_a_document_needs_a_name_and_something_to_print():
    with pytest.raises(SpoolError):
        Document(name="", party_code="MERRICK", text="anything")
    with pytest.raises(SpoolError):
        Document(name="S-1", party_code="MERRICK", text="   \n  ")


def test_a_document_counts_its_lines_and_pages():
    short = a_document(lines=20)
    assert short.lines == 20
    assert short.pages == 1


def test_a_long_document_runs_to_more_than_one_page():
    long = a_document(lines=PAGE_LINES + 5)
    assert long.pages == 2


def test_the_spool_counts_what_is_in_it():
    spool = a_spool()
    assert spool.count == 2
    assert spool.total_pages == 2


def test_the_same_document_twice_is_refused():
    spool = a_spool()
    with pytest.raises(SpoolError):
        spool.add(a_document())


def test_documents_can_be_pulled_out_by_producer():
    spool = a_spool()
    assert [d.name for d in spool.for_party("HOLLIS")] == ["S-2024-0418"]
    assert spool.parties() == ("HOLLIS", "MERRICK")


def test_the_matrix_stream_separates_with_a_form_feed():
    text = to_matrix(a_spool())
    assert text.count(FORM_FEED) == 1


def test_an_empty_spool_cannot_be_printed():
    with pytest.raises(SpoolError):
        to_matrix(Spool(day=DAY))
    with pytest.raises(SpoolError):
        to_pages(Spool(day=DAY))


def test_the_page_stream_pads_each_document_to_a_full_page():
    text = to_pages(a_spool())
    first = text.split(FORM_FEED)[0]
    assert len(first.split("\n")) == PAGE_LINES


def test_a_long_document_pads_to_the_next_whole_page():
    spool = Spool(day=DAY)
    spool.add(a_document(lines=PAGE_LINES + 5))
    first = to_pages(spool).split(FORM_FEED)[0]
    assert len(first.split("\n")) == PAGE_LINES * 2


def test_a_plain_name_makes_a_plain_filename():
    assert safe_filename("S-2024-0417") == "S-2024-0417.txt"


def test_an_awkward_name_is_cleaned():
    assert safe_filename("S 2024/0417") == "S-2024-0417.txt"


def test_a_name_with_nothing_usable_is_refused():
    with pytest.raises(SpoolError):
        safe_filename("///")


def test_the_suffix_can_be_changed():
    assert safe_filename("S-1", ".prn") == "S-1.prn"


def test_writing_files_puts_one_per_document_under_the_day(tmp_path):
    written = write_files(a_spool(), tmp_path)
    assert len(written) == 2
    assert all(path.parent.name == "2024-10-20" for path in written)


def test_the_written_file_holds_the_document(tmp_path):
    written = write_files(a_spool(), tmp_path)
    assert "line 0" in written[0].read_text(encoding="utf-8")


def test_writing_an_empty_spool_is_refused(tmp_path):
    with pytest.raises(SpoolError):
        write_files(Spool(day=DAY), tmp_path)


def test_writing_twice_overwrites_rather_than_duplicating(tmp_path):
    write_files(a_spool(), tmp_path)
    written = write_files(a_spool(), tmp_path)
    assert len(list((tmp_path / "2024-10-20").iterdir())) == len(written)


def test_the_manifest_lists_what_went_out():
    text = manifest(a_spool())
    assert "SPOOL 2024-10-20" in text
    assert "2 documents" in text
    assert "S-2024-0417" in text
    assert "HOLLIS" in text


def test_the_manifest_is_in_document_order():
    text = manifest(a_spool())
    rows = [line for line in text.split("\n") if line.startswith("S-")]
    assert rows == sorted(rows)


def test_an_empty_spool_still_has_a_manifest():
    text = manifest(Spool(day=DAY))
    assert "0 documents" in text
