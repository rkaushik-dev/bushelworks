package io.github.rkaushikdev.bushelworks.release;

import io.github.rkaushikdev.bushelworks.Bushelworks;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One version, and what the house will notice about it.
 *
 * @param version three numbers with dots between, such as {@code 0.8.0}
 * @param releasedOn the day it went out
 * @param summary one sentence
 * @param changes what the office will notice, one line each
 * @param breaking the lines somebody has to be telephoned about before the box is upgraded
 * @param migrations the schema migrations an upgrade to it runs, named by their script's version, such
 *     as {@code V9}
 */
public record Release(
        String version,
        LocalDate releasedOn,
        String summary,
        List<String> changes,
        List<String> breaking,
        List<String> migrations) {

    public Release {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(releasedOn, "releasedOn");
        Objects.requireNonNull(summary, "summary");
        changes = List.copyOf(changes);
        breaking = List.copyOf(breaking);
        migrations = List.copyOf(migrations);
    }

    /** The version as numbers, for putting releases in order. */
    public Bushelworks.Version numbers() {
        return Bushelworks.Version.parse(version);
    }

    public boolean isBreaking() {
        return !breaking.isEmpty();
    }

    public boolean needsAMigration() {
        return !migrations.isEmpty();
    }
}
