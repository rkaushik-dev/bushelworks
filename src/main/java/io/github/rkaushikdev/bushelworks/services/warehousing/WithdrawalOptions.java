package io.github.rkaushikdev.bushelworks.services.warehousing;

import java.util.Objects;
import java.util.Optional;

/**
 * Everything a withdrawal takes beyond whose grain, how much and on what day.
 *
 * <pre>{@code
 * Warehousing.withdraw(uow, "MERRICK", "CORN", bushels, on,
 *         WithdrawalOptions.defaults().withSettlementNumber("S-2024-0417"));
 * }</pre>
 *
 * @param reason why the bushels went, {@code settled} unless said otherwise
 * @param settlementNumber the statement that paid for them, if one did
 * @param locationCode only lots at this site; empty for every site
 */
public record WithdrawalOptions(String reason, Optional<String> settlementNumber, Optional<String> locationCode) {

    /** The reason a withdrawal records when none is given. */
    public static final String SETTLED = "settled";

    public WithdrawalOptions {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(settlementNumber, "settlementNumber");
        Objects.requireNonNull(locationCode, "locationCode");
    }

    /** Settled, by no statement in particular, from any site. */
    public static WithdrawalOptions defaults() {
        return new WithdrawalOptions(SETTLED, Optional.empty(), Optional.empty());
    }

    public WithdrawalOptions withReason(String value) {
        return new WithdrawalOptions(value, settlementNumber, locationCode);
    }

    /** Null clears it. */
    public WithdrawalOptions withSettlementNumber(String value) {
        return new WithdrawalOptions(reason, Optional.ofNullable(value), locationCode);
    }

    /** Null or empty means every site. */
    public WithdrawalOptions withLocationCode(String value) {
        return new WithdrawalOptions(
                reason, settlementNumber, Optional.ofNullable(value).filter(code -> !code.isEmpty()));
    }
}
