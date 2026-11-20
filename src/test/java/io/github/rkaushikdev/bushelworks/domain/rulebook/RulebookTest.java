package io.github.rkaushikdev.bushelworks.domain.rulebook;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConfigurationError;
import io.github.rkaushikdev.bushelworks.domain.application.OverDelivery;
import io.github.rkaushikdev.bushelworks.domain.application.SelectionRule;
import io.github.rkaushikdev.bushelworks.domain.moisture.DryingSchedule;
import io.github.rkaushikdev.bushelworks.domain.moisture.DryingTier;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkMethod;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkSchedule;
import io.github.rkaushikdev.bushelworks.domain.storage.AccrualMethod;
import io.github.rkaushikdev.bushelworks.domain.storage.StorageRate;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulebookTest {

    static final ShrinkSchedule CORN_SHRINK_2023 =
            ShrinkSchedule.of("CORN", d("15.0")).withMethod(ShrinkMethod.TABLE).withShrinkFactor(d("1.20"));
    static final ShrinkSchedule CORN_SHRINK_2024 =
            ShrinkSchedule.of("CORN", d("15.0")).withMethod(ShrinkMethod.TABLE).withShrinkFactor(d("1.18"));
    static final DryingSchedule CORN_DRYER = DryingSchedule.of("CORN", List.of(DryingTier.open(d("0.0450"))));
    static final StorageRate CORN_STORAGE =
            StorageRate.of("CORN", d("0.0450")).withMethod(AccrualMethod.DAILY).withFreeDays(30);

    static RuleBook book() {
        RuleBook rb = new RuleBook(HousePolicy.of("KS"));
        rb.fileShrink("CORN", CORN_SHRINK_2023, LocalDate.of(2023, 8, 1));
        rb.fileShrink("CORN", CORN_SHRINK_2024, LocalDate.of(2024, 8, 1));
        rb.fileDrying("CORN", CORN_DRYER, LocalDate.of(2023, 8, 1));
        rb.fileStorage("CORN", CORN_STORAGE, LocalDate.of(2023, 8, 1));
        return rb;
    }

    @Test
    void theTicketDatePicksTheShrinkTable() {
        RuleBook rb = book();
        assertThat(rb.shrinkFor("CORN", LocalDate.of(2024, 7, 31)).shrinkFactor())
                .isEqualByComparingTo(d("1.20"));
        assertThat(rb.shrinkFor("CORN", LocalDate.of(2024, 8, 1)).shrinkFactor())
                .isEqualByComparingTo(d("1.18"));
    }

    @Test
    void aBranchShrinkTableWinsOverANewerCompanyOne() {
        RuleBook rb = book();
        rb.fileShrink(
                "CORN",
                ShrinkSchedule.of("CORN", d("15.0")).withShrinkFactor(d("1.25")),
                LocalDate.of(2023, 9, 1),
                "LURAY");
        assertThat(rb.shrinkFor("CORN", LocalDate.of(2024, 10, 1), "LURAY").shrinkFactor())
                .isEqualByComparingTo(d("1.25"));
        assertThat(rb.shrinkFor("CORN", LocalDate.of(2024, 10, 1), "HOME").shrinkFactor())
                .isEqualByComparingTo(d("1.18"));
    }

    @Test
    void dryingIsOptionalAndMissingMeansNothing() {
        RuleBook rb = book();
        assertThat(rb.dryingFor("CORN", LocalDate.of(2024, 10, 1))).isPresent();
        assertThat(rb.dryingFor("SOYB", LocalDate.of(2024, 10, 1))).isEmpty();
    }

    @Test
    void storageIsOptionalToo() {
        RuleBook rb = book();
        assertThat(rb.storageFor("CORN", LocalDate.of(2024, 10, 1))).isPresent();
        assertThat(rb.storageFor("HRW", LocalDate.of(2024, 10, 1))).isEmpty();
    }

    @Test
    void coversTurnsOnTheShrinkTable() {
        RuleBook rb = book();
        assertThat(rb.covers("CORN", LocalDate.of(2024, 10, 1), "HOME")).isTrue();
        assertThat(rb.covers("SOYB", LocalDate.of(2024, 10, 1), "HOME")).isFalse();
        assertThat(rb.covers("CORN", LocalDate.of(2023, 1, 1), "HOME")).isFalse();
    }

    @Test
    void aScheduleCannotBeFiledUnderTheWrongCommodity() {
        RuleBook rb = new RuleBook(HousePolicy.of("KS"));
        LocalDate on = LocalDate.of(2024, 8, 1);
        assertThatThrownBy(() -> rb.fileShrink("SOYB", CORN_SHRINK_2024, on)).isInstanceOf(ConfigurationError.class);
        assertThatThrownBy(() -> rb.fileDrying("SOYB", CORN_DRYER, on)).isInstanceOf(ConfigurationError.class);
        assertThatThrownBy(() -> rb.fileStorage("SOYB", CORN_STORAGE, on)).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void policyDefaultsAreTheConservativeOnes() {
        HousePolicy policy = HousePolicy.of("KS");
        assertThat(policy.selectionRule()).isSameAs(SelectionRule.WINDOW);
        assertThat(policy.overDelivery()).isSameAs(OverDelivery.STORE);
        assertThat(policy.roundSettlementToCents()).isTrue();
    }

    @Test
    void aBadStateCodeIsRefused() {
        assertThatThrownBy(() -> HousePolicy.of("KANSAS")).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aNegativeMinimumSettlementIsRefused() {
        assertThatThrownBy(() -> HousePolicy.of("KS").withMinimumSettlement(d("-1")))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void anEmptyBookStillAnswersTheOptionalLookups() {
        RuleBook rb = new RuleBook(HousePolicy.of("NE"));
        assertThat(rb.dryingFor("CORN", LocalDate.of(2024, 10, 1))).isEmpty();
        assertThat(rb.storageFor("CORN", LocalDate.of(2024, 10, 1))).isEmpty();
        assertThat(rb.discounts().factors("CORN")).isEmpty();
        assertThat(rb.assessments().codes()).isEmpty();
    }
}
