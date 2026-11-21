package io.github.rkaushikdev.bushelworks.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * The migrations have to build the same schema the entities describe.
 *
 * <p>A drift between the two is caught by nothing else: a table added to the entities and forgotten
 * in a migration passes every other test in the suite and fails on the office box at six on a
 * Monday. So these build one schema by running the migrations and one straight from the mapping with
 * Hibernate's own schema export, and compare them table by table.
 *
 * <p>The two databases are built once for the class. Nothing here writes to either of them, so
 * sharing them is safe; the tests that go up and down the revisions get databases of their own.
 */
class MigrationsTest {

    private static Database migrated;
    private static Database built;
    private static LocalContainerEntityManagerFactoryBean export;
    private static Set<String> declared;

    @BeforeAll
    static void buildBothSchemas() {
        migrated = Database.open(memory("migrated"));
        migrated.migrations().upgrade();

        built = Database.open(memory("built"));
        export = Database.entityManagerFactoryBean(built.dataSource(), "create", false, sql -> sql);
        export.afterPropertiesSet();
        EntityManagerFactory factory = export.getObject();
        declared = new TreeSet<>();
        for (EntityType<?> entity : factory.getMetamodel().getEntities()) {
            declared.add(entity.getJavaType().getAnnotation(Table.class).name());
        }
    }

    @AfterAll
    static void closeBoth() {
        export.destroy();
        built.close();
        migrated.close();
    }

    private static String memory(String name) {
        return "jdbc:h2:mem:" + name + "-" + UUID.randomUUID();
    }

    private static Schema migratedSchema() {
        return new Schema(migrated.dataSource());
    }

    private static Schema builtSchema() {
        return new Schema(built.dataSource());
    }

    static Stream<String> tables() {
        return declared.stream();
    }

    @Test
    void theMigrationRunsFromNothing() {
        assertThat(migrated.tableNames()).contains("scale_ticket");
        assertThat(migrated.migrations().version()).isEqualTo(9);
    }

