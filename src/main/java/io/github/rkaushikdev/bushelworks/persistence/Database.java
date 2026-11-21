package io.github.rkaushikdev.bushelworks.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.rkaushikdev.bushelworks.ConfigurationError;
import io.github.rkaushikdev.bushelworks.Settings;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * One database: a connection pool, the entity manager factory sessions are opened from, and the
 * migrations that build its schema.
 *
 * <p>The command line and the service both open one of these and nothing else, so there is exactly
 * one place a database is named and configured. The factory is built the first time a session is
 * asked for, and it checks the mapping against the schema when it is; a database nobody has
 * migrated yet can still be migrated, listed and closed.
 *
 * <p>Sessions do not flush on their own before a query. A change made to a row is written when the
 * unit of work flushes or commits, so code that has to read back what it just changed calls {@link
 * UnitOfWork#flush()} first. Rows stay readable after a commit: nothing is reloaded behind the
 * caller's back.
 */
public final class Database implements AutoCloseable {

    /** Where the entities live. */
    public static final String MAPPED_PACKAGE = "io.github.rkaushikdev.bushelworks.persistence.models";

    /**
     * Column names the schema uses that H2 otherwise reads as keywords. They are ordinary words in
     * the office's vocabulary, so the URL tells H2 so rather than the schema renaming them.
     */
    static final String H2_NON_KEYWORDS = "NON_KEYWORDS=VALUE,YEAR";

    private static final String UNNAMED_MEMORY = "jdbc:h2:mem:";

    private final String url;
    private final boolean echoSql;
    private final HikariDataSource pool;
    private final StatementLog statements = new StatementLog();
    private LocalContainerEntityManagerFactoryBean factoryBean;

    private Database(Settings settings) {
        this.url = settings.databaseUrl();
        this.echoSql = settings.echoSql();
        this.pool = pool(jdbcUrl(settings));
    }

    /**
     * Open the database the settings name, logging its SQL when they ask for that. Nothing
     * connects until something is asked of it.
     *
     * @throws ConfigurationError for an unnamed in-memory database, which gives every connection a
     *     database of its own: a session would commit into one and the next would find nothing,
     *     which reads like a transaction bug and is not
     */
    public static Database open(Settings settings) {
        if (settings.isEmbedded() && isUnnamedMemory(settings.databaseUrl())) {
            throw new ConfigurationError(UNNAMED_MEMORY + " gives every connection a database of its own;"
                    + " name it, as " + UNNAMED_MEMORY + "bushelworks, so every session sees the same one");
        }
        return new Database(settings);
    }

    /** Open the database at {@code url} with every other setting at its default. */
    public static Database open(String url) {
        return open(Settings.defaults().withDatabaseUrl(url));
    }

    /**
     * The URL as the driver gets it. An H2 URL is told which of the schema's column names are not
     * keywords, unless it already says so itself.
     */
    public static String jdbcUrl(Settings settings) {
        String configured = settings.databaseUrl();
        if (settings.isEmbedded() && !configured.toUpperCase(Locale.ROOT).contains("NON_KEYWORDS")) {
            return configured + ";" + H2_NON_KEYWORDS;
        }
        return configured;
    }

    private static boolean isUnnamedMemory(String url) {
        return url.equals(UNNAMED_MEMORY) || url.startsWith(UNNAMED_MEMORY + ";");
    }

    private static HikariDataSource pool(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setPoolName("bushelworks");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        // Connect when first asked rather than at start, so a box whose database is down still
        // starts far enough to say so.
        config.setInitializationFailTimeout(-1);
        // A database that will not answer is reported within seconds, not after half a minute.
        config.setConnectionTimeout(3_000);
        return new HikariDataSource(config);
    }

    /**
     * The entity manager factory bean for the mapped package over {@code dataSource}, without a
     * Spring context.
     *
     * @param schemaAction what Hibernate does to the schema when the factory is built: {@code
     *     validate} everywhere the schema came from the migrations, {@code create} only to build a
     *     schema straight from the mapping for comparison
     * @param inspector sees every statement before it runs
     */
    static LocalContainerEntityManagerFactoryBean entityManagerFactoryBean(
            DataSource dataSource, String schemaAction, boolean echoSql, StatementInspector inspector) {
        LocalContainerEntityManagerFactoryBean bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(dataSource);
        bean.setPackagesToScan(MAPPED_PACKAGE);
        bean.setPersistenceUnitName("bushelworks");
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("hibernate.hbm2ddl.auto", schemaAction);
        properties.put("hibernate.show_sql", String.valueOf(echoSql));
        // A statement's lines, a ticket's samples and a contract's applications are read for a
        // page of rows at a time rather than once per row.
        properties.put("hibernate.default_batch_fetch_size", "128");
        properties.put("hibernate.jdbc.time_zone", "UTC");
        // Hibernate otherwise lays columns out by type size, composite keys included, and a schema
        // it builds stops matching the order the migrations declare.
        properties.put("hibernate.column_ordering_strategy", "legacy");
        properties.put("hibernate.session_factory.statement_inspector", inspector);
        bean.setJpaPropertyMap(properties);
        return bean;
    }

    /** The URL as configured, which is what the command line prints. */
    public String url() {
        return url;
    }

    public DataSource dataSource() {
        return pool;
    }

    /** The migrations, run against this database. */
    public Migrations migrations() {
        return new Migrations(pool);
    }

    /** The factory, built and checked against the schema the first time it is asked for. */
    public synchronized EntityManagerFactory entityManagerFactory() {
        if (factoryBean == null) {
            LocalContainerEntityManagerFactoryBean bean =
                    entityManagerFactoryBean(pool, "validate", echoSql, statements);
            bean.afterPropertiesSet();
            factoryBean = bean;
        }
        return factoryBean.getObject();
    }

    /**
     * A new session, with no transaction begun. Whoever opens it closes it; a {@link UnitOfWork}
     * is the usual way to hold one.
     */
    public EntityManager openSession() {
        EntityManager session = entityManagerFactory().createEntityManager();
        session.setFlushMode(FlushModeType.COMMIT);
        return session;
    }

    /** Run {@code work} in a session of its own and close the session afterwards, however it ends. */
    public <T> T inSession(Function<EntityManager, T> work) {
        try (EntityManager session = openSession()) {
            return work.apply(session);
        }
    }

    /** Hand every statement a session prepares from now on to {@code listener}, until removed. */
    public void addStatementListener(Consumer<String> listener) {
        statements.add(listener);
    }

    public void removeStatementListener(Consumer<String> listener) {
        statements.remove(listener);
    }

    /** The tables in the database, lower case and sorted, the migrations' own history aside. */
    public List<String> tableNames() {
        try (Connection connection = pool.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            TreeSet<String> names = new TreeSet<>();
            try (ResultSet tables = metadata.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (tables.next()) {
                    String schema = tables.getString("TABLE_SCHEM");
                    if (schema == null || !schema.equalsIgnoreCase("INFORMATION_SCHEMA")) {
                        names.add(tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                    }
                }
            }
            names.remove(Migrations.HISTORY_TABLE);
            return List.copyOf(names);
        } catch (SQLException trouble) {
            throw new PersistenceException("cannot list the tables in " + url, trouble);
        }
    }

    /** Close the factory and the pool. Safe to call twice. */
    @Override
    public synchronized void close() {
        if (factoryBean != null) {
            factoryBean.destroy();
            factoryBean = null;
        }
        pool.close();
    }
}
