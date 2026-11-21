package io.github.rkaushikdev.bushelworks.domain.liens;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One cheque the settlement produces.
 *
 * @param payee who the cheque is made out to first
 * @param jointWith anybody whose name goes on it too, in filing order
 * @param dollars how much
 * @param reason what the cheque is for
 */
public record Payment(String payee, List<String> jointWith, BigDecimal dollars, String reason) {

    public Payment {
        Objects.requireNonNull(payee, "payee");
        Objects.requireNonNull(dollars, "dollars");
        Objects.requireNonNull(reason, "reason");
        jointWith = List.copyOf(jointWith);
    }

    public boolean isJoint() {
        return !jointWith.isEmpty();
    }

    /** Every name on the cheque, the payee first. */
    public List<String> names() {
        List<String> names = new ArrayList<>(jointWith.size() + 1);
        names.add(payee);
        names.addAll(jointWith);
        return List.copyOf(names);
    }
}
