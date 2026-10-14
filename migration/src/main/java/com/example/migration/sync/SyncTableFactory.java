package com.example.migration.sync;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import com.example.migration.config.DataSourceConfig;
import com.example.migration.config.MigrationProperties;
import com.example.migration.config.MigrationProperties.Mode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Builds the sync plan of one configured target table:
 *
 * <ol>
 * <li>derive the source table of the snapshot ({@code order -> order20261014}),</li>
 * <li>look for it in the source database - no snapshot, no sync,</li>
 * <li>create the target table when it does not exist yet,</li>
 * <li>copy the columns both tables have in common.</li>
 * </ol>
 */
@Component
public class SyncTableFactory {

	private static final Logger logger = LoggerFactory.getLogger(SyncTableFactory.class);

	/** Column types that cannot be read through JDBC and are therefore not copied. */
	private static final Set<String> UNSUPPORTED = Set.of("ROWID", "UROWID", "BFILE", "ARRAY", "STRUCT", "REF",
			"DATALINK", "OTHER", "JAVA_OBJECT", "DISTINCT");

	private final DataSource sourceDataSource;

	private final DataSource targetDataSource;

	private final TargetTableCreator targetTableCreator;

	private final MigrationProperties properties;

	public SyncTableFactory(@Qualifier(DataSourceConfig.SOURCE_DATA_SOURCE) DataSource sourceDataSource,
			@Qualifier(DataSourceConfig.TARGET_DATA_SOURCE) DataSource targetDataSource,
			TargetTableCreator targetTableCreator, MigrationProperties properties) {
		this.sourceDataSource = sourceDataSource;
		this.targetDataSource = targetDataSource;
		this.targetTableCreator = targetTableCreator;
		this.properties = properties;
	}

	/** Source table of a target table for the given date, e.g. {@code order -> order20261014}. */
	public String sourceTable(String targetTable, LocalDate date) {
		String pattern = this.properties.getSourceTablePattern();
		if (pattern == null || !pattern.contains("{table}")) {
			throw new IllegalStateException(
					"'migration.source-table-pattern' has to contain {table} and may contain {date}");
		}
		String dateText = DateTimeFormatter.ofPattern(this.properties.getDatePattern()).format(date);
		return pattern.replace("{table}", targetTable).replace("{date}", dateText);
	}

	/** Plan of one target table, empty when the snapshot of the run date does not exist. */
	public Optional<SyncTable> resolve(String targetTable, LocalDate date) {
		String sourceTable = sourceTable(targetTable, date);
		List<SourceColumn> sourceColumns = readSourceColumns(sourceTable);
		if (sourceColumns.isEmpty()) {
			logger.warn("No source table {} for target table {}, skipping it", sourceTable, targetTable);
			return Optional.empty();
		}
		List<String> keyColumns = readPrimaryKeys(this.sourceDataSource, sourceTable);
		String cursor = resolveCursor(sourceColumns, keyColumns, sourceTable);
		String timestampColumn = null;
		if (this.properties.getSyncMode() == Mode.INCREMENTAL) {
			timestampColumn = resolveColumn(sourceColumns, this.properties.getTimestampColumn(),
					"migration.timestamp-column", sourceTable);
		}
		List<String> keys = keyColumns.isEmpty() ? List.of(cursor) : keyColumns;
		this.targetTableCreator.createIfMissing(targetTable, sourceColumns, keys,
				timestampColumn == null ? null : TargetTableCreator.targetName(timestampColumn),
				this.properties.getTableOptions());

		List<String> targetColumns = readColumnNames(this.targetDataSource, targetTable);
		List<String> copiedSourceColumns = new ArrayList<>();
		List<String> copiedTargetColumns = new ArrayList<>();
		for (SourceColumn sourceColumn : sourceColumns) {
			Optional<String> targetColumn = targetColumns.stream()
					.filter(sourceColumn.name()::equalsIgnoreCase)
					.findFirst();
			if (targetColumn.isPresent()) {
				copiedSourceColumns.add(sourceColumn.name());
				copiedTargetColumns.add(targetColumn.get());
			}
			else {
				logger.warn("Column {} of {} does not exist in {}, it is not copied", sourceColumn.name(), sourceTable,
						targetTable);
			}
		}
		requireColumn(copiedSourceColumns, cursor, "cursor column", sourceTable, targetTable);
		if (timestampColumn != null) {
			requireColumn(copiedSourceColumns, timestampColumn, "watermark column", sourceTable, targetTable);
		}
		SyncTable table = new SyncTable(sourceTable, targetTable, timestampColumn, cursor, copiedSourceColumns,
				copiedTargetColumns, keys, this.properties.getBatchSize());
		logger.info("Resolved {}", table);
		return Optional.of(table);
	}

	private static void requireColumn(List<String> copiedColumns, String column, String what, String sourceTable,
			String targetTable) {
		if (copiedColumns.stream().noneMatch(column::equalsIgnoreCase)) {
			throw new IllegalStateException(
					"The " + what + " " + column + " of " + sourceTable + " does not exist in " + targetTable);
		}
	}

