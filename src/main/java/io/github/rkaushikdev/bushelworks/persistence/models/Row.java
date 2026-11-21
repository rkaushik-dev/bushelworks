package io.github.rkaushikdev.bushelworks.persistence.models;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * A stored row. Every table has a surrogate integer key named {@code id}, assigned by the database
 * when the row is first written.
 *
 * <p>Codes and numbers are what the office refers to things by, and they are printed on the
 * documents. The surrogate key exists anyway so a code can be corrected without rewriting every
 * row that points at it.
 */
@MappedSuperclass
public abstract class Row {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    /** The key the database gave the row, or null until the row has been written. */
    public Integer getId() {
        return id;
    }

    /**
     * The row's kind and key, as {@code <Party id=4>}: what a log line or a failed check wants, as
     * against the row's printed form.
     */
    public String describe() {
        return "<" + getClass().getSimpleName() + " id=" + id + ">";
    }

    @Override
    public String toString() {
        return describe();
    }
}
