package io.github.rkaushikdev.bushelworks.devices.moisture;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One test off the grading bench.
 *
 * @param sampleId the sample id the operator keyed, colons and all
 * @param commodityCode the house's code for the calibration the test ran on
 * @param calibration the meter's calibration name, without its version
 * @param moisture the moisture reading, in percent
 * @param testWeight the test weight in pounds per bushel, when the meter reported one
 * @param temperature the sample temperature in degrees Celsius, when the meter reported one
 * @param instrument the model line the report opens with
 */
public record MeterReading(
        String sampleId,
        String commodityCode,
        String calibration,
        BigDecimal moisture,
        Optional<BigDecimal> testWeight,
        Optional<BigDecimal> temperature,
        String instrument) {

    public MeterReading {
        Objects.requireNonNull(sampleId, "sampleId");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(calibration, "calibration");
        Objects.requireNonNull(moisture, "moisture");
        Objects.requireNonNull(testWeight, "testWeight");
        Objects.requireNonNull(temperature, "temperature");
        Objects.requireNonNull(instrument, "instrument");
    }

    /** The readings as grade factors, moisture first, ready to go onto a ticket. */
    public Map<Factor, BigDecimal> asSample() {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.MOISTURE, moisture);
        testWeight.ifPresent(value -> sample.put(Factor.TEST_WEIGHT, value));
        return sample;
    }

    /** Whether this test was run on the right calibration. */
    public boolean matches(String commodity) {
        return commodityCode.equals(commodity);
    }
}
