package io.github.rkaushikdev.bushelworks.domain.bins;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BinsTest {

    static final Map<Factor, BigDecimal> SAMPLE = sample("14.0", "56.0");
    static final Map<Factor, BigDecimal> WET = sample("18.0", "54.0");

    static Map<Factor, BigDecimal> sample(String moisture, String testWeight) {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.MOISTURE, d(moisture));
        sample.put(Factor.TEST_WEIGHT, d(testWeight));
        return sample;
    }

    static Bin aBin() {
        return Bin.empty("B-04", "HOME", d("100000"));
    }

    static Bin aBin(String binId, String capacity) {
        return Bin.empty(binId, "HOME", d(capacity));
    }

    static BinYard aYard() {
        return new BinYard("HOME", List.of(aBin("B-01", "40000"), aBin("B-02", "100000"), aBin("B-03", "250000")));
    }

    @Test
    void aBinNeedsACapacity() {
        assertThatThrownBy(() -> aBin("B-04", "0")).isInstanceOf(BinError.class);
    }

    @Test
    void grainInABinHasToBeSomething() {
        assertThatThrownBy(() -> aBin().holding(null, d("1000"), Map.of())).isInstanceOf(BinError.class);
    }

    @Test
    void aBinCannotHoldMoreThanItHolds() {
        assertThatThrownBy(() -> aBin().holding("CORN", d("200000"), Map.of())).isInstanceOf(BinError.class);
    }

    @Test
    void anEmptyBinHasWorkingHeadroom() {
        Bin empty = aBin();
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.workingCapacity()).isEqualTo(d("97000.0000"));
        assertThat(empty.headroom()).isEqualTo(d("97000.0000"));
        assertThat(empty.percentFull()).isEqualTo(d("0.0"));
    }

    @Test
    void fillingPutsGrainInAndSetsTheCommodity() {
        Bin filled = aBin().fill("CORN", d("40000"), SAMPLE);
        assertThat(filled.commodityCode()).contains("CORN");
        assertThat(filled.bushels()).isEqualTo(d("40000.0000"));
        assertThat(filled.sample().get(Factor.MOISTURE)).isEqualTo(d("14.0"));
    }

    @Test
    void aSecondLoadBlendsWithTheFirst() {
        Bin filled = aBin().fill("CORN", d("40000"), SAMPLE).fill("CORN", d("40000"), WET);
        assertThat(filled.bushels()).isEqualTo(d("80000.0000"));
        assertThat(filled.sample().get(Factor.MOISTURE)).isEqualTo(d("16.00"));
        assertThat(filled.sample().get(Factor.TEST_WEIGHT)).isEqualTo(d("55.00"));
    }

    @Test
    void theBlendIsWeightedByBushels() {
        Bin filled = aBin().fill("CORN", d("60000"), SAMPLE).fill("CORN", d("20000"), WET);
        assertThat(filled.sample().get(Factor.MOISTURE)).isEqualTo(d("15.00"));
    }

    @Test
    void aFactorOnlyOneLoadHadDropsOutOfTheBlend() {
        Bin filled =
                aBin().fill("CORN", d("40000"), SAMPLE).fill("CORN", d("40000"), Map.of(Factor.MOISTURE, d("18.0")));
        assertThat(filled.sample()).doesNotContainKey(Factor.TEST_WEIGHT);
    }

    @Test
    void aBinWillNotTakeAnotherCommodity() {
        Bin filled = aBin().fill("CORN", d("40000"), SAMPLE);
        assertThatThrownBy(() -> filled.fill("SOYB", d("1000"), SAMPLE)).isInstanceOf(ConflictError.class);
        assertThat(filled.accepts("SOYB", d("1000"))).isFalse();
    }

    @Test
    void aBinWillNotTakeMoreThanItsHeadroom() {
        Bin filled = aBin().fill("CORN", d("90000"), SAMPLE);
        assertThat(filled.headroom()).isEqualTo(d("7000.0000"));
        assertThatThrownBy(() -> filled.fill("CORN", d("8000"), SAMPLE)).isInstanceOf(BinError.class);
    }

    @Test
    void theLastThreePercentIsNotWorkingCapacity() {
        Bin filled = aBin().fill("CORN", d("97000"), SAMPLE);
        assertThat(filled.isFull()).isTrue();
        assertThat(filled.percentFull()).isEqualTo(d("97.0"));
    }

    @Test
    void fillingNothingIsRefused() {
        assertThatThrownBy(() -> aBin().fill("CORN", d("0"), SAMPLE)).isInstanceOf(BinError.class);
    }

    @Test
    void drawingTakesGrainOutAndLeavesTheSample() {
        Bin filled = aBin().fill("CORN", d("40000"), SAMPLE);
        Bin drawn = filled.draw(d("15000"));
        assertThat(drawn.bushels()).isEqualTo(d("25000.0000"));
        assertThat(drawn.sample()).isEqualTo(filled.sample());
    }

    @Test
    void emptyingABinClearsTheCommodity() {
        Bin empty = aBin().fill("CORN", d("40000"), SAMPLE).draw(d("40000"));
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.commodityCode()).isEqualTo(Optional.empty());
        assertThat(empty.sample()).isEmpty();
    }

    @Test
    void drawingMoreThanIsThereIsRefused() {
        Bin filled = aBin().fill("CORN", d("40000"), SAMPLE);
        assertThatThrownBy(() -> filled.draw(d("40001"))).isInstanceOf(BinError.class);
    }

    @Test
    void drawingNothingIsRefused() {
        assertThatThrownBy(() -> aBin().fill("CORN", d("100"), SAMPLE).draw(d("0")))
                .isInstanceOf(BinError.class);
    }

    @Test
    void aBinOutOfServiceTakesNothing() {
        Bin out = aBin().takeOutOfService("fan motor");
        assertThat(out.accepts("CORN", d("100"))).isFalse();
        assertThatThrownBy(() -> out.fill("CORN", d("100"), SAMPLE)).isInstanceOf(ConflictError.class);
    }

    @Test
    void takingABinOutTwiceIsAConflict() {
        Bin out = aBin().takeOutOfService("fan motor");
        assertThatThrownBy(() -> out.takeOutOfService("again")).isInstanceOf(ConflictError.class);
    }

    @Test
    void aBinCanComeBack() {
        Bin back = aBin().takeOutOfService("fan motor").returnToService();
        assertThat(back.accepts("CORN", d("100"))).isTrue();
        assertThatThrownBy(back::returnToService).isInstanceOf(ConflictError.class);
    }

    @Test
    void takingABinOutNeedsAReason() {
        assertThatThrownBy(() -> aBin().takeOutOfService("")).isInstanceOf(BinError.class);
    }

    @Test
    void aYardHoldsItsBinsInOrder() {
        assertThat(aYard().bins()).extracting(Bin::binId).containsExactly("B-01", "B-02", "B-03");
    }

    @Test
    void aBinFromAnotherSiteIsRefused() {
        assertThatThrownBy(() -> new BinYard("HOME", List.of(Bin.empty("B-04", "LURAY", d("100000")))))
                .isInstanceOf(BinError.class);
    }

    @Test
    void theSameBinTwiceIsAConflict() {
        BinYard yard = aYard();
        assertThatThrownBy(() -> yard.add(aBin("B-01", "40000"))).isInstanceOf(ConflictError.class);
    }

    @Test
    void aMissingBinSaysSo() {
        assertThatThrownBy(() -> aYard().get("B-99")).isInstanceOf(BinError.class);
    }

    @Test
    void theYardTotalsWhatIsInIt() {
        BinYard yard = aYard();
        yard.replace(yard.get("B-02").fill("CORN", d("50000"), SAMPLE));
        yard.replace(yard.get("B-03").fill("SOYB", d("20000"), SAMPLE));
        assertThat(yard.totalBushels()).isEqualTo(d("70000.0000"));
        assertThat(yard.totalBushels("CORN")).isEqualTo(d("50000.0000"));
    }

    @Test
    void theYardReportsWhichBinsHoldACommodity() {
        BinYard yard = aYard();
        yard.replace(yard.get("B-02").fill("CORN", d("50000"), SAMPLE));
        assertThat(yard.holding("CORN")).extracting(Bin::binId).containsExactly("B-02");
    }

    @Test
    void roomForACommoditySkipsTheBinsHoldingSomethingElse() {
        BinYard yard = aYard();
        yard.replace(yard.get("B-03").fill("SOYB", d("20000"), SAMPLE));
        assertThat(yard.roomFor("CORN")).isEqualTo(d("135800.0000"));
    }

    @Test
    void roomSkipsABinOutOfService() {
        BinYard yard = aYard();
        yard.replace(yard.get("B-01").takeOutOfService("fan motor"));
        assertThat(yard.roomFor("CORN")).isEqualTo(d("339500.0000"));
    }

    @Test
    void aLoadGoesIntoABinAlreadyHoldingTheCommodity() {
        BinYard yard = aYard();
        yard.replace(yard.get("B-02").fill("CORN", d("50000"), SAMPLE));
        assertThat(yard.bestBinFor("CORN", d("1000")).map(Bin::binId)).contains("B-02");
    }

    @Test
    void theFullestMatchingBinGetsFinishedFirst() {
        BinYard yard = aYard();
        yard.replace(yard.get("B-02").fill("CORN", d("20000"), SAMPLE));
        yard.replace(yard.get("B-03").fill("CORN", d("50000"), SAMPLE));
        assertThat(yard.bestBinFor("CORN", d("1000")).map(Bin::binId)).contains("B-03");
    }

    @Test
    void anEmptyYardPutsALoadInTheSmallestBinThatTakesIt() {
        assertThat(aYard().bestBinFor("CORN", d("1000")).map(Bin::binId)).contains("B-01");
    }

    @Test
    void aLoadNothingCanTakeFindsNoBin() {
        assertThat(aYard().bestBinFor("CORN", d("500000"))).isEmpty();
    }
}
