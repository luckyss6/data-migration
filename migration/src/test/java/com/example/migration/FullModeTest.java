package com.example.migration;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * {@code migration.sync-mode=FULL}: the whole snapshot is copied on every run, which is the mode for
 * snapshots without a modification timestamp.
 */
@SpringBootTest(properties = "migration.sync-mode=FULL")
@ActiveProfiles("test")
class FullModeTest {

	private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 14);

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
		this.target.execute("DROP TABLE IF EXISTS t_dict");
		this.target.execute("DELETE FROM sync_table_config");
		this.target.update("INSERT INTO sync_table_config (target_table) VALUES ('t_dict')");
		// no UPDATE_TIME column: this snapshot can only be copied completely
		this.source.execute("DROP TABLE IF EXISTS T_DICT20261014");
		this.source.execute("CREATE TABLE T_DICT20261014 (ID NUMBER(6) NOT NULL, CODE VARCHAR2(20), "
				+ "LABEL VARCHAR2(60), PRIMARY KEY (ID))");
		for (int id = 1; id <= 3; id++) {
			this.source.update("INSERT INTO T_DICT20261014 (ID, CODE, LABEL) VALUES (?, ?, ?)", (long) id, "C" + id,
					"label " + id);
		}
	}

	@Test
	void theWholeSnapshotIsCopiedOnEveryRunWithoutCreatingDuplicates() {
		List<SyncResult> first = this.syncService.runAll("test");
		List<SyncResult> second = this.syncService.runAll("test");

		assertThat(result(first, "t_dict").written()).isEqualTo(3);
		assertThat(result(second, "t_dict").written()).isEqualTo(3);
		// no watermark in FULL mode
		assertThat(result(second, "t_dict").watermark()).isNull();
		assertThat(count("t_dict")).isEqualTo(3);
	}

	@Test
	void changedRowsAreUpdatedInFullMode() {
		this.syncService.runAll("test");
		this.source.update("UPDATE T_DICT20261014 SET LABEL = ? WHERE ID = 2", "changed");

		this.syncService.runAll("test");

		assertThat(this.target.queryForObject("SELECT label FROM t_dict WHERE id = 2", String.class))
				.isEqualTo("changed");
		assertThat(count("t_dict")).isEqualTo(3);
	}

	private long count(String table) {
		return this.target.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
	}

	private static SyncResult result(List<SyncResult> results, String table) {
		return results.stream().filter((result) -> result.table().equalsIgnoreCase(table)).findFirst().orElseThrow();
	}

}
