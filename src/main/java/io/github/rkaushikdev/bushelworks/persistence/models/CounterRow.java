package io.github.rkaushikdev.bushelworks.persistence.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Check;

/**
 * One gapless document counter per series per year.
 *
 * <p>Locked for the length of the allocating transaction rather than read and incremented, so a
 * rollback puts the number back instead of burning it. A database sequence would be faster and
 * would leave holes, and the state auditor counts settlement numbers.
 */
@Entity
@Table(
        name = "document_counter",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_document_counter_series",
                        columnNames = {"series", "year"}))
@Check(name = "ck_document_counter_next_value_positive", constraints = "next_value >= 1")
public class CounterRow extends Row {

    @Column(name = "series", length = 16, nullable = false)
    private String series;

    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "next_value", nullable = false)
    private int nextValue = 1;

    public CounterRow() {}

    public String getSeries() {
        return series;
    }

    public void setSeries(String series) {
        this.series = series;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public int getNextValue() {
        return nextValue;
    }

    public void setNextValue(int nextValue) {
        this.nextValue = nextValue;
    }
}
