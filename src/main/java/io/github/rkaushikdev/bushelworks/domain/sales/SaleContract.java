package io.github.rkaushikdev.bushelworks.domain.sales;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One sale out of the house, with what has shipped against it.
 *
 * <p>A sale is not the mirror image of a purchase. It is shipped rather than delivered, so it is
 * measured at destination and the house carries the shrink and the freight in between. It is priced
 * against the same futures month but on the other side, so the basis is the house's margin rather
 * than its cost. And shipping tolerance works the other way round: a sale contract obliges the house
 * to load the full amount, and the tolerance is the buyer's to use.
 *
 * @param number the sale number
 * @param kind spot, forward or basis
 * @param buyer who bought it
 * @param destination what kind of buyer
 * @param commodityCode the commodity
 * @param originCode the site it ships from
 * @param writtenOn when it was written
 * @param bushels the face amount
 * @param window the shipping window
 * @param specification what the buyer will accept
 * @param carrier how it moves
 * @param futuresMonth the futures month it is priced against, when there is one
 * @param futuresPrice the futures half, once set
 * @param basis the basis half, once set
 * @param flatPrice the whole price, for a flat priced sale
 * @param tolerancePercent how far over the face amount the last car may run
 * @param freightPerBushel what the house pays to get it there
 * @param shipped bushels loaded so far
 */
