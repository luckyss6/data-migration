package com.example.migration.sync;

/** Result of copying one table. */
public record SyncResult(String table, String status, long written, long skipped, long durationMs) {

	public boolean success() {
		return "SUCCESS".equals(this.status);
	}

	public String describe() {
		return this.status + " " + this.table + ": written=" + this.written + " skipped=" + this.skipped
				+ " duration=" + this.durationMs + "ms";
	}

}
