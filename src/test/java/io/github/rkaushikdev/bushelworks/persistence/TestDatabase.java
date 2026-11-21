package io.github.rkaushikdev.bushelworks.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * The suite's database: one H2 in memory, migrated once for the whole run and emptied before each
 * test that asks for it.
 *
 * <p>Building a Hibernate factory per test costs more than the tests do, so this keeps one database
 * and one factory and gives every test a database of its own in effect: the schema, nothing in it,
 * and the generated keys counting from one.
 *
 * <pre>{@code
 * Database database = TestDatabase.fresh();
 * try (UnitOfWork uow = UnitOfWork.open(database)) { ... }
 * }</pre>
 */
public final class TestDatabase {

    public static final String URL = "jdbc:h2:mem:bushelworks-tests;DB_CLOSE_DELAY=-1";

    private static Database shared;

    private TestDatabase() {}

    /** The shared database, migrated and emptied. Never close it: the next test wants it. */
    public static synchronized Database fresh() {
        if (shared == null) {
            Database database = Database.open(URL);
            database.migrations().upgrade();
            shared = database;
        }
        empty(shared);
        return shared;
    }

    /** The first row of a kind, in the order the rows were written, if there is one. */
    public static <T> Optional<T> first(EntityManager session, Class<T> type) {
        return all(session, type).stream().findFirst();
    }

    /** Every row of a kind, in the order they were written. */
    public static <T> List<T> all(EntityManager session, Class<T> type) {
        CriteriaBuilder builder = session.getCriteriaBuilder();
        CriteriaQuery<T> query = builder.createQuery(type);
        Root<T> root = query.from(type);
        query.select(root).orderBy(builder.asc(root.get("id")));
        return session.createQuery(query).getResultList();
    }

    /** How many rows of a kind there are. */
    public static long count(EntityManager session, Class<?> type) {
        CriteriaBuilder builder = session.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        query.select(builder.count(query.from(type)));
        return session.createQuery(query).getSingleResult();
    }

    /**
     * Run {@code work} and require the database to refuse it for breaking a constraint: a key, a
     * unique, a check or a not null. Any other failure, or none, fails the test.
     */
    public static void assertIntegrityError(ThrowingCallable work) {
        Throwable thrown = catchThrowable(work);
        assertThat(thrown).as("the database should have refused it").isInstanceOf(PersistenceException.class);
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = thrown; cause != null && !chain.contains(cause); cause = cause.getCause()) {
            chain.add(cause);
        }
        assertThat(chain)
                .as("refused for breaking a constraint")
                .anyMatch(SQLIntegrityConstraintViolationException.class::isInstance);
    }

    private static void empty(Database database) {
        List<String> tables = database.tableNames();
        try (Connection connection = database.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET REFERENTIAL_INTEGRITY FALSE");
            for (String table : tables) {
                statement.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
            }
            statement.execute("SET REFERENTIAL_INTEGRITY TRUE");
        } catch (SQLException trouble) {
            throw new IllegalStateException("could not empty the test database", trouble);
        }
    }
}
