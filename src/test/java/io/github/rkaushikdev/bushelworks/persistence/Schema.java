package io.github.rkaushikdev.bushelworks.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;

/**
 * What an H2 database's schema says about itself, read from {@code INFORMATION_SCHEMA} and written
 * lower case so two schemas built different ways can be compared table by table.
 *
 * <p>The migrations' own history table is left out everywhere, as are the indexes H2 builds behind
 * a key or a unique constraint on its own account.
 */
final class Schema {

    private static final String TABLES = "select table_name from information_schema.tables"
            + " where table_schema = 'PUBLIC' and table_type = 'BASE TABLE'";

    private static final String COLUMNS = "select column_name, is_nullable from information_schema.columns"
            + " where table_schema = 'PUBLIC' and table_name = ?";

    private static final String TYPES = "select column_name, data_type, character_maximum_length,"
            + " numeric_precision, numeric_scale from information_schema.columns"
            + " where table_schema = 'PUBLIC' and table_name = ?";

    private static final String PRIMARY_KEY = "select k.column_name"
            + " from information_schema.table_constraints c join information_schema.key_column_usage k"
            + " on c.constraint_name = k.constraint_name and c.table_name = k.table_name"
            + " where c.table_schema = 'PUBLIC' and c.constraint_type = 'PRIMARY KEY' and c.table_name = ?"
            + " order by k.ordinal_position";

    private static final String UNIQUES = "select c.constraint_name, k.column_name"
            + " from information_schema.table_constraints c join information_schema.key_column_usage k"
            + " on c.constraint_name = k.constraint_name and c.table_name = k.table_name"
            + " where c.table_schema = 'PUBLIC' and c.constraint_type = 'UNIQUE' and c.table_name = ?"
            + " order by c.constraint_name, k.ordinal_position";

    private static final String FOREIGN_KEYS = "select r.constraint_name, k.column_name, u.table_name,"
            + " u.column_name, r.delete_rule"
            + " from information_schema.referential_constraints r"
            + " join information_schema.key_column_usage k on r.constraint_name = k.constraint_name"
            + " join information_schema.key_column_usage u on r.unique_constraint_name = u.constraint_name"
            + " and k.position_in_unique_constraint = u.ordinal_position"
            + " where k.table_schema = 'PUBLIC' and k.table_name = ?";

    private static final String CHECKS = "select c.constraint_name, k.check_clause"
            + " from information_schema.table_constraints c join information_schema.check_constraints k"
            + " on c.constraint_name = k.constraint_name"
            + " where c.table_schema = 'PUBLIC' and c.constraint_type = 'CHECK' and c.table_name = ?";

    private static final String INDEXES = "select i.index_name, c.column_name"
            + " from information_schema.indexes i join information_schema.index_columns c"
            + " on i.index_name = c.index_name and i.table_name = c.table_name"
            + " where i.table_schema = 'PUBLIC' and i.table_name = ? and i.index_type_name = 'INDEX'"
            + " and i.index_name not like 'FK%' order by i.index_name, c.ordinal_position";

    private static final String CONSTRAINTS = "select table_name, constraint_type, constraint_name"
            + " from information_schema.table_constraints where table_schema = 'PUBLIC'";

    private final DataSource source;

    Schema(DataSource source) {
        this.source = source;
    }

    /** The tables, the history aside. */
    Set<String> tables() {
        Set<String> names = new TreeSet<>();
        for (List<String> row : rows(TABLES, null)) {
            names.add(row.get(0));
        }
        names.remove(Migrations.HISTORY_TABLE);
        return names;
    }

    /** Each column with whether it may be empty. */
    Set<String> columns(String table) {
        return joined(rows(COLUMNS, table));
    }

    /** Each column with its type, length, precision and scale. */
    Set<String> types(String table) {
        return joined(rows(TYPES, table));
    }

    /** The primary key's columns, in key order. */
    List<String> primaryKey(String table) {
        return rows(PRIMARY_KEY, table).stream().map(row -> row.get(0)).toList();
    }

    /** Each unique constraint by name, with its columns in order. */
    Map<String, List<String>> uniques(String table) {
        return grouped(rows(UNIQUES, table));
    }

    /** Each foreign key: its name, column, the table and column it points at, and what a delete does. */
    Set<String> foreignKeys(String table) {
        return joined(rows(FOREIGN_KEYS, table));
    }

    /** Each check constraint by name, with its condition as H2 reads it back. */
    Map<String, String> checks(String table) {
        Map<String, String> checks = new TreeMap<>();
        for (List<String> row : rows(CHECKS, table)) {
            checks.put(row.get(0), row.get(1));
        }
        return checks;
    }

    /** Each index by name, with its columns in order. */
    Map<String, List<String>> indexes(String table) {
        return grouped(rows(INDEXES, table));
    }

    /** Every constraint in the schema as {@code table | kind | name}, the history's own aside. */
    Set<String> constraints() {
        Set<String> found = new TreeSet<>();
        for (List<String> row : rows(CONSTRAINTS, null)) {
            if (!row.get(0).equals(Migrations.HISTORY_TABLE)) {
                found.add(String.join(" | ", row));
            }
        }
        return found;
    }

    /** Everything above for every table, so two schemas can be compared in one assertion. */
    Map<String, String> everything() {
        Map<String, String> described = new TreeMap<>();
        for (String table : tables()) {
            described.put(
                    table,
                    String.join(
                            "\n",
                            columns(table).toString(),
                            types(table).toString(),
                            primaryKey(table).toString(),
                            uniques(table).toString(),
                            foreignKeys(table).toString(),
                            checks(table).toString(),
                            indexes(table).toString()));
        }
        return described;
    }

    private static Set<String> joined(List<List<String>> rows) {
        Set<String> joined = new TreeSet<>();
        for (List<String> row : rows) {
            joined.add(String.join(" | ", row));
        }
        return joined;
    }

    private static Map<String, List<String>> grouped(List<List<String>> rows) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (List<String> row : rows) {
            grouped.computeIfAbsent(row.get(0), name -> new ArrayList<>()).add(row.get(1));
        }
        return new TreeMap<>(grouped);
    }

    private List<List<String>> rows(String sql, String table) {
        List<List<String>> found = new ArrayList<>();
        try (Connection connection = source.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (table != null) {
                statement.setString(1, table.toUpperCase(Locale.ROOT));
            }
            try (ResultSet rows = statement.executeQuery()) {
                int width = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    List<String> row = new ArrayList<>(width);
                    for (int column = 1; column <= width; column++) {
                        String value = rows.getString(column);
                        row.add(value == null ? "null" : value.toLowerCase(Locale.ROOT));
                    }
                    found.add(row);
                }
            }
        } catch (SQLException trouble) {
            throw new IllegalStateException(sql, trouble);
        }
        return found;
    }
}
