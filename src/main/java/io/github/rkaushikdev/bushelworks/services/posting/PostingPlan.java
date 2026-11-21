package io.github.rkaushikdev.bushelworks.services.posting;

import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * What an entry would be, before anything is written.
 *
 * @param entry the entry the statement would post
 * @param settlementNumber the statement it comes from
 * @param net the statement's net
 * @param warnings anything the office should look at before posting it
 */
public record PostingPlan(JournalEntry entry, String settlementNumber, BigDecimal net, List<String> warnings) {

    public PostingPlan {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(settlementNumber, "settlementNumber");
        Objects.requireNonNull(net, "net");
        warnings = List.copyOf(warnings);
    }

    /** Whether the entry's debits equal its credits. */
    public boolean balances() {
        return entry.balances();
    }
}
