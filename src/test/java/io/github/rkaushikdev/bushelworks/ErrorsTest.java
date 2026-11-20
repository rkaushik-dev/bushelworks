package io.github.rkaushikdev.bushelworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.commodity.UnknownCommodity;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractError;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementError;
import io.github.rkaushikdev.bushelworks.domain.tickets.TicketError;
import io.github.rkaushikdev.bushelworks.schedules.NoScheduleInEffect;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorsTest {

    @Test
    void everyDeliberateErrorDescendsFromTheRoot() {
        for (Class<?> type : List.of(
                UnitError.class,
                MoneyError.class,
                TicketError.class,
                ContractError.class,
                SettlementError.class,
                UnknownCommodity.class,
                NoScheduleInEffect.class)) {
            assertThat(BushelworksError.class).as(type.getSimpleName()).isAssignableFrom(type);
        }
    }

    @Test
    void oneCatchOfValidationErrorCatchesEveryValidationLeaf() {
        assertThat(ValidationError.class).isAssignableFrom(TicketError.class);
        assertThat(ValidationError.class).isAssignableFrom(UnitError.class);
        assertThat(RuntimeException.class).isAssignableFrom(ValidationError.class);
    }

    @Test
    void oneCatchOfLookupFailureCatchesEveryLookupLeaf() {
        assertThat(LookupFailure.class).isAssignableFrom(UnknownCommodity.class);
        assertThat(LookupFailure.class).isAssignableFrom(NoScheduleInEffect.class);
        assertThat(ValidationError.class.isAssignableFrom(LookupFailure.class)).isFalse();
    }

    @Test
    void aLookupFailureReadsCleanlyRatherThanQuoted() {
        LookupFailure error = new LookupFailure("no such producer: HOLLIS");
        assertThat(error).hasToString("no such producer: HOLLIS");
        assertThat(error.getMessage()).isEqualTo("no such producer: HOLLIS");
    }

    @Test
    void aSubclassThatNamesNoCodeGetsOneFromItsName() {
        assertThat(new ContractError("x").code()).isEqualTo("contract_error");
        assertThat(new TicketError("x").code()).isEqualTo("ticket_error");
        assertThat(new UnknownCommodity("x").code()).isEqualTo("unknown_commodity");
        assertThat(new NoScheduleInEffect("x").code()).isEqualTo("no_schedule_in_effect");
    }

    @Test
    void anExplicitCodeSurvivesTheDerivation() {
        assertThat(new LookupFailure("x").code()).isEqualTo("not_found");
        assertThat(new SettlementError("x").code()).isEqualTo("settlement_error");
    }

    @Test
    void errorsCarryAStableCode() {
        assertThat(new ValidationError("bad").code()).isEqualTo("validation_error");
        assertThat(new LookupFailure("gone").code()).isEqualTo("not_found");
        assertThat(new ConflictError("busy").code()).isEqualTo("conflict");
        assertThat(new ConfigurationError("nope").code()).isEqualTo("configuration_error");
    }

    @Test
    void aCodeCanBeOverriddenPerInstance() {
        ValidationError error = new ValidationError("tare exceeds gross", "tare_exceeds_gross");
        assertThat(error.code()).isEqualTo("tare_exceeds_gross");
        assertThat(error.asMap()).isEqualTo(Map.of("code", "tare_exceeds_gross", "message", "tare exceeds gross"));
    }

    @Test
    void catchingTheRootCatchesADomainError() {
        assertThatThrownBy(() -> {
                    throw new ContractError("F-1: no price");
                })
                .isInstanceOf(BushelworksError.class);
    }

    @Test
    void conflictIsNotAValidationError() {
        assertThat(ValidationError.class.isAssignableFrom(ConflictError.class)).isFalse();
    }
}
