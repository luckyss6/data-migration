package com.example.migration.sync;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import com.example.migration.config.DataSourceConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Creates a missing target table from the metadata of the source snapshot, so a new daily snapshot
 * only needs a row in the config table. An existing target table is left untouched.
 */
@Component
public class TargetTableCreator {

	private static final Logger logger = LoggerFactory.getLogger(TargetTableCreator.class);

	private final DataSource targetDataSource;

	private final OracleToMySqlTypeMapper typeMapper;

	public TargetTableCreator(@Qualifier(DataSourceConfig.TARGET_DATA_SOURCE) DataSource targetDataSource,
			OracleToMySqlTypeMapper typeMapper) {
		this.targetDataSource = targetDataSource;
		this.typeMapper = typeMapper;
	}

	/**
	 * Creates the target table when it does not exist yet.
	 * @return {@code true} when the table was created
	 */
	public boolean createIfMissing(String targetTable, List<SourceColumn> columns, List<String> keyColumns,
			String tableOptions) {
		if (tableExists(targetTable)) {
			return false;
		}
		String ddl = createTableSql(targetTable, columns, keyColumns, tableOptions);
		logger.info("Creating target table {}", targetTable);
		logger.debug("DDL: {}", ddl.replace('\n', ' '));
		try (Connection connection = this.targetDataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute(ddl);
			return true;
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not create the target table " + targetTable, ex);
		}
	}

	/** {@code CREATE TABLE} statement derived from the source snapshot. */
	public String createTableSql(String targetTable, List<SourceColumn> columns, List<String> keyColumns,
			String tableOptions) {
		List<String> definitions = new ArrayList<>();
		for (SourceColumn column : columns) {
			boolean notNull = !column.nullable() || containsIgnoreCase(keyColumns, column.name());
			definitions.add("  " + targetName(column.name()) + " " + this.typeMapper.toMySqlType(column)
					+ (notNull ? " NOT NULL" : ""));
		}
		if (!keyColumns.isEmpty()) {
			definitions.add("  PRIMARY KEY (" + keyColumns.stream().map(TargetTableCreator::targetName)
					.reduce((left, right) -> left + ", " + right).orElse("") + ")");
		}
		StringBuilder sql = new StringBuilder("CREATE TABLE ").append(targetTable).append(" (\n")
				.append(String.join(",\n", definitions))
				.append("\n)");
		if (tableOptions != null && !tableOptions.isBlank()) {
			sql.append(' ').append(tableOptions.trim());
		}
		return sql.toString();
	}

	/** Target column name of a source column. */
	public static String targetName(String sourceColumn) {
		return sourceColumn.toLowerCase(Locale.ROOT);
	}

	private boolean tableExists(String tableName) {
		String plain = tableName.contains(".") ? tableName.substring(tableName.lastIndexOf('.') + 1) : tableName;
		try (Connection connection = this.targetDataSource.getConnection()) {
			var metaData = connection.getMetaData();
			for (String candidate : new String[] { plain, TargetTableCreator.targetName(plain),
					plain.toUpperCase(Locale.ROOT) }) {
				try (ResultSet rs = metaData.getTables(connection.getCatalog(), connection.getSchema(), candidate,
						new String[] { "TABLE" })) {
					if (rs.next()) {
						return true;
					}
				}
			}
			return false;
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not check whether the target table " + tableName + " exists", ex);
		}
	}

	private static boolean containsIgnoreCase(List<String> values, String value) {
		return values.stream().anyMatch((candidate) -> candidate.equalsIgnoreCase(value));
	}

}