public record SaleContract(
        String number,
        SaleKind kind,
        String buyer,
        Destination destination,
        String commodityCode,
        String originCode,
        LocalDate writtenOn,
        BigDecimal bushels,
        DeliveryWindow window,
        Specification specification,
        Carrier carrier,
        Optional<FuturesMonth> futuresMonth,
        Optional<BigDecimal> futuresPrice,
        Optional<BigDecimal> basis,
        Optional<BigDecimal> flatPrice,
        BigDecimal tolerancePercent,
        BigDecimal freightPerBushel,
        BigDecimal shipped) {

    private static final BigDecimal MOST_TOLERANCE = new BigDecimal("10");

    public SaleContract {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(buyer, "buyer");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(originCode, "originCode");
        Objects.requireNonNull(writtenOn, "writtenOn");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(specification, "specification");
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(futuresMonth, "futuresMonth");
        Objects.requireNonNull(futuresPrice, "futuresPrice");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(flatPrice, "flatPrice");
        if (number.isEmpty()) {
            throw new SaleError("a sale needs a number");
        }
        if (bushels.signum() <= 0) {
            throw new SaleError(number + ": a sale needs bushels");
        }
        if (shipped.signum() < 0) {
            throw new SaleError(number + ": shipped cannot be negative");
        }
        if (tolerancePercent.signum() < 0 || tolerancePercent.compareTo(MOST_TOLERANCE) > 0) {
            throw new SaleError(number + ": tolerance out of range");
        }
        if (freightPerBushel.signum() < 0) {
            throw new SaleError(number + ": freight cannot be negative");
        }
        if (kind == SaleKind.BASIS && basis.isEmpty()) {
            throw new SaleError(number + ": a basis sale needs a basis");
        }
        if (kind != SaleKind.BASIS && flatPrice.isEmpty() && (futuresPrice.isEmpty() || basis.isEmpty())) {
            throw new SaleError(number + ": a " + kind.value() + " sale needs a price");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .number(number)
                .kind(kind)
                .buyer(buyer)
                .destination(destination)
                .commodityCode(commodityCode)
                .originCode(originCode)
                .writtenOn(writtenOn)
                .bushels(bushels)
                .window(window)
                .specification(specification)
                .carrier(carrier)
                .futuresMonth(futuresMonth.orElse(null))
                .futuresPrice(futuresPrice.orElse(null))
                .basis(basis.orElse(null))
                .flatPrice(flatPrice.orElse(null))
                .tolerancePercent(tolerancePercent)
                .freightPerBushel(freightPerBushel)
                .shipped(shipped);
    }

    public boolean isPriced() {
        return flatPrice.isPresent() || (futuresPrice.isPresent() && basis.isPresent());
    }

    public BigDecimal unitPrice() {
        if (flatPrice.isPresent()) {
            return Money.price(flatPrice.get());
        }
        if (futuresPrice.isEmpty() || basis.isEmpty()) {
            throw new SaleError(number + ": sale is not priced yet");
        }
        return Units.quantize(futuresPrice.get().add(basis.get()), Money.PRICE_PLACES);
    }

    /** What the house keeps after freight. */
    public BigDecimal netPrice() {
        return Units.quantize(unitPrice().subtract(freightPerBushel), Money.PRICE_PLACES);
    }

    /** Bushels still to load, floored at zero. */
    public BigDecimal remaining() {
        BigDecimal left = bushels.subtract(shipped);
        return left.signum() > 0 ? Units.quantize(left, Units.BUSHEL_PLACES) : new BigDecimal("0.0000");
    }

    public BigDecimal maximumBushels() {
        return Units.quantize(
                Decimals.divide(bushels.multiply(Decimals.HUNDRED.add(tolerancePercent)), Decimals.HUNDRED),
                Units.BUSHEL_PLACES);
    }

    /**
     * Complete means loaded to the face amount, not to the tolerance.
     *
     * <p>The house owes the whole number. The tolerance is there for the last car being a little
     * heavy, not for coming up short.
     */
    public boolean isComplete() {
        return shipped.compareTo(bushels) >= 0;
    }

    public boolean canShip(BigDecimal amount, LocalDate on) {
        return window.contains(on) && shipped.add(amount).compareTo(maximumBushels()) <= 0;
    }

    /** The sale after a car goes out. */
    public SaleContract ship(BigDecimal amount, LocalDate on) {
        BigDecimal wanted = Units.quantize(amount, Units.BUSHEL_PLACES);
        if (wanted.signum() <= 0) {
            throw new SaleError(number + ": cannot ship " + wanted + " bushels");
        }
        if (!window.contains(on)) {
            throw new SaleError(number + ": " + on + " is outside the shipping window");
        }
        if (shipped.add(wanted).compareTo(maximumBushels()) > 0) {
            throw new SaleError(number + ": shipping " + wanted + " would take it past " + maximumBushels());
        }
        return toBuilder()
                .shipped(Units.quantize(shipped.add(wanted), Units.BUSHEL_PLACES))
                .build();
    }

    /** Fix the futures half of a basis sale. */
    public SaleContract setFutures(BigDecimal futures) {
        if (kind != SaleKind.BASIS) {
            throw new SaleError(number + ": only a basis sale sets futures later");
        }
        if (futuresPrice.isPresent()) {
            throw new SaleError(number + ": futures are already set");
        }
        return toBuilder().futuresPrice(Money.price(futures)).build();
    }

    /** A sale assembled a field at a time; by truck, two percent tolerance, no freight, nothing shipped. */
    public static final class Builder {

        private String number;
        private SaleKind kind;
        private String buyer;
        private Destination destination;
        private String commodityCode;
        private String originCode;
        private LocalDate writtenOn;
        private BigDecimal bushels;
        private DeliveryWindow window;
        private Specification specification;
        private Carrier carrier = Carrier.TRUCK;
        private FuturesMonth futuresMonth;
        private BigDecimal futuresPrice;
        private BigDecimal basis;
        private BigDecimal flatPrice;
        private BigDecimal tolerancePercent = new BigDecimal("2");
        private BigDecimal freightPerBushel = BigDecimal.ZERO;
        private BigDecimal shipped = BigDecimal.ZERO;

        private Builder() {}

        public Builder number(String value) {
            this.number = value;
            return this;
        }

        public Builder kind(SaleKind value) {
            this.kind = value;
            return this;
        }

        public Builder buyer(String value) {
            this.buyer = value;
            return this;
        }

        public Builder destination(Destination value) {
            this.destination = value;
            return this;
        }

        public Builder commodityCode(String value) {
            this.commodityCode = value;
            return this;
        }

        public Builder originCode(String value) {
            this.originCode = value;
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

        public Builder specification(Specification value) {
            this.specification = value;
            return this;
        }

        public Builder carrier(Carrier value) {
            this.carrier = value;
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

        public Builder freightPerBushel(BigDecimal value) {
            this.freightPerBushel = value;
            return this;
        }

        public Builder shipped(BigDecimal value) {
            this.shipped = value;
            return this;
        }

        public SaleContract build() {
            return new SaleContract(
                    number,
                    kind,
                    buyer,
                    destination,
                    commodityCode,
                    originCode,
                    writtenOn,
                    bushels,
                    window,
                    specification,
                    carrier,
                    Optional.ofNullable(futuresMonth),
                    Optional.ofNullable(futuresPrice),
                    Optional.ofNullable(basis),
                    Optional.ofNullable(flatPrice),
                    tolerancePercent,
                    freightPerBushel,
                    shipped);
        }
    }
}
