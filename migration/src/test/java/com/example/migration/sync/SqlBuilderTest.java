package com.example.migration.sync;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlBuilderTest {

	private static SyncTable order() {
		return new SyncTable("ORDER", "order20261014", "ID", List.of("ID", "ORDER_NO", "UPDATE_TIME"),
				List.of("id", "order_no", "update_time"), List.of("ID"), 500);
	}

	@Test
	void theFirstPageReadsTheWholeTableInCursorOrder() {
		String firstPage = SqlBuilder.selectPage(order(), true, 500);

		assertThat(firstPage).startsWith("SELECT ID, ORDER_NO, UPDATE_TIME FROM ORDER");
		assertThat(firstPage).doesNotContain("WHERE");
		assertThat(firstPage).endsWith("ORDER BY ID ASC FETCH FIRST 500 ROWS ONLY");
		assertThat(SqlBuilder.pageArgs(order(), true, null)).isEmpty();
	}

	@Test
	void laterPagesContinueAfterTheCursorOfThePreviousPage() {
		assertThat(SqlBuilder.selectPage(order(), false, 500)).contains("WHERE ID > ?");
		assertThat(SqlBuilder.pageArgs(order(), false, 7L)).containsExactly(7L);
	}

	@Test
	void upsertUsesTheTargetColumnNamesAndLeavesTheKeyColumnsAlone() {
		assertThat(SqlBuilder.upsert(order()))
				.isEqualTo("INSERT INTO order20261014 (id, order_no, update_time) VALUES (?, ?, ?) "
						+ "ON DUPLICATE KEY UPDATE order_no = VALUES(order_no), update_time = VALUES(update_time)");
	}

	@Test
	void upsertOfATableWithKeyColumnsOnlyStaysAValidStatement() {
		SyncTable keyOnly = new SyncTable("LINK", "link20261014", "ID", List.of("ID", "TAG"),
				List.of("id", "tag"), List.of("ID", "TAG"), 100);

		assertThat(SqlBuilder.upsert(keyOnly))
				.isEqualTo("INSERT INTO link20261014 (id, tag) VALUES (?, ?) ON DUPLICATE KEY UPDATE id = VALUES(id)");
	}

}
