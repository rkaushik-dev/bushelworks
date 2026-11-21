package io.github.rkaushikdev.bushelworks.persistence.models;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** A row that records when it was written and when it last moved, both in UTC. */
@MappedSuperclass
public abstract class Timestamped extends Row {

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** When the row was first written; null on a row that has not been. */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** When the row last changed; null on a row that has not been written. */
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    @PrePersist
    void stampWritten() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void stampMoved() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
