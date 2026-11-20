package io.github.rkaushikdev.bushelworks.domain.contracts;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import io.github.rkaushikdev.bushelworks.domain.pricing.PricingError;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One purchase contract between the house and a producer, with whichever halves of its price are
 * set so far.
 *
 * <p>A contract is not priced until both halves are set. Everything downstream keys off {@link
 * #isFullyPriced()}, because an unpriced contract can take delivery but cannot pay for it.
 *
 * @param number the contract number
 * @param kind which halves of the price are fixed and when
 * @param partyId the producer
 * @param commodityCode the commodity
 * @param locationCode where delivery is taken
 * @param writtenOn the day it was written
 * @param bushels the contracted quantity
 * @param window when delivery may be made
 * @param futuresMonth the futures month the price is built on, when there is one
 * @param futuresPrice the futures half, once set
 * @param basis the basis half, once set
 * @param flatPrice the whole price, for a cash or forward contract
 * @param tolerancePercent how far delivery may run over or short and still fill
 * @param serviceRatePerBushel the deferred pricing fee per bushel per month started
 * @param status where the contract stands
 */
public record PurchaseContract(
        String number,
        ContractKind kind,
        String partyId,
        String commodityCode,
        String locationCode,
        LocalDate writtenOn,
        BigDecimal bushels,
        DeliveryWindow window,
        Optional<FuturesMonth> futuresMonth,
        Optional<BigDecimal> futuresPrice,
        Optional<BigDecimal> basis,
        Optional<BigDecimal> flatPrice,
        BigDecimal tolerancePercent,
        BigDecimal serviceRatePerBushel,
        ContractStatus status) {

    private static final BigDecimal MOST_TOLERANCE = new BigDecimal("25");
    private static final BigDecimal BUSHEL_PLACES = new BigDecimal("0.0001");
    private static final BigDecimal DAYS_A_MONTH = new BigDecimal("30");

    public PurchaseContract {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(writtenOn, "writtenOn");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(futuresMonth, "futuresMonth");
        Objects.requireNonNull(futuresPrice, "futuresPrice");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(flatPrice, "flatPrice");
        Objects.requireNonNull(serviceRatePerBushel, "serviceRatePerBushel");
        Objects.requireNonNull(status, "status");
        if (number.isEmpty()) {
            throw new ContractError("a contract needs a number");
        }
        if (bushels.signum() <= 0) {
            throw new ContractError(number + ": contracted bushels must be positive");
        }
        if (tolerancePercent.signum() < 0 || tolerancePercent.compareTo(MOST_TOLERANCE) > 0) {
            throw new ContractError(number + ": tolerance out of range");
        }
        if (!window.contains(window.opens())) {
            throw new ContractError(number + ": empty delivery window");
        }
        if (kind == ContractKind.CASH || kind == ContractKind.FORWARD) {
            if (flatPrice.isEmpty()) {
                throw new ContractError(number + ": a " + kind.value() + " needs a price");
            }
            if (flatPrice.get().signum() <= 0) {
                throw new ContractError(number + ": a flat price has to be positive");
            }
        }
        if (kind == ContractKind.HTA && (futuresMonth.isEmpty() || futuresPrice.isEmpty())) {
            throw new ContractError(number + ": a hedge to arrive needs a month and a futures price");
        }
        if (kind == ContractKind.BASIS && basis.isEmpty()) {
            throw new ContractError(number + ": a basis contract needs a basis");
        }
        if (kind == ContractKind.DEFERRED && flatPrice.isPresent()) {
            throw new ContractError(number + ": a deferred price contract cannot carry a price");
        }
        if (futuresMonth.isPresent() && !futuresMonth.get().commodityCode().equals(commodityCode)) {
            throw new ContractError(number + ": " + futuresMonth.get().symbol() + " is the wrong commodity");
        }
    }

    /** A contract built a field at a time; tolerance two percent, no service fee, open. */
    public static Builder builder() {
        return new Builder();
    }

    /** This contract, ready to have a field or two changed. */
    public Builder toBuilder() {
        return new Builder()
                .number(number)
                .kind(kind)
                .partyId(partyId)
                .commodityCode(commodityCode)
                .locationCode(locationCode)
                .writtenOn(writtenOn)
                .bushels(bushels)
                .window(window)
                .futuresMonth(futuresMonth.orElse(null))
                .futuresPrice(futuresPrice.orElse(null))
                .basis(basis.orElse(null))
                .flatPrice(flatPrice.orElse(null))
                .tolerancePercent(tolerancePercent)
                .serviceRatePerBushel(serviceRatePerBushel)
                .status(status);
    }

    public boolean isFullyPriced() {
        if (flatPrice.isPresent()) {
            return true;
        }
        return futuresPrice.isPresent() && basis.isPresent();
    }

    /** The contract price per bushel, or a refusal to guess. */
    public BigDecimal unitPrice() {
        if (flatPrice.isPresent()) {
            return Money.price(flatPrice.get());
        }
        if (futuresPrice.isEmpty() || basis.isEmpty()) {
            throw new ContractError(number + ": contract is not fully priced yet");
        }
        return Units.quantize(futuresPrice.get().add(basis.get()), Money.PRICE_PLACES);
    }

    /** The fewest bushels that fill the contract inside its tolerance. */
    public BigDecimal minimumBushels() {
        BigDecimal floor =
                Decimals.divide(bushels.multiply(Decimals.HUNDRED.subtract(tolerancePercent)), Decimals.HUNDRED);
        return Units.quantize(floor, BUSHEL_PLACES);
    }

    /** The most bushels the contract takes inside its tolerance. */
    public BigDecimal maximumBushels() {
        BigDecimal ceiling =
                Decimals.divide(bushels.multiply(Decimals.HUNDRED.add(tolerancePercent)), Decimals.HUNDRED);
        return Units.quantize(ceiling, BUSHEL_PLACES);
    }

    public boolean acceptsDeliveryOn(LocalDate day) {
        return status == ContractStatus.OPEN && window.contains(day);
    }

    /** Fix the basis half of a hedge to arrive. */
    public PurchaseContract setBasis(BigDecimal value, LocalDate on) {
        if (kind != ContractKind.HTA) {
            throw new ContractError(number + ": only a hedge to arrive sets a basis");
        }
        if (basis.isPresent()) {
            throw new ContractError(number + ": basis is already set");
        }
        if (on.isAfter(window.closes())) {
            throw new ContractError(number + ": basis cannot be set after the window");
        }
        return toBuilder().basis(Money.price(value)).build();
    }

    /** Fix the futures half of a basis contract. */
    public PurchaseContract setFutures(BigDecimal futures, LocalDate on) {
        if (kind != ContractKind.BASIS) {
            throw new ContractError(number + ": only a basis contract sets futures");
        }
        if (futuresPrice.isPresent()) {
            throw new ContractError(number + ": futures are already set");
        }
        if (futures.signum() <= 0) {
            throw new PricingError(number + ": futures price has to be positive");
        }
        if (on.isAfter(window.closes())) {
            throw new ContractError(number + ": futures cannot be set after the window");
        }
        return toBuilder().futuresPrice(Money.price(futures)).build();
    }

    /**
     * Move an unpriced futures half to a later month, paying the spread.
     *
     * <p>Rolling is only legal forward. A producer who wants to roll back is asking the house to
     * carry his position for him, and the house does not.
     */
    public PurchaseContract rollTo(FuturesMonth month, BigDecimal spread) {
        if (futuresMonth.isEmpty() || futuresPrice.isEmpty()) {
            throw new ContractError(number + ": nothing to roll");
        }
        if (!futuresMonth.get().isBefore(month)) {
            throw new ContractError(
                    number + ": cannot roll back from " + futuresMonth.get().symbol() + " to " + month.symbol());
        }
        return toBuilder()
                .futuresMonth(month)
                .futuresPrice(Units.quantize(futuresPrice.get().add(spread), Money.PRICE_PLACES))
                .build();
    }

    public PurchaseContract cancel() {
        if (status != ContractStatus.OPEN) {
            throw new ContractError(number + ": only an open contract can be cancelled");
        }
        return toBuilder().status(ContractStatus.CANCELLED).build();
    }

    /** The deferred price service fee, charged per bushel per month started. */
    public BigDecimal serviceCharge(BigDecimal onBushels, int days) {
        if (serviceRatePerBushel.signum() == 0 || days <= 0) {
            return Money.ZERO_AMOUNT;
        }
        BigDecimal months =
                Decimals.divide(BigDecimal.valueOf(days), DAYS_A_MONTH).setScale(0, RoundingMode.CEILING);
        return Money.extend(onBushels, serviceRatePerBushel.multiply(months));
    }

    /** A contract assembled a field at a time. */
    public static final class Builder {

        private String number;
        private ContractKind kind;
        private String partyId;
        private String commodityCode;
        private String locationCode;
        private LocalDate writtenOn;
        private BigDecimal bushels;
        private DeliveryWindow window;
        private FuturesMonth futuresMonth;
        private BigDecimal futuresPrice;
        private BigDecimal basis;
        private BigDecimal flatPrice;
        private BigDecimal tolerancePercent = new BigDecimal("2");
        private BigDecimal serviceRatePerBushel = BigDecimal.ZERO;
        private ContractStatus status = ContractStatus.OPEN;

        private Builder() {}

        public Builder number(String value) {
            this.number = value;
            return this;
        }

        public Builder kind(ContractKind value) {
            this.kind = value;
            return this;
        }

        public Builder partyId(String value) {
            this.partyId = value;
            return this;
        }

        public Builder commodityCode(String value) {
            this.commodityCode = value;
            return this;
        }

        public Builder locationCode(String value) {
            this.locationCode = value;
            return this;
        }

        public Builder writtenOn(LocalDate value) {
            this.writtenOn = value;
            return this;
        }

        public Builder bushels(BigDecimal value) {
            this.bushels = value;
            return this;
        }

        public Builder window(DeliveryWindow value) {
            this.window = value;
            return this;
        }

        /** The futures month, or null for none. */
        public Builder futuresMonth(FuturesMonth value) {
            this.futuresMonth = value;
            return this;
        }

        /** The futures half, or null while it is not set. */
        public Builder futuresPrice(BigDecimal value) {
            this.futuresPrice = value;
            return this;
        }

        /** The basis half, or null while it is not set. */
        public Builder basis(BigDecimal value) {
            this.basis = value;
            return this;
        }

        /** The flat price, or null for none. */
        public Builder flatPrice(BigDecimal value) {
            this.flatPrice = value;
            return this;
        }

        public Builder tolerancePercent(BigDecimal value) {
            this.tolerancePercent = value;
            return this;
        }

        public Builder serviceRatePerBushel(BigDecimal value) {
            this.serviceRatePerBushel = value;
            return this;
        }

        public Builder status(ContractStatus value) {
            this.status = value;
            return this;
        }

        public PurchaseContract build() {
            return new PurchaseContract(
                    number,
                    kind,
                    partyId,
                    commodityCode,
                    locationCode,
                    writtenOn,
                    bushels,
                    window,
                    Optional.ofNullable(futuresMonth),
                    Optional.ofNullable(futuresPrice),
                    Optional.ofNullable(basis),
                    Optional.ofNullable(flatPrice),
                    tolerancePercent,
                    serviceRatePerBushel,
                    status);
        }
    }
}
