package io.github.rkaushikdev.bushelworks.domain.complaints;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The complaint register: every complaint, and what it lets the house see.
 *
 * <p>The register exists to make two questions answerable: is this a one off, or has this bin been
 * complained about three times; and was the retained sample still on the shelf when the complaint
 * arrived. The second one decides whether the house can defend itself. A complaint that arrives
 * after the retention period is a complaint the house settles rather than argues, and knowing that
 * early saves a fortnight.
 */
public final class Register {

    private final List<Complaint> complaints = new ArrayList<>();

    public List<Complaint> complaints() {
        return List.copyOf(complaints);
    }

    public void add(Complaint complaint) {
        if (complaints.stream().anyMatch(item -> item.reference().equals(complaint.reference()))) {
            throw new ConflictError(complaint.reference() + " is already on the register");
        }
        complaints.add(complaint);
    }

    /** Put back a complaint that has moved on. It has to be on the register already. */
    public void replace(Complaint complaint) {
        for (int index = 0; index < complaints.size(); index++) {
            if (complaints.get(index).reference().equals(complaint.reference())) {
                complaints.set(index, complaint);
                return;
            }
        }
        throw new ComplaintError(complaint.reference() + " is not on the register");
    }

    /** The complaints still open, oldest first. */
    public List<Complaint> openComplaints() {
        return complaints.stream()
                .filter(Complaint::isOpen)
                .sorted(Comparator.comparing(Complaint::raisedOn).thenComparing(Complaint::reference))
                .toList();
    }

    public List<Complaint> forBin(String binId) {
        return complaints.stream()
                .filter(item -> item.binId().filter(binId::equals).isPresent())
                .toList();
    }

    /** Bins complained about three times or more. */
    public List<String> repeatBins() {
        return repeatBins(3);
    }

    /** Bins complained about more than the house is comfortable with. */
    public List<String> repeatBins(int threshold) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Complaint item : complaints) {
            item.binId().filter(id -> !id.isEmpty()).ifPresent(id -> counts.merge(id, 1, Integer::sum));
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .toList();
    }

    public BigDecimal credits() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Complaint item : complaints) {
            sum = sum.add(item.credit());
        }
        return Money.amount(sum);
    }

    /** Of the complaints closed, the percent upheld, to a tenth, a tie going to the even tenth. */
    public BigDecimal upheldRate() {
        List<Complaint> closed =
                complaints.stream().filter(item -> !item.isOpen()).toList();
        if (closed.isEmpty()) {
            return new BigDecimal("0.0");
        }
        long upheld =
                closed.stream().filter(item -> item.outcome() == Outcome.UPHELD).count();
        return Decimals.divide(BigDecimal.valueOf(upheld), BigDecimal.valueOf(closed.size()))
                .multiply(Decimals.HUNDRED)
                .setScale(1, RoundingMode.HALF_EVEN);
    }

    public List<Complaint> indefensible() {
        return indefensible(Complaint.RETENTION_DAYS);
    }

    /** Open complaints with no sample to argue from. */
    public List<Complaint> indefensible(int retentionDays) {
        return openComplaints().stream()
                .filter(item -> !item.sampleIsAvailable(retentionDays))
                .toList();
    }

    /** How many complaints came from each kind of caller, by the caller's value. */
    public static Map<String, Integer> bySource(List<Complaint> complaints) {
        Map<String, Integer> counts = new TreeMap<>();
        complaints.forEach(item -> counts.merge(item.source().value(), 1, Integer::sum));
        return counts;
    }
}
