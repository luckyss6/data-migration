package com.example.migration.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds the statements the engine needs: one paged select, one numbers-less upsert. */
final class SqlBuilder {

	private SqlBuilder() {
	}

	/**
	 * Reads a page of the source table in cursor order. Every run starts at the first row, so the whole
	 * table is copied again.
	 *
	 * @param firstPage first page of a run
	 * @param pageSize number of rows to read, inlined because not every database accepts a bind
	 * parameter in the {@code FETCH FIRST} clause
	 */
	static String selectPage(SyncTable table, boolean firstPage, int pageSize) {
		StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", table.sourceColumns()))
				.append(" FROM ").append(table.sourceTable());
		if (!firstPage) {
			sql.append(" WHERE ").append(table.idColumn()).append(" > ?");
		}
		return sql.append(" ORDER BY ").append(table.idColumn()).append(" ASC")
				.append(" FETCH FIRST ").append(pageSize).append(" ROWS ONLY").toString();
	}

	/** Arguments of {@link #selectPage}, only the cursor of the previous page. */
	static List<Object> pageArgs(SyncTable table, boolean firstPage, Object cursorId) {
		List<Object> args = new ArrayList<>();
		if (!firstPage) {
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

}