    @Test
    void everyModelTableExistsInTheMigrations() {
        assertThat(migratedSchema().tables()).isEqualTo(builtSchema().tables()).isEqualTo(declared);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void everyTableHasTheSameColumnsEitherWay(String table) {
        assertThat(migratedSchema().columns(table))
                .as(table)
                .isEqualTo(builtSchema().columns(table));
        assertThat(migratedSchema().types(table))
                .as(table)
                .isEqualTo(builtSchema().types(table));
        assertThat(migratedSchema().primaryKey(table))
                .as(table)
                .isEqualTo(builtSchema().primaryKey(table));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void everyTableHasTheSameIndexesEitherWay(String table) {
        assertThat(migratedSchema().indexes(table))
                .as(table)
                .isEqualTo(builtSchema().indexes(table));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void everyTableHasTheSameUniqueConstraintsEitherWay(String table) {
        assertThat(migratedSchema().uniques(table))
                .as(table)
                .isEqualTo(builtSchema().uniques(table));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void everyTableHasTheSameForeignKeysEitherWay(String table) {
        assertThat(migratedSchema().foreignKeys(table))
                .as(table)
                .isEqualTo(builtSchema().foreignKeys(table));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    void everyTableHasTheSameChecksEitherWay(String table) {
        assertThat(migratedSchema().checks(table))
                .as(table)
                .isEqualTo(builtSchema().checks(table));
    }

    @Test
    void everyConstraintIsNamedByTheConvention() {
        assertThat(misnamed(migratedSchema())).isEmpty();
        assertThat(misnamedIndexes(migratedSchema())).isEmpty();
    }

    /** Every constraint name that does not follow pk_, uq_, fk_ or ck_ and its table. */
    private static List<String> misnamed(Schema schema) {
        return schema.constraints().stream()
                .filter(row -> {
                    String[] parts = row.split(" \\| ");
                    String table = parts[0];
                    String name = parts[2];
                    return switch (parts[1]) {
                        case "primary key" -> !name.equals("pk_" + table);
                        case "unique" ->
                            !name.equals("uq_" + table + "_"
                                    + schema.uniques(table).get(name).get(0));
                        case "foreign key" ->
                            schema.foreignKeys(table).stream().noneMatch(key -> {
                                String[] columns = key.split(" \\| ");
                                return columns[0].equals(name)
                                        && name.equals("fk_" + table + "_" + columns[1] + "_" + columns[2]);
                            });
                        case "check" -> !name.startsWith("ck_" + table + "_");
                        default -> true;
                    };
                })
                .toList();
    }

    private static List<String> misnamedIndexes(Schema schema) {
        return schema.tables().stream()
                .flatMap(table -> schema.indexes(table).keySet().stream()
                        .filter(name -> !name.startsWith("ix_" + table + "_"))
                        .map(name -> table + " | " + name))
                .toList();
    }

    @Test
    void theReferenceAndTicketTablesAreThere() {
        assertThat(migrated.tableNames())
                .contains(
                        "party",
                        "location",
                        "commodity",
                        "document_counter",
                        "split_agreement",
                        "scale_ticket",
                        "ticket_sample",
                        "ticket_split");
    }

    @Test
    void theTradingTablesAreThere() {
        assertThat(migrated.tableNames())
                .contains(
                        "purchase_contract",
                        "contract_application",
                        "settlement",
                        "settlement_line",
                        "settlement_ticket");
    }

    @Test
    void theWarehouseTablesAreThere() {
        assertThat(migrated.tableNames()).contains("storage_lot", "storage_withdrawal", "warehouse_receipt");
    }

    @Test
    void theSaleTablesAreThere() {
        assertThat(migrated.tableNames()).contains("sale_contract", "shipment");
    }

    @Test
    void theAuditTableIsThere() {
        assertThat(migrated.tableNames()).contains("audit_entry");
    }

    @Test
    void theUniqueConstraintOnTicketNumberIsPerLocation() {
        assertThat(migratedSchema().uniques("scale_ticket"))
                .containsEntry("uq_scale_ticket_location_code", List.of("location_code", "ticket_number"));
    }

    @Test
    void theMigrationReverses(@TempDir Path folder) {
        try (Database database = Database.open("jdbc:h2:file:" + folder.resolve("reversed"))) {
            database.migrations().upgrade();
            assertThat(database.tableNames()).isNotEmpty();
            database.migrations().downgradeTo(0);
            assertThat(database.tableNames()).isEmpty();
            assertThat(database.migrations().version()).isZero();
        }
    }

    @Test
    void steppingBackOneRevisionDropsOnlyThatRevisionsTables(@TempDir Path folder) {
        try (Database database = Database.open("jdbc:h2:file:" + folder.resolve("stepped"))) {
            database.migrations().upgrade();
            database.migrations().downgradeTo(3);
            List<String> names = database.tableNames();
            assertThat(names).contains("storage_lot");
            assertThat(names).doesNotContain("audit_entry", "sale_contract");
            assertThat(database.migrations().version()).isEqualTo(3);
        }
    }

    @Test
    void upgradingTwiceIsANoOp(@TempDir Path folder) {
        try (Database database = Database.open("jdbc:h2:file:" + folder.resolve("twice"))) {
            database.migrations().upgrade();
            List<String> before = database.tableNames();
            database.migrations().upgrade();
            assertThat(database.tableNames()).contains("scale_ticket").isEqualTo(before);
        }
    }

    static IntStream revisionsBelowTheNewest() {
        return IntStream.range(0, 9);
    }

    /**
     * Each rollback takes out exactly its own revision: coming down to a revision leaves the schema
     * that going up to it builds, and going back up again rebuilds the newest one.
     */
    @ParameterizedTest(name = "down to {0}")
    @MethodSource("revisionsBelowTheNewest")
    void downgradingToARevisionLeavesTheSchemaItBuilt(int revision) {
        try (Database stepped = Database.open(memory("stepped"));
                Database direct = Database.open(memory("direct"))) {
            stepped.migrations().upgrade();
            stepped.migrations().downgradeTo(revision);
            direct.migrations().upgradeTo(revision);
            assertThat(new Schema(stepped.dataSource()).everything())
                    .isEqualTo(new Schema(direct.dataSource()).everything());
            assertThat(stepped.migrations().version()).isEqualTo(revision);

            stepped.migrations().upgrade();
            assertThat(new Schema(stepped.dataSource()).everything())
                    .isEqualTo(migratedSchema().everything());
        }
    }

    @Test
    void aDowngradeAboveTheCurrentRevisionIsRefused() {
        try (Database database = Database.open(memory("above"))) {
            database.migrations().upgradeTo(2);
            assertThatThrownBy(() -> database.migrations().downgradeTo(5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("cannot downgrade from revision 2 to 5");
            assertThatThrownBy(() -> database.migrations().downgradeTo(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void cleaningTakesEverythingOutTheHistoryIncluded() {
        try (Database database = Database.open(memory("cleaned"))) {
            database.migrations().upgrade();
            database.migrations().clean();
            assertThat(database.tableNames()).isEmpty();
            assertThat(new Schema(database.dataSource()).constraints()).isEmpty();
            assertThat(database.migrations().version()).isZero();
            database.migrations().upgrade();
            assertThat(database.migrations().version()).isEqualTo(9);
        }
    }

    /**
     * The comparisons above pass when both sides read nothing, so this shows that each of them reads
     * something on both sides, and that a change planted on one side is seen while an untouched table
     * still matches.
     */
    @Test
    void theComparisonsReadTheSchemaAndSeeADifference() throws SQLException {
        List<Function<String, Object>> migratedReads = reads(migratedSchema());
        List<Function<String, Object>> builtReads = reads(builtSchema());
        for (int kind = 0; kind < migratedReads.size(); kind++) {
            for (List<Function<String, Object>> side : List.of(migratedReads, builtReads)) {
                Function<String, Object> read = side.get(kind);
                assertThat(declared.stream()
                                .mapToInt(table -> size(read.apply(table)))
                                .sum())
                        .as("comparison %d reads something", kind)
                        .isPositive();
            }
        }

        try (Database planted = Database.open(memory("planted"))) {
            planted.migrations().upgrade();
            try (Connection connection = planted.dataSource().getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE party ADD COLUMN planted INTEGER");
                statement.execute("CREATE INDEX ix_party_planted ON party (name)");
                statement.execute("ALTER TABLE location ADD CONSTRAINT uq_location_planted UNIQUE (name)");
                statement.execute("ALTER TABLE ticket_sample DROP CONSTRAINT fk_ticket_sample_ticket_id_scale_ticket");
                statement.execute("ALTER TABLE bin ADD CONSTRAINT ck_bin_planted CHECK (bushels > 1)");
                statement.execute("ALTER TABLE commodity ADD UNIQUE (name)");
                statement.execute("CREATE INDEX planted_index ON shipment (reference)");
            }
            Schema schema = new Schema(planted.dataSource());
            Schema expected = builtSchema();
            assertThat(schema.columns("party")).isNotEqualTo(expected.columns("party"));
            assertThat(schema.types("party")).isNotEqualTo(expected.types("party"));
            assertThat(schema.indexes("party")).isNotEqualTo(expected.indexes("party"));
            assertThat(schema.uniques("location")).isNotEqualTo(expected.uniques("location"));
            assertThat(schema.foreignKeys("ticket_sample")).isNotEqualTo(expected.foreignKeys("ticket_sample"));
            assertThat(schema.checks("bin")).isNotEqualTo(expected.checks("bin"));
            assertThat(schema.everything()).isNotEqualTo(expected.everything());
            assertThat(misnamed(schema)).anyMatch(row -> row.startsWith("commodity | unique | constraint_"));
            assertThat(misnamedIndexes(schema)).containsExactly("shipment | planted_index");
            assertThat(schema.columns("settlement")).isEqualTo(expected.columns("settlement"));
            assertThat(schema.checks("settlement_line")).isEqualTo(expected.checks("settlement_line"));
        }
    }

    private static List<Function<String, Object>> reads(Schema schema) {
        return List.of(
                schema::columns,
                schema::types,
                schema::primaryKey,
                schema::uniques,
                schema::foreignKeys,
                schema::checks,
                schema::indexes);
    }

    private static int size(Object read) {
        if (read instanceof Collection<?> collection) {
            return collection.size();
        }
        return ((Map<?, ?>) read).size();
    }
}
