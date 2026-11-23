package io.github.rkaushikdev.bushelworks.domain.settlement;

import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which lines of a statement a crop share lease puts on one side of the split alone.
 *
 * <p>The grain is shared and so are the charges against the grain: the discounts, the drying and
 * the checkoff come off each owner's share in proportion. What is not shared is money one party
 * took or owes on his own. An advance paid to the tenant in August is the tenant's to repay, and
 * recovering a quarter of it from the landlord's cheque is how a house ends up writing a cheque to
 * the landlord with a letter of apology. A lease names the kinds of line one role bears in full,
 * and every other kind is split by share.
 *
 * @param borneBy the line kinds that one role bears in full
 */
public record LeaseTerms(Map<LineKind, SplitRole> borneBy) {

    /** Every line split by share, which is what a statement with one owner does anyway. */
    public static final LeaseTerms SHARED = new LeaseTerms(Map.of());

    /** The common crop share lease: advances and other deductions are the tenant's own. */
    public static final LeaseTerms CROP_SHARE =
            new LeaseTerms(Map.of(LineKind.ADVANCE, SplitRole.TENANT, LineKind.DEDUCTION, SplitRole.TENANT));

    public LeaseTerms {
        Map<LineKind, SplitRole> copy = new EnumMap<>(LineKind.class);
        copy.putAll(borneBy);
        if (copy.containsKey(LineKind.GROSS)) {
            throw new SettlementError("the grain itself is shared: a lease cannot put the gross on one party");
        }
        borneBy = Map.copyOf(copy);
    }

    /** The same terms with one more kind of line borne in full by {@code role}. */
    public LeaseTerms bearing(LineKind kind, SplitRole role) {
        Map<LineKind, SplitRole> more = new EnumMap<>(LineKind.class);
        more.putAll(borneBy);
        more.put(kind, role);
        return new LeaseTerms(more);
    }

    /** The role that bears this kind of line alone, or nothing when it is split by share. */
    public Optional<SplitRole> bearerOf(LineKind kind) {
        return Optional.ofNullable(borneBy.get(kind));
    }
}