	private String resolveCursor(List<SourceColumn> sourceColumns, List<String> keyColumns, String sourceTable) {
		String configured = this.properties.getIdColumn();
		if (configured != null && !configured.isBlank()) {
			return resolveColumn(sourceColumns, configured, "migration.id-column", sourceTable);
		}
		if (keyColumns.size() == 1) {
			return keyColumns.get(0);
		}
		throw new IllegalStateException("Configure 'migration.id-column', " + sourceTable + " has "
				+ (keyColumns.isEmpty() ? "no primary key" : "the composite primary key " + keyColumns));
	}

	private static String resolveColumn(List<SourceColumn> columns, String requested, String property, String table) {
		if (requested == null || requested.isBlank()) {
			throw new IllegalStateException("Configure '" + property + "' for " + table);
		}
		return columns.stream()
				.map(SourceColumn::name)
				.filter(requested::equalsIgnoreCase)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"Configured " + property + " '" + requested + "' is not a column of " + table));
	}

	private List<SourceColumn> readSourceColumns(String table) {
		TableReference reference = TableReference.parse(table);
		try (Connection connection = this.sourceDataSource.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();
			for (String[] candidate : reference.candidates(connection.getSchema())) {
				List<OrderedColumn> ordered = new ArrayList<>();
				try (ResultSet rs = metaData.getColumns(null, candidate[0], candidate[1], null)) {
					while (rs.next()) {
						String columnName = rs.getString("COLUMN_NAME");
						String typeName = rs.getString("TYPE_NAME");
						if (isGeneratedColumn(rs)
								|| (typeName != null && UNSUPPORTED.contains(typeName.toUpperCase(Locale.ROOT)))) {
							continue;
						}
						SourceColumn column = new SourceColumn(columnName, typeName, rs.getInt("DATA_TYPE"),
								rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"),
								rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
						ordered.add(new OrderedColumn(rs.getInt("ORDINAL_POSITION"), column));
					}
				}
				if (!ordered.isEmpty()) {
					ordered.sort(Comparator.comparingInt(OrderedColumn::position));
					return ordered.stream().map(OrderedColumn::column).toList();
				}
			}
			return List.of();
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not read the columns of source table " + table, ex);
		}
	}

	private static List<String> readColumnNames(DataSource dataSource, String table) {
		return readColumns(dataSource, table).stream().map(SourceColumn::name).toList();
	}

	private static List<SourceColumn> readColumns(DataSource dataSource, String table) {
		TableReference reference = TableReference.parse(table);
		try (Connection connection = dataSource.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();
			for (String[] candidate : reference.candidates(connection.getSchema())) {
				List<OrderedColumn> ordered = new ArrayList<>();
				try (ResultSet rs = metaData.getColumns(null, candidate[0], candidate[1], null)) {
					while (rs.next()) {
						SourceColumn column = new SourceColumn(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"),
								rs.getInt("DATA_TYPE"), rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"),
								rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
						ordered.add(new OrderedColumn(rs.getInt("ORDINAL_POSITION"), column));
					}
				}
				if (!ordered.isEmpty()) {
					ordered.sort(Comparator.comparingInt(OrderedColumn::position));
					return ordered.stream().map(OrderedColumn::column).toList();
				}
			}
			return List.of();
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not read the columns of " + table, ex);
		}
	}

	private static List<String> readPrimaryKeys(DataSource dataSource, String table) {
		TableReference reference = TableReference.parse(table);
		try (Connection connection = dataSource.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();
			for (String[] candidate : reference.candidates(connection.getSchema())) {
				List<OrderedColumn> keys = new ArrayList<>();
				try (ResultSet rs = metaData.getPrimaryKeys(null, candidate[0], candidate[1])) {
					while (rs.next()) {
						keys.add(new OrderedColumn(rs.getShort("KEY_SEQ"),
								new SourceColumn(rs.getString("COLUMN_NAME"), null, 0, 0, 0, false)));
					}
				}
				if (!keys.isEmpty()) {
					keys.sort(Comparator.comparingInt(OrderedColumn::position));
					return keys.stream().map((key) -> key.column().name()).toList();
				}
			}
			return List.of();
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not read the primary key of " + table, ex);
		}
	}

	private static boolean isGeneratedColumn(ResultSet rs) {
		try {
			return "YES".equalsIgnoreCase(rs.getString("IS_GENERATEDCOLUMN"));
		}
		catch (SQLException ex) {
			return false;
		}
	}

	private record OrderedColumn(int position, SourceColumn column) {
	}

	/** {@code [SCHEMA.]TABLE} with the lookup candidates for the JDBC metadata calls. */
	private record TableReference(String schema, String table) {

		static TableReference parse(String reference) {
			String trimmed = reference.trim();
			int dot = trimmed.lastIndexOf('.');
			return dot > 0 ? new TableReference(trimmed.substring(0, dot), trimmed.substring(dot + 1))
					: new TableReference(null, trimmed);
		}

		List<String[]> candidates(String connectionSchema) {
			List<String[]> candidates = new ArrayList<>();
			if (this.schema != null) {
				candidates.add(new String[] { this.schema, this.table });
			}
			else if (connectionSchema != null && !connectionSchema.isBlank()) {
				candidates.add(new String[] { connectionSchema, this.table });
			}
			candidates.add(new String[] { null, this.table });
			candidates.add(new String[] { null, this.table.toUpperCase(Locale.ROOT) });
			if (this.schema == null) {
				candidates.add(new String[] { null, this.table.toLowerCase(Locale.ROOT) });
			}
			return candidates;
		}
	}

}
