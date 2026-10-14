package com.example.migration.sync;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds the statements the engine needs: one paged select, one numbers-less upsert. */
final class SqlBuilder {

	private SqlBuilder() {
	}

	/**
	 * Reads a page of the source table ordered by (watermark, cursor). Paging over both columns keeps
	 * the read consistent when many rows share the same timestamp.
	 *
	 * @param firstPage first page of a run
	 * @param hasWatermark {@code false} on the very first run, when there is nothing to filter on yet
	 * @param pageSize number of rows to read, inlined because not every database accepts a bind
	 * parameter in the {@code FETCH FIRST} clause
	 */
	static String selectPage(SyncTable table, boolean firstPage, boolean hasWatermark, int pageSize) {
		StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", table.sourceColumns()))
				.append(" FROM ").append(table.sourceTable());
		List<String> filters = new ArrayList<>();
		if (table.incremental()) {
			String watermark = table.timestampColumn();
			String cursor = table.idColumn();
			filters.add(watermark + " IS NOT NULL");
			if (firstPage) {
				if (hasWatermark) {
					filters.add(watermark + " > ?");
				}
			}
			else {
				filters.add("(" + watermark + " > ? OR (" + watermark + " = ? AND " + cursor + " > ?))");
			}
			filters.add(watermark + " <= ?");
		}
		else if (!firstPage) {
			filters.add(table.idColumn() + " > ?");
		}
		if (!filters.isEmpty()) {
			sql.append(" WHERE ").append(String.join(" AND ", filters));
		}
		sql.append(" ORDER BY ");
		if (table.incremental()) {
			sql.append(table.timestampColumn()).append(" ASC, ");
		}
		sql.append(table.idColumn()).append(" ASC");
		return sql.append(" FETCH FIRST ").append(pageSize).append(" ROWS ONLY").toString();
	}

	static List<Object> pageArgs(SyncTable table, boolean firstPage, boolean hasWatermark, LocalDateTime from,
			LocalDateTime to, LocalDateTime cursorTimestamp, Object cursorId) {
		List<Object> args = new ArrayList<>();
		if (table.incremental()) {
			if (firstPage) {
				if (hasWatermark) {
					args.add(toSqlTimestamp(from));
				}
			}
			else {
				args.add(toSqlTimestamp(cursorTimestamp));
				args.add(toSqlTimestamp(cursorTimestamp));
				args.add(cursorId);
			}
			args.add(toSqlTimestamp(to));
		}
		else if (!firstPage) {
			args.add(cursorId);
		}
		return args;
	}

	/** Idempotent write: running the same page twice never creates a duplicate row. */
	static String upsert(SyncTable table) {
		List<String> columns = table.targetColumns();
		StringBuilder sql = new StringBuilder("INSERT INTO ").append(table.targetTable()).append(" (")
				.append(String.join(", ", columns))
				.append(") VALUES (")
				.append(String.join(", ", Collections.nCopies(columns.size(), "?")))
				.append(")");
		List<String> assignments = new ArrayList<>();
		for (int index = 0; index < columns.size(); index++) {
			if (!table.isKeyColumn(table.sourceColumns().get(index))) {
				String column = columns.get(index);
				assignments.add(column + " = VALUES(" + column + ")");
			}
		}
		if (assignments.isEmpty()) {
			assignments.add(columns.get(0) + " = VALUES(" + columns.get(0) + ")");
		}
		return sql.append(" ON DUPLICATE KEY UPDATE ").append(String.join(", ", assignments)).toString();
	}

	private static Timestamp toSqlTimestamp(LocalDateTime value) {
		return value == null ? null : Timestamp.valueOf(value);
	}

}
