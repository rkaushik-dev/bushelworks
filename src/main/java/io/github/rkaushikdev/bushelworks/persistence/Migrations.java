package io.github.rkaushikdev.bushelworks.persistence;

import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * The schema's revisions, and the way back down them.
 *
 * <p>Each revision is a script in {@value #LOCATION}, numbered in the order the schema grew, and
 * each has a script beside it under {@value #ROLLBACKS} that takes exactly that revision out
 * again. Upgrading runs the ones a database has not had; downgrading runs the rollbacks newest
 * first and strikes each revision off the history, so the next upgrade puts it back.
 *
 * <p>The application never migrates on its own. A database is brought up to date on purpose,
 * because a box that quietly rebuilds its schema at start is a box that can lose a season's tickets
 * to a typo in a URL.
 */
public final class Migrations {

    /** Where the revisions live. */
    public static final String LOCATION = "classpath:db/migration";

    /** Where each revision's rollback lives, named for its revision with a {@code U} for the {@code V}. */
    public static final String ROLLBACKS = "db/rollback";

    /** The table the history of applied revisions is kept in. */
    public static final String HISTORY_TABLE = "flyway_schema_history";

    private final DataSource dataSource;

    public Migrations(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Bring the schema up to the newest revision. A database already there is left alone. */
    public void upgrade() {
        flyway().migrate();
    }

    /** Bring the schema up to revision {@code target} and no further. Revision zero is no schema at all. */
    public void upgradeTo(int target) {
        if (target == 0) {
            return;
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .target(String.valueOf(target))
                .load()
                .migrate();
    }

    /** The newest revision applied, or zero for a database with none. */
    public int version() {
        MigrationInfo current = flyway().info().current();
        return current == null ? 0 : revision(current);
    }

    /**
     * Take the schema back down to revision {@code target}, newest revision first. Zero takes every
     * table out and leaves only the empty history.
     *
     * @throws IllegalArgumentException when {@code target} is negative or above the current revision
     * @throws PersistenceException when a revision has no rollback or its rollback fails
     */
    public void downgradeTo(int target) {
        int current = version();
        if (target < 0 || target > current) {
            throw new IllegalArgumentException("cannot downgrade from revision " + current + " to " + target);
        }
        List<MigrationInfo> applied = Arrays.stream(flyway().info().applied())
                .filter(info -> info.getVersion() != null && info.getState().isApplied())
                .filter(info -> revision(info) > target)
                .sorted(Comparator.comparingInt(Migrations::revision).reversed())
                .toList();
        try (Connection connection = dataSource.getConnection()) {
            for (MigrationInfo info : applied) {
                ScriptUtils.executeSqlScript(connection, rollbackFor(info));
                strike(connection, info);
            }
        } catch (SQLException trouble) {
            throw new PersistenceException("cannot downgrade to revision " + target, trouble);
        }
    }

    /** Drop everything in the schema, the history included. What a scratch database is reset with. */
    public void clean() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(LOCATION)
                .cleanDisabled(false)
                .load()
                .clean();
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource).locations(LOCATION).load();
    }

    private static int revision(MigrationInfo info) {
        return Integer.parseInt(info.getVersion().getVersion());
    }

    private static ClassPathResource rollbackFor(MigrationInfo info) {
        ClassPathResource script =
                new ClassPathResource(ROLLBACKS + "/U" + info.getScript().substring(1));
        if (!script.exists()) {
            throw new PersistenceException("revision " + revision(info) + " has no rollback at " + script.getPath());
        }
        return script;
    }

    private static void strike(Connection connection, MigrationInfo info) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("DELETE FROM \"" + HISTORY_TABLE + "\" WHERE \"version\" = ?")) {
            statement.setString(1, info.getVersion().getVersion());
            statement.executeUpdate();
        }
    }
}
