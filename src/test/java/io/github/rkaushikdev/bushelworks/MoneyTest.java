package io.github.rkaushikdev.bushelworks;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.TestDecimals.ds;
import static io.github.rkaushikdev.bushelworks.TestDecimals.sum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void priceCarriesFourDecimals() {
        assertThat(Money.price("4.7325")).isEqualTo(d("4.7325"));
        assertThat(Money.price("-0.225")).isEqualTo(d("-0.2250"));
    }

    @Test
    void amountRoundsHalfUpToCents() {
        assertThat(Money.amount("12.345")).isEqualTo(d("12.35"));
        assertThat(Money.amount("12.344")).isEqualTo(d("12.34"));
    }

    @Test
    void extendRoundsOnceAtTheEnd() {
        // 932.8571 bu at 4.7325 is 4414.7462... and must not be rounded twice
        assertThat(Money.extend(d("932.8571"), d("4.7325"))).isEqualTo(d("4414.75"));
    }

    @Test
    void totalDoesNotReroundParts() {
        assertThat(Money.total(List.of(d("1.005"), d("1.005")))).isEqualTo(d("2.01"));
    }

    @Test
    void allocateSplitsEvenlyWhenItCan() {
        assertThat(Money.allocate(d("100.00"), List.of(d(1), d(1))))
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(d("50.00"), d("50.00"));
    }

    @Test
    void allocateGivesTheOddCentToTheFirstShare() {
        List<BigDecimal> parts = Money.allocate(d("100.01"), List.of(d(1), d(1)));
        assertThat(parts).usingElementComparator(BigDecimal::compareTo).containsExactly(d("50.01"), d("50.00"));
        assertThat(sum(parts)).isEqualByComparingTo("100.01");
    }

    @Test
    void allocateThreeWayThirdsStillBalances() {
        List<BigDecimal> parts = Money.allocate(d("1000.00"), List.of(d(1), d(1), d(1)));
        assertThat(sum(parts)).isEqualByComparingTo("1000.00");
    }

    @Test
    void allocateRespectsUnevenWeights() {
        List<BigDecimal> parts = Money.allocate(d("1000.00"), ds("60", "40"));
        assertThat(parts).usingElementComparator(BigDecimal::compareTo).containsExactly(d("600.00"), d("400.00"));
    }

    @Test
    void allocateHandlesANegativeAmount() {
        List<BigDecimal> parts = Money.allocate(d("-100.01"), List.of(d(1), d(1)));
        assertThat(sum(parts)).isEqualByComparingTo("-100.01");
    }

    @Test
    void allocateRejectsZeroWeights() {
        assertThatThrownBy(() -> Money.allocate(d("10.00"), List.of(d(0), d(0))))
                .isInstanceOf(MoneyError.class);
    }

    @Test
    void allocateRejectsNoWeights() {
        assertThatThrownBy(() -> Money.allocate(d("10.00"), List.of())).isInstanceOf(MoneyError.class);
    }

    @Test
    void negateAndFormatting() {
        assertThat(Money.negate(d("12.34"))).isEqualTo(d("-12.34"));
        assertThat(Money.formatAmount(d("-1234.5"))).isEqualTo("(1,234.50)");
        assertThat(Money.formatAmount(d("1234.5"))).isEqualTo("1,234.50");
        assertThat(Money.formatPrice(d("4.7"))).isEqualTo("4.7000");
    }
}
