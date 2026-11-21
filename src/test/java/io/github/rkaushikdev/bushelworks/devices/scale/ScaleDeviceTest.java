package io.github.rkaushikdev.bushelworks.devices.scale;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.devices.scale.Scales.FRAME_LENGTH;
import static io.github.rkaushikdev.bushelworks.devices.scale.Scales.buildFrame;
import static io.github.rkaushikdev.bushelworks.devices.scale.Scales.frames;
import static io.github.rkaushikdev.bushelworks.devices.scale.Scales.latest;
import static io.github.rkaushikdev.bushelworks.devices.scale.Scales.parseReading;
import static io.github.rkaushikdev.bushelworks.devices.scale.Scales.parseStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ScaleDeviceTest {

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] changed(byte[] frame, int index, char letter) {
        byte[] copy = frame.clone();
        copy[index] = (byte) letter;
        return copy;
    }

    @Test
    void aStableFrameReadsAsAWeight() {
        ScaleReading reading = parseReading(buildFrame(78420));
        assertThat(reading.pounds()).isEqualTo(d("78420"));
        assertThat(reading.stability()).isSameAs(Stability.STABLE);
        assertThat(reading.isUsable()).isTrue();
    }

    @Test
    void aFrameInMotionIsNotUsable() {
        ScaleReading reading = parseReading(buildFrame(78420, Stability.MOTION));
        assertThat(reading.isUsable()).isFalse();
        assertThat(reading.pounds()).isEqualTo(d("78420"));
    }

    @Test
    void anOverCapacityFrameSaysSo() {
        ScaleReading reading = parseReading(buildFrame(999999, Stability.OVER_CAPACITY));
        assertThat(reading.isOverCapacity()).isTrue();
        assertThat(reading.isUsable()).isFalse();
    }

    @Test
    void aNegativeWeightReadsNegative() {
        assertThat(parseReading(buildFrame(-240)).pounds()).isEqualTo(d("-240"));
    }

    @Test
    void aKilogramFrameIsConvertedToPounds() {
        ScaleReading reading = parseReading(buildFrame(1000, Stability.STABLE, "kg"));
        assertThat(reading.unit()).isEqualTo("kg");
        assertThat(reading.pounds()).isEqualTo(d("2205"));
    }

    @Test
    void aFrameOfTheWrongLengthIsRefused() {
        byte[] frame = buildFrame(78420);
        assertThatThrownBy(() -> parseReading(Arrays.copyOf(frame, frame.length - 1)))
                .isInstanceOf(IndicatorError.class);
    }

    @Test
    void aFrameWithoutStxIsRefused() {
        byte[] frame = buildFrame(78420);
        assertThatThrownBy(() -> parseReading(concat(new byte[] {0x03}, Arrays.copyOfRange(frame, 1, frame.length))))
                .isInstanceOf(IndicatorError.class);
    }

    @Test
    void aFrameWithoutCrIsRefused() {
        byte[] frame = buildFrame(78420);
        assertThatThrownBy(() -> parseReading(concat(Arrays.copyOf(frame, frame.length - 1), new byte[] {0x00})))
                .isInstanceOf(IndicatorError.class);
    }

    @Test
    void anUnknownStatusByteIsRefused() {
        byte[] frame = changed(buildFrame(78420), 1, 'Z');
        assertThatThrownBy(() -> parseReading(frame)).isInstanceOf(IndicatorError.class);
    }

    @Test
    void aMissingSignIsRefused() {
        byte[] frame = changed(buildFrame(78420), 2, ' ');
        assertThatThrownBy(() -> parseReading(frame)).isInstanceOf(IndicatorError.class);
    }

    @Test
    void aNonNumericWeightIsRefused() {
        byte[] frame = changed(buildFrame(78420), 5, 'X');
        assertThatThrownBy(() -> parseReading(frame)).isInstanceOf(IndicatorError.class);
    }

    @Test
    void anUnknownUnitIsRefused() {
        byte[] frame = changed(changed(buildFrame(78420), 9, 'o'), 10, 'z');
        assertThatThrownBy(() -> parseReading(frame)).isInstanceOf(IndicatorError.class);
    }

    @Test
    void aWeightTooBigForTheFrameIsRefused() {
        assertThatThrownBy(() -> buildFrame(1_000_000)).isInstanceOf(IndicatorError.class);
    }

    @Test
    void aBufferSplitsIntoWholeFrames() {
        byte[] buffer = concat(buildFrame(26180), buildFrame(78420));
        assertThat(frames(buffer)).hasSize(2);
        assertThat(frames(buffer)).allSatisfy(frame -> assertThat(frame).hasSize(FRAME_LENGTH));
    }

    @Test
    void aPartialFrameAtTheEndIsDropped() {
        byte[] buffer = concat(buildFrame(26180), Arrays.copyOf(buildFrame(78420), 6));
        assertThat(frames(buffer)).hasSize(1);
    }

    @Test
    void noiseBeforeTheFirstFrameIsSkipped() {
        byte[] buffer = concat(new byte[] {0x00, (byte) 0xFF, 0x41}, buildFrame(78420));
        assertThat(frames(buffer)).hasSize(1);
    }

    @Test
    void theStreamGivesTheMostRecentStableWeight() {
        byte[] buffer = concat(buildFrame(26180), buildFrame(50000, Stability.MOTION), buildFrame(78420));
        assertThat(parseStream(buffer)).get().extracting(ScaleReading::pounds).isEqualTo(d("78420"));
    }

    @Test
    void aStreamEndingInMotionFallsBackToTheLastStableOne() {
        byte[] buffer = concat(buildFrame(78420), buildFrame(50000, Stability.MOTION));
        assertThat(parseStream(buffer)).get().extracting(ScaleReading::pounds).isEqualTo(d("78420"));
    }

    @Test
    void aStreamWithNothingStableGivesNothing() {
        byte[] moving = buildFrame(50000, Stability.MOTION);
        assertThat(parseStream(concat(moving, moving, moving))).isEmpty();
    }

    @Test
    void anEmptyBufferGivesNothing() {
        assertThat(parseStream(new byte[0])).isEmpty();
        assertThat(latest(new byte[0])).isEmpty();
    }

    @Test
    void aCorruptedFrameInTheMiddleIsSteppedOver() {
        byte[] corrupted = changed(buildFrame(50000), 5, 'X');
        byte[] buffer = concat(buildFrame(78420), corrupted);
        assertThat(parseStream(buffer)).get().extracting(ScaleReading::pounds).isEqualTo(d("78420"));
    }

    @Test
    void latestReturnsAMovingWeightForTheWallDisplay() {
        byte[] buffer = concat(buildFrame(78420), buildFrame(50000, Stability.MOTION));
        ScaleReading reading = latest(buffer).orElseThrow();
        assertThat(reading.pounds()).isEqualTo(d("50000"));
        assertThat(reading.isUsable()).isFalse();
    }
}
