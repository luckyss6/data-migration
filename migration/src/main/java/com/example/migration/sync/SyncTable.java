package com.example.migration.sync;

import java.util.List;

/** A table that has been resolved against the source schema and is ready to be synchronised. */
public record SyncTable(String sourceTable, String targetTable, String idColumn, List<String> sourceColumns,
		List<String> targetColumns, List<String> keyColumns, int batchSize) {

	public boolean isKeyColumn(String sourceColumn) {
		return this.keyColumns.stream().anyMatch((key) -> key.equalsIgnoreCase(sourceColumn));
	}

	@Override
	public String toString() {
		return this.sourceTable + " -> " + this.targetTable + " (cursor=" + this.idColumn + ", batchSize="
				+ this.batchSize + ")";
	}

}
