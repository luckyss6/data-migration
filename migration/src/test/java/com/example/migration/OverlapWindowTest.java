package com.example.migration;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import com.example.migration.config.DataSourceConfig;
import com.example.migration.sync.SyncResult;
import com.example.migration.sync.TableSyncService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code migration.watermark-overlap}: every run starts a bit before the last watermark on purpose,
 * to pick up rows that were written while the previous run was in progress. The upsert keeps that
 * harmless, this test proves it.
 */
@SpringBootTest(properties = "migration.watermark-overlap=1h")
@ActiveProfiles("test")
class OverlapWindowTest {

	private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 14);

	private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 1, 8, 0);

	@TestConfiguration
	static class FixedClock {

		@Bean
		@Primary
		Clock migrationTestClock() {
			return Clock.fixed(Instant.parse("2026-10-14T02:30:00Z"), ZoneId.of("Asia/Shanghai"));
		}
	}

	@Autowired
	private TableSyncService syncService;

	@Autowired
	@Qualifier(DataSourceConfig.SOURCE_JDBC_TEMPLATE)
	private JdbcTemplate source;

	@Autowired
	@Qualifier(DataSourceConfig.TARGET_JDBC_TEMPLATE)
	private JdbcTemplate target;

	@BeforeEach
	void reset() {
		this.target.execute("DROP TABLE IF EXISTS t_order");
		this.target.execute("DELETE FROM sync_table_config");
		this.target.update("INSERT INTO sync_table_config (target_table) VALUES ('t_order')");
		this.source.execute("DROP TABLE IF EXISTS T_ORDER20261014");
		this.source.execute("CREATE TABLE T_ORDER20261014 (ID NUMBER(10) NOT NULL, ORDER_NO VARCHAR2(60) NOT NULL, "
				+ "AMOUNT NUMBER(12,2), REMARK CLOB, UPDATE_TIME TIMESTAMP(3), PRIMARY KEY (ID))");
		for (int id = 1; id <= 3; id++) {
			this.source.update("INSERT INTO T_ORDER20261014 (ID, ORDER_NO, AMOUNT, REMARK, UPDATE_TIME) "
					+ "VALUES (?, ?, ?, ?, ?)", (long) id, "ORD-" + id, new BigDecimal("10.50"), "remark",
					Timestamp.valueOf(T1));
		}
	}

	@Test
	void reReadingTheOverlapWindowDoesNotDuplicateRows() {
		List<SyncResult> first = this.syncService.runAll("test");
		List<SyncResult> second = this.syncService.runAll("test");

		assertThat(result(first, "t_order").written()).isEqualTo(3);
		// the watermark moved one hour back, so the same three rows are read and upserted again
		assertThat(result(second, "t_order").written()).isEqualTo(3);
		assertThat(count("t_order")).isEqualTo(3);
		assertThat(watermark("t_order")).isEqualTo(T1);
	}

	@Test
	void aRowWrittenDuringThePreviousRunIsPickedUpByTheNextRun() {
		// simulates a row that appeared after the first run had already read the data
		this.syncService.runAll("test");
		this.source.update("INSERT INTO T_ORDER20261014 (ID, ORDER_NO, AMOUNT, REMARK, UPDATE_TIME) "
				+ "VALUES (?, ?, ?, ?, ?)", 4L, "ORD-4", new BigDecimal("1.00"), "late", Timestamp.valueOf(T1));

		List<SyncResult> second = this.syncService.runAll("test");

		assertThat(result(second, "t_order").written()).isEqualTo(4);
		assertThat(count("t_order")).isEqualTo(4);
	}

	private long count(String table) {
		return this.target.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
	}

	private LocalDateTime watermark(String table) {
		List<LocalDateTime> values = this.target.query("SELECT MAX(update_time) FROM " + table,
				(rs, rowNum) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toLocalDateTime());
		return values.isEmpty() ? null : values.get(0);
	}

	private static SyncResult result(List<SyncResult> results, String table) {
		return results.stream().filter((result) -> result.table().equalsIgnoreCase(table)).findFirst().orElseThrow();
	}

}
