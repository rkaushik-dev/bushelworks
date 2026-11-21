package io.github.rkaushikdev.bushelworks.services.charges;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.BushelworksError;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The charges and credits a statement carries besides the grain, and what each one refuses. */
class ChargesTest {

    static final LocalDate SEPTEMBER = LocalDate.of(2024, 9, 12);

    @Test
    void anAdvanceHasToBePositive() {
        assertThatThrownBy(() -> new Advance("2024-0912", d("0.00"), SEPTEMBER))
                .isInstanceOf(SettlementRefused.class)
                .hasMessage("2024-0912: an advance has to be positive");
    }

    @Test
    void anAdvanceNeedsAReference() {
        assertThatThrownBy(() -> new Advance("", d("1500.00"), SEPTEMBER))
                .isInstanceOf(SettlementRefused.class)
                .hasMessage("an advance needs a reference");
    }

    @Test
    void aStorageChargeMayBeNothingButNeverNegative() {
        assertThat(new StorageCharge("L-1", d("0.00"), SEPTEMBER).dollars()).isEqualTo(d("0.00"));
        assertThatThrownBy(() -> new StorageCharge("L-1", d("-0.01"), SEPTEMBER))
                .isInstanceOf(SettlementRefused.class)
                .hasMessage("L-1: a storage charge cannot be negative");
    }

    @Test
    void aDeductionIsPositiveAndSaysWhy() {
        assertThat(new Deduction("FRT", "freight paid", d("85.00")).dollars()).isEqualTo(d("85.00"));
        assertThatThrownBy(() -> new Deduction("FRT", "freight paid", d("-85.00")))
                .isInstanceOf(SettlementRefused.class)
                .hasMessage("FRT: a deduction has to be positive");
        assertThatThrownBy(() -> new Deduction("FRT", "", d("85.00")))
                .isInstanceOf(SettlementRefused.class)
                .hasMessage("FRT: a deduction needs a description");
    }

    @Test
    void aRefusalCarriesItsOwnCode() {
        BushelworksError refused = new SettlementRefused("no");
        assertThat(refused.code()).isEqualTo("settlement_refused");
    }

    @Test
    void theDiscountBasisIsWrittenByItsValue() {
        assertThat(DiscountBasis.of("dry")).isSameAs(DiscountBasis.DRY);
        assertThat(DiscountBasis.WET.value()).isEqualTo("wet");
    }
}
