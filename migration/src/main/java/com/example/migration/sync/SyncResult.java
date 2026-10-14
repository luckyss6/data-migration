package com.example.migration.sync;

import java.time.LocalDateTime;

/** Result of copying one table. */
public record SyncResult(String table, String status, long written, long skipped, LocalDateTime watermark,
		long durationMs) {

	public boolean success() {
		return "SUCCESS".equals(this.status);
	}

	public String describe() {
		return this.status + " " + this.table + ": written=" + this.written + " skipped=" + this.skipped
				+ " watermark=" + this.watermark + " duration=" + this.durationMs + "ms";
	}

}
