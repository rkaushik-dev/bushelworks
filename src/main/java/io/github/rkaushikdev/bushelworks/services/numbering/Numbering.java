package io.github.rkaushikdev.bushelworks.services.numbering;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.persistence.models.CounterRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Allocates the next number in a series, inside the caller's transaction.
 *
 * <p>Ticket numbers come off pre-numbered stock and belong to the scale house, so nothing here ever
 * invents one. Settlement numbers are the office's own, and they have to be gapless within a year
 * because the state auditor counts them.
 *
 * <p>Gapless is the hard part. Taking the current maximum and adding one races the moment two
 * clerks settle at the same time, and a sequence in the database leaves a gap whenever a
 * transaction rolls back. What the office needs is a counter row locked for the length of the
 * transaction, so a rollback releases the number and the next caller gets it.
 */
public final class Numbering {

    private final EntityManager session;

    /** Numbering over {@code session}, usually a unit of work's. Nothing here commits. */
    public Numbering(EntityManager session) {
        this.session = session;
    }

    /**
     * Take the next number in the series for the year {@code on} falls in.
     *
     * @throws NumberingError when the series has no name
     */
    public DocumentNumber allocate(String series, LocalDate on) {
        if (series.isEmpty()) {
            throw new NumberingError("a series needs a name");
        }
        CounterRow row = counter(series, on.getYear());
        DocumentNumber number = new DocumentNumber(series, on.getYear(), row.getNextValue());
        row.setNextValue(row.getNextValue() + 1);
        session.flush();
        return number;
    }

    /** The number the next allocation in the series would take, without taking it. */
    public int peek(String series, LocalDate on) {
        return find(series, on.getYear(), false).map(CounterRow::getNextValue).orElse(1);
    }

    /**
     * Take a run of numbers at once, for a batch settlement run.
     *
     * @throws NumberingError when fewer than one number is asked for
     */
    public List<DocumentNumber> reserveBlock(String series, LocalDate on, int count) {
        if (count < 1) {
            throw new NumberingError("a block has to be at least one number");
        }
        CounterRow row = counter(series, on.getYear());
        int start = row.getNextValue();
        row.setNextValue(start + count);
        session.flush();
        List<DocumentNumber> block = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            block.add(new DocumentNumber(series, on.getYear(), start + offset));
        }
        return List.copyOf(block);
    }

    /**
     * Require a year's issued numbers to have no holes in them.
     *
     * <p>Run at year end against the settlement table. A hole means a number was allocated and the
     * settlement was never written, which is exactly what the auditor wants explained. No numbers at
     * all passes.
     *
     * @param used the sequences issued in the year
     * @throws ConflictError naming how many numbers are missing and the first ten of them
     */
    public void checkGapless(String series, int year, Collection<Integer> used) {
        if (used.isEmpty()) {
            return;
        }
        int highest = used.stream().mapToInt(Integer::intValue).max().orElseThrow();
        TreeSet<Integer> missing = new TreeSet<>();
        for (int sequence = 1; sequence <= highest; sequence++) {
            missing.add(sequence);
        }
        missing.removeAll(used);
        if (!missing.isEmpty()) {
            String listed = missing.stream().limit(10).map(String::valueOf).collect(Collectors.joining(", "));
            throw new ConflictError(series + " " + year + " is missing " + missing.size() + " numbers: " + listed);
        }
    }

    /** The series' counter for the year, locked until the transaction ends, written if it is new. */
    private CounterRow counter(String series, int year) {
        return find(series, year, true).orElseGet(() -> {
            CounterRow row = new CounterRow();
            row.setSeries(series);
            row.setYear(year);
            row.setNextValue(1);
            session.persist(row);
            session.flush();
            return row;
        });
    }

    private Optional<CounterRow> find(String series, int year, boolean locked) {
        TypedQuery<CounterRow> query = session.createQuery(
                        "select c from CounterRow c where c.series = :series and c.year = :year", CounterRow.class)
                .setParameter("series", series)
                .setParameter("year", year);
        if (locked) {
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        }
        return query.getResultList().stream().findFirst();
    }
}
