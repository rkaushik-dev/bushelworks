from decimal import Decimal

import pytest

from bushelworks.devices.scale import (
    FRAME_LENGTH,
    IndicatorError,
    Stability,
    build_frame,
    frames,
    latest,
    parse_reading,
    parse_stream,
)


def test_a_stable_frame_reads_as_a_weight():
    reading = parse_reading(build_frame(78420))
    assert reading.pounds == Decimal("78420")
    assert reading.stability is Stability.STABLE
    assert reading.is_usable


def test_a_frame_in_motion_is_not_usable():
    reading = parse_reading(build_frame(78420, Stability.MOTION))
    assert not reading.is_usable
    assert reading.pounds == Decimal("78420")


def test_an_over_capacity_frame_says_so():
    reading = parse_reading(build_frame(999999, Stability.OVER_CAPACITY))
    assert reading.is_over_capacity
    assert not reading.is_usable


def test_a_negative_weight_reads_negative():
    assert parse_reading(build_frame(-240)).pounds == Decimal("-240")


def test_a_kilogram_frame_is_converted_to_pounds():
    reading = parse_reading(build_frame(1000, unit="kg"))
    assert reading.unit == "kg"
    assert reading.pounds == Decimal("2205")


def test_a_frame_of_the_wrong_length_is_refused():
    with pytest.raises(IndicatorError):
        parse_reading(build_frame(78420)[:-1])


def test_a_frame_without_stx_is_refused():
    frame = build_frame(78420)
    with pytest.raises(IndicatorError):
        parse_reading(b"\x03" + frame[1:])


def test_a_frame_without_cr_is_refused():
    frame = build_frame(78420)
    with pytest.raises(IndicatorError):
        parse_reading(frame[:-1] + b"\x00")


def test_an_unknown_status_byte_is_refused():
    frame = bytearray(build_frame(78420))
    frame[1] = ord("Z")
    with pytest.raises(IndicatorError):
        parse_reading(bytes(frame))


def test_a_missing_sign_is_refused():
    frame = bytearray(build_frame(78420))
    frame[2] = ord(" ")
    with pytest.raises(IndicatorError):
        parse_reading(bytes(frame))


def test_a_non_numeric_weight_is_refused():
    frame = bytearray(build_frame(78420))
    frame[5] = ord("X")
    with pytest.raises(IndicatorError):
        parse_reading(bytes(frame))


def test_an_unknown_unit_is_refused():
    frame = bytearray(build_frame(78420))
    frame[9:11] = b"oz"
    with pytest.raises(IndicatorError):
        parse_reading(bytes(frame))


def test_a_weight_too_big_for_the_frame_is_refused():
    with pytest.raises(IndicatorError):
        build_frame(1_000_000)


def test_a_buffer_splits_into_whole_frames():
    buffer = build_frame(26180) + build_frame(78420)
    assert len(frames(buffer)) == 2
    assert all(len(frame) == FRAME_LENGTH for frame in frames(buffer))


def test_a_partial_frame_at_the_end_is_dropped():
    buffer = build_frame(26180) + build_frame(78420)[:6]
    assert len(frames(buffer)) == 1


def test_noise_before_the_first_frame_is_skipped():
    buffer = b"\x00\xff\x41" + build_frame(78420)
    assert len(frames(buffer)) == 1


def test_the_stream_gives_the_most_recent_stable_weight():
    buffer = (
        build_frame(26180)
        + build_frame(50000, Stability.MOTION)
        + build_frame(78420)
    )
    assert parse_stream(buffer).pounds == Decimal("78420")


def test_a_stream_ending_in_motion_falls_back_to_the_last_stable_one():
    buffer = build_frame(78420) + build_frame(50000, Stability.MOTION)
    assert parse_stream(buffer).pounds == Decimal("78420")


def test_a_stream_with_nothing_stable_gives_nothing():
    buffer = build_frame(50000, Stability.MOTION) * 3
    assert parse_stream(buffer) is None


def test_an_empty_buffer_gives_nothing():
    assert parse_stream(b"") is None
    assert latest(b"") is None


def test_a_corrupted_frame_in_the_middle_is_stepped_over():
    corrupted = bytearray(build_frame(50000))
    corrupted[5] = ord("X")
    buffer = build_frame(78420) + bytes(corrupted)
    assert parse_stream(buffer).pounds == Decimal("78420")


def test_latest_returns_a_moving_weight_for_the_wall_display():
    buffer = build_frame(78420) + build_frame(50000, Stability.MOTION)
    reading = latest(buffer)
    assert reading.pounds == Decimal("50000")
    assert not reading.is_usable
