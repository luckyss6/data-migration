package com.example.migration;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * End to end test against an Oracle flavoured source (the daily snapshots) and a MySQL flavoured
 * target database: {@code sync_table_config} says which target tables are filled, the source table of
 * a target table is derived from its name and the run date.
 */
@SpringBootTest
@ActiveProfiles("test")
class TableSyncServiceTest {

	/** The engine builds the source table name from the run date, this test pins that date. */
	private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 14);

	private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 1, 8, 0);

	private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 2, 8, 0);

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
		this.target.execute("DROP TABLE IF EXISTS t_dict");
		this.target.execute("DELETE FROM sync_table_config");
		configureTargetTable("t_order");
		configureTargetTable("t_dict");
		createSnapshot("t_order");
		createSnapshot("t_dict");
	}

	@Test
	void createsTheMissingTargetTableFromTheSnapshotAndCopiesEveryRow() {
		insertOrders(1, 1201, T1);
		insertDictionaries(1, 3);

		List<SyncResult> results = this.syncService.runAll("test");

		// the target table did not exist, it was created from the snapshot metadata
		assertThat(targetColumns("t_order")).containsOnlyKeys("id", "order_no", "amount", "remark", "update_time");
		assertThat(columnDetail("t_order", "order_no", "CHARACTER_MAXIMUM_LENGTH")).isEqualTo("60");
		assertThat(columnDetail("t_order", "amount", "NUMERIC_SCALE")).isEqualTo("2");
		// 1201 rows sharing one timestamp are copied in three pages of 500 rows
		assertThat(result(results, "t_order").written()).isEqualTo(1201);
		assertThat(result(results, "t_dict").written()).isEqualTo(3);
		assertThat(count("t_order")).isEqualTo(1201);
		assertThat(count("t_dict")).isEqualTo(3);
		assertThat(watermark("t_order")).isEqualTo(T1);
	}

	@Test
	void onlyRowsNewerThanTheWatermarkOfTheTargetTableAreCopied() {
		insertOrders(1, 10, T1);
		this.syncService.runAll("test");

		insertOrders(11, 5, T2);
		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_order").written()).isEqualTo(5);
		assertThat(count("t_order")).isEqualTo(15);
		assertThat(watermark("t_order")).isEqualTo(T2);
	}

	@Test
	void updatedRowsAreUpsertedInsteadOfDuplicated() {
		insertOrders(1, 3, T1);
		this.syncService.runAll("test");
		this.source.update("UPDATE " + snapshot("t_order") + " SET AMOUNT = ?, ORDER_NO = ?, UPDATE_TIME = ? WHERE ID = 2",
				new BigDecimal("99.99"), "ORD-2-NEW", Timestamp.valueOf(T2));

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_order").written()).isEqualTo(1);
		assertThat(count("t_order")).isEqualTo(3);
		assertThat(this.target.queryForObject("SELECT amount FROM t_order WHERE id = 2", BigDecimal.class))
				.isEqualByComparingTo("99.99");
		assertThat(this.target.queryForObject("SELECT order_no FROM t_order WHERE id = 2", String.class))
				.isEqualTo("ORD-2-NEW");
	}

	@Test
	void anExistingTargetTableIsKeptAndOnlyItsCommonColumnsAreFilled() {
		// no remark column, and an extra column with a default value
		this.target.execute("CREATE TABLE t_order (id BIGINT NOT NULL PRIMARY KEY, order_no VARCHAR(60) NOT NULL, "
				+ "amount DECIMAL(12,2) NULL, update_time DATETIME(3) NULL, note VARCHAR(20) DEFAULT 'zz')");
		insertOrders(1, 2, T1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_order").success()).isTrue();
		assertThat(targetColumns("t_order")).containsOnlyKeys("id", "order_no", "amount", "update_time", "note");
		assertThat(this.target.queryForObject("SELECT note FROM t_order WHERE id = 1", String.class)).isEqualTo("zz");
		assertThat(count("t_order")).isEqualTo(2);
	}

	@Test
	void aBadRecordIsLoggedAndSkipped() {
		// a pre-existing target column that is narrower than the source one
		this.target.execute("CREATE TABLE t_order (id BIGINT NOT NULL PRIMARY KEY, order_no VARCHAR(10) NOT NULL, "
				+ "amount DECIMAL(12,2) NULL, remark LONGTEXT NULL, update_time DATETIME(3) NULL)");
		insertOrder(1, "ORD-1", T1);
		insertOrder(2, "FAR-TOO-LONG-FOR-THE-TARGET-COLUMN", T1);
		insertOrder(3, "ORD-3", T1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_order").success()).isTrue();
		assertThat(result(results, "t_order").skipped()).isEqualTo(1);
		assertThat(count("t_order")).isEqualTo(2);
	}

	@Test
	void aTargetTableWithoutASnapshotForTheRunDateIsNotCreated() {
		this.source.execute("DROP TABLE " + snapshot("t_dict"));
		insertOrders(1, 4, T1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(tables(results)).containsExactly("t_order");
		assertThat(count("t_order")).isEqualTo(4);
		assertThat(targetColumns("t_dict")).isEmpty();
	}

	@Test
	void aSnapshotThatAppearsLaterIsPickedUpOnTheNextRun() {
		this.source.execute("DROP TABLE " + snapshot("t_dict"));
		insertOrders(1, 2, T1);

		List<SyncResult> first = this.syncService.runAll("test");
		assertThat(tables(first)).containsExactly("t_order");

		createSnapshot("t_dict");
		insertDictionaries(1, 3);
		List<SyncResult> second = this.syncService.runAll("test");

		assertThat(tables(second)).containsExactly("t_dict", "t_order");
		assertThat(count("t_dict")).isEqualTo(3);
	}

	@Test
	void aDisabledConfigRowIsIgnored() {
		insertOrders(1, 2, T1);
		this.target.update("UPDATE sync_table_config SET enabled = 0 WHERE target_table = 't_dict'");

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(tables(results)).containsExactly("t_order");
		assertThat(targetColumns("t_dict")).isEmpty();
	}

	@Test
	void rowsWithoutAWatermarkAreNotPartOfTheIncrementalStream() {
		this.source.update("INSERT INTO " + snapshot("t_order")
				+ " (ID, ORDER_NO, AMOUNT, REMARK, UPDATE_TIME) VALUES (?, ?, ?, ?, ?)", 1L, "ORD-1",
				new BigDecimal("1.00"), "no watermark", null);
		insertOrder(2, "ORD-2", T1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(count("t_order")).isEqualTo(1);
		assertThat(watermark("t_order")).isEqualTo(T1);
		assertThat(result(results, "t_order").success()).isTrue();
	}

	@Test
	void anEmptyConfigTableSynchronisesNothing() {
		this.target.execute("DELETE FROM sync_table_config");
		insertOrders(1, 2, T1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(results).isEmpty();
		// not even the target table is created
		assertThat(targetColumns("t_order")).isEmpty();
	}

	@Test
	void aFailingTableDoesNotStopTheOtherTables() {
		// required_col is not part of the snapshot, so every insert into t_order fails
		this.target.execute("CREATE TABLE t_order (id BIGINT NOT NULL PRIMARY KEY, order_no VARCHAR(60) NOT NULL, "
				+ "amount DECIMAL(12,2) NULL, remark LONGTEXT NULL, update_time DATETIME(3) NULL, "
				+ "required_col VARCHAR(10) NOT NULL)");
		insertOrders(1, 3, T1);
		insertDictionaries(1, 2);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_order").success()).isFalse();
		assertThat(result(results, "t_dict").success()).isTrue();
		assertThat(count("t_order")).isZero();
		assertThat(count("t_dict")).isEqualTo(2);
	}

	@Test
	void aConfigRowThatDoesNotFitTheSnapshotIsSkipped() {
		// the snapshot of t_noid has CODE as primary key, so the configured id-column ID is not there
		configureTargetTable("t_noid");
		this.source.execute("CREATE TABLE T_NOID20261014 (CODE VARCHAR2(20) NOT NULL PRIMARY KEY, "
				+ "LABEL VARCHAR2(60), UPDATE_TIME TIMESTAMP(3))");
		insertOrders(1, 2, T1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(tables(results)).containsExactly("t_dict", "t_order");
		assertThat(targetColumns("t_noid")).isEmpty();
	}

	@Test
	void lobValuesSurviveTheCopy() {
		insertOrder(1, "ORD-1", T1);
		this.source.update("UPDATE " + snapshot("t_order") + " SET REMARK = ? WHERE ID = 1", "中文备注 🚀");

		this.syncService.runAll("test");

		assertThat(this.target.queryForObject("SELECT remark FROM t_order WHERE id = 1", String.class))
				.isEqualTo("中文备注 🚀");
	}

	/** Source table the engine derives from a target table, e.g. t_order -> T_ORDER20261014. */
	private static String snapshot(String targetTable) {
		return targetTable.toUpperCase(Locale.ROOT) + DateTimeFormatter.BASIC_ISO_DATE.format(RUN_DATE);
	}

	private void configureTargetTable(String targetTable) {
		this.target.update("INSERT INTO sync_table_config (target_table) VALUES (?)", targetTable);
	}

	private void createSnapshot(String targetTable) {
		String name = snapshot(targetTable);
		this.source.execute("DROP TABLE IF EXISTS " + name);
		if ("t_order".equalsIgnoreCase(targetTable)) {
			this.source.execute("CREATE TABLE " + name + " (ID NUMBER(10) NOT NULL, ORDER_NO VARCHAR2(60) NOT NULL, "
					+ "AMOUNT NUMBER(12,2), REMARK CLOB, UPDATE_TIME TIMESTAMP(3), PRIMARY KEY (ID))");
		}
		else {
			this.source.execute("CREATE TABLE " + name + " (ID NUMBER(6) NOT NULL, CODE VARCHAR2(20), "
					+ "LABEL VARCHAR2(60), UPDATE_TIME TIMESTAMP(3), PRIMARY KEY (ID))");
		}
	}

	private void insertOrders(int fromId, int count, LocalDateTime updateTime) {
		for (int id = fromId; id < fromId + count; id++) {
			insertOrder(id, "ORD-" + id, updateTime);
		}
	}

	private void insertOrder(int id, String orderNo, LocalDateTime updateTime) {
		this.source.update("INSERT INTO " + snapshot("t_order")
				+ " (ID, ORDER_NO, AMOUNT, REMARK, UPDATE_TIME) VALUES (?, ?, ?, ?, ?)", (long) id, orderNo,
				new BigDecimal("10.50"), "remark " + id, Timestamp.valueOf(updateTime));
	}

	private void insertDictionaries(int fromId, int count) {
		for (int id = fromId; id < fromId + count; id++) {
			this.source.update("INSERT INTO " + snapshot("t_dict")
					+ " (ID, CODE, LABEL, UPDATE_TIME) VALUES (?, ?, ?, ?)", (long) id, "C" + id, "label " + id,
					Timestamp.valueOf(T1));
		}
	}

	private long count(String table) {
		return this.target.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
	}

	/** Column name to JDBC type name of a target table, empty when the table does not exist. */
	private Map<String, String> targetColumns(String table) {
		return this.target.query(
				"SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = UPPER(?) "
						+ "ORDER BY ORDINAL_POSITION",
				(rs) -> {
					Map<String, String> columns = new LinkedHashMap<>();
					while (rs.next()) {
						columns.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), rs.getString("DATA_TYPE"));
					}
					return columns;
				}, table);
	}

	private String columnDetail(String table, String column, String field) {
		return String.valueOf(this.target.queryForObject("SELECT " + field
				+ " FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = UPPER(?) AND UPPER(COLUMN_NAME) = UPPER(?)",
				Object.class, table, column));
	}

	/** The engine reads MAX(update_time) of the target table to know where to continue. */
	private LocalDateTime watermark(String table) {
		List<LocalDateTime> values = this.target.query("SELECT MAX(update_time) FROM " + table,
				(rs, rowNum) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toLocalDateTime());
		return values.isEmpty() ? null : values.get(0);
	}

	private static List<String> tables(List<SyncResult> results) {
		return results.stream().map((result) -> result.table().toLowerCase(Locale.ROOT)).sorted().toList();
	}

	private static SyncResult result(List<SyncResult> results, String table) {
		return results.stream()
				.filter((result) -> result.table().equalsIgnoreCase(table))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No result for " + table + ": " + results));
	}

}
