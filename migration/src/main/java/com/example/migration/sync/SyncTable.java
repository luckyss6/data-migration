package com.example.migration.sync;

import java.util.List;

/** A table that has been resolved against the source schema and is ready to be synchronised. */
public record SyncTable(String sourceTable, String targetTable, String timestampColumn, String idColumn,
		List<String> sourceColumns, List<String> targetColumns, List<String> keyColumns, int batchSize) {

	/** Incremental tables are filtered by the watermark, full tables are copied row by row. */
	public boolean incremental() {
		return this.timestampColumn != null;
	}

	public boolean isKeyColumn(String sourceColumn) {
		return this.keyColumns.stream().anyMatch((key) -> key.equalsIgnoreCase(sourceColumn));
	}

	/**
	 * Target column the watermark lives in: the current watermark of a table is the highest value of
	 * this column that the target table already contains.
	 */
	public String watermarkColumn() {
		for (int index = 0; index < this.sourceColumns.size(); index++) {
			if (this.sourceColumns.get(index).equalsIgnoreCase(this.timestampColumn)) {
				return this.targetColumns.get(index);
			}
		}
		throw new IllegalStateException(
				"Watermark column " + this.timestampColumn + " is not part of " + this.sourceTable);
	}

	@Override
	public String toString() {
		return this.sourceTable + " -> " + this.targetTable + " (" + (incremental() ? "incremental" : "full")
				+ ", cursor=" + this.idColumn + (incremental() ? ", watermark=" + this.timestampColumn : "")
				+ ", batchSize=" + this.batchSize + ")";
	}

}
