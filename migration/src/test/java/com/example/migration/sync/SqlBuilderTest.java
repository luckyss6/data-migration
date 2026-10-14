package com.example.migration.sync;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlBuilderTest {

	private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);

	private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 14, 2, 30);

	private static SyncTable incremental() {
		return new SyncTable("ORDER20261014", "order", "UPDATE_TIME", "ID",
				List.of("ID", "ORDER_NO", "UPDATE_TIME"), List.of("id", "order_no", "update_time"), List.of("ID"), 500);
	}

	private static SyncTable full() {
		return new SyncTable("DICT20261014", "dict", null, "ID", List.of("ID", "CODE"), List.of("id", "code"),
				List.of("ID"), 100);
	}

	@Test
	void incrementalModeFiltersOnTheWatermarkAndPagesOverTheCursor() {
		String firstPage = SqlBuilder.selectPage(incremental(), true, true, 500);
		assertThat(firstPage).startsWith("SELECT ID, ORDER_NO, UPDATE_TIME FROM ORDER20261014");
		assertThat(firstPage).contains("WHERE UPDATE_TIME IS NOT NULL AND UPDATE_TIME > ? AND UPDATE_TIME <= ?");
		assertThat(firstPage).endsWith("ORDER BY UPDATE_TIME ASC, ID ASC FETCH FIRST 500 ROWS ONLY");

		// on the very first run there is no watermark yet
		assertThat(SqlBuilder.selectPage(incremental(), true, false, 500)).doesNotContain("UPDATE_TIME > ?");

		// later pages continue after the last (watermark, cursor) pair that was read
		assertThat(SqlBuilder.selectPage(incremental(), false, true, 500))
				.contains("(UPDATE_TIME > ? OR (UPDATE_TIME = ? AND ID > ?))");

		assertThat(SqlBuilder.pageArgs(incremental(), true, true, FROM, TO, null, null))
				.containsExactly(Timestamp.valueOf(FROM), Timestamp.valueOf(TO));
		assertThat(SqlBuilder.pageArgs(incremental(), false, true, FROM, TO, FROM, 42L))
				.containsExactly(Timestamp.valueOf(FROM), Timestamp.valueOf(FROM), 42L, Timestamp.valueOf(TO));
	}

	@Test
	void fullModeCopiesTheWholeSnapshotInCursorOrder() {
		String firstPage = SqlBuilder.selectPage(full(), true, false, 100);
		assertThat(firstPage).doesNotContain("WHERE");
		assertThat(firstPage).endsWith("ORDER BY ID ASC FETCH FIRST 100 ROWS ONLY");
		assertThat(SqlBuilder.selectPage(full(), false, false, 100)).contains("WHERE ID > ?");
		assertThat(SqlBuilder.pageArgs(full(), false, false, null, null, null, 7L)).containsExactly(7L);
	}

	@Test
	void upsertUsesTheTargetColumnNamesAndLeavesTheKeyColumnsAlone() {
		assertThat(SqlBuilder.upsert(incremental())).isEqualTo("INSERT INTO order (id, order_no, update_time) "
				+ "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE order_no = VALUES(order_no), update_time = VALUES(update_time)");
	}

	@Test
	void upsertOfATableWithKeyColumnsOnlyStaysAValidStatement() {
		SyncTable keyOnly = new SyncTable("LINK20261014", "link", null, "ID", List.of("ID", "TAG"),
				List.of("id", "tag"), List.of("ID", "TAG"), 100);

		assertThat(SqlBuilder.upsert(keyOnly))
				.isEqualTo("INSERT INTO link (id, tag) VALUES (?, ?) ON DUPLICATE KEY UPDATE id = VALUES(id)");
	}

}
