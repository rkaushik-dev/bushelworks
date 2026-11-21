package io.github.rkaushikdev.bushelworks.services.legacy;

import java.util.List;

/**
 * What a night's file gave up: the rows that read, and the ones that did not with why.
 *
 * @param rows the rows that read, in file order
 * @param rejected the rows that did not, in file order
 */
public record ParsedFile(List<LegacyRow> rows, List<Rejected> rejected) {

    public ParsedFile {
        rows = List.copyOf(rows);
        rejected = List.copyOf(rejected);
    }

    /** How many rows read. */
    public int read() {
        return rows.size();
    }

    /** How many rows did not. */
    public int refused() {
        return rejected.size();
    }

    /** Whether every row read. */
    public boolean isClean() {
        return rejected.isEmpty();
    }

    /** The rows filed under one producer. */
    public List<LegacyRow> byParty(String partyCode) {
        return rows.stream().filter(row -> row.partyCode().equals(partyCode)).toList();
    }
}
