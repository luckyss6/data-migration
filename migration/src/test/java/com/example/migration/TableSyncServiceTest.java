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
import com.example.migration.support.TestSchema;
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
 * End to end test against an Oracle flavoured source and a MySQL flavoured target database:
 * {@code sync_table_config} of the source lists the tables that exist in Oracle, and every one of them
 * is copied completely into a target table whose name carries the run date
 * ({@code t_order -> t_order20261014}).
 */
@SpringBootTest
@ActiveProfiles("test")
class TableSyncServiceTest {

	/** The engine builds the target table name from the run date, this test pins that date. */
	private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 14);

	private static final String DATE_SUFFIX = "20261014";

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
		TestSchema.createSyncTableConfig(this.source);
		this.target.execute("DROP TABLE IF EXISTS " + targetTable("t_order"));
		this.target.execute("DROP TABLE IF EXISTS " + targetTable("t_dict"));
		this.source.execute("DELETE FROM sync_table_config");
		configureSourceTable("t_order");
		configureSourceTable("t_dict");
		createSourceTable("t_order");
		createSourceTable("t_dict");
	}

	@Test
	void createsTheMissingTargetTableFromTheSourceTableAndCopiesEveryRow() {
		insertOrders(1, 1201, T1);
		insertDictionaries(1, 3);

		List<SyncResult> results = this.syncService.runAll("test");

		// the dated target table did not exist, it was created from the source table's structure
		assertThat(targetColumns(targetTable("t_order"))).containsOnlyKeys("id", "order_no", "amount", "remark",
				"update_time");
		assertThat(columnDetail(targetTable("t_order"), "order_no", "CHARACTER_MAXIMUM_LENGTH")).isEqualTo("60");
		assertThat(columnDetail(targetTable("t_order"), "amount", "NUMERIC_SCALE")).isEqualTo("2");
		// 1201 rows are copied in three pages of 500 rows
		assertThat(result(results, targetTable("t_order")).written()).isEqualTo(1201);
		assertThat(result(results, targetTable("t_dict")).written()).isEqualTo(3);
		assertThat(count(targetTable("t_order"))).isEqualTo(1201);
		assertThat(count(targetTable("t_dict"))).isEqualTo(3);
	}

	@Test
	void everyRunCopiesTheWholeTableAgainWithoutCreatingDuplicates() {
		insertOrders(1, 10, T1);

		List<SyncResult> first = this.syncService.runAll("test");
		List<SyncResult> second = this.syncService.runAll("test");

		assertThat(result(first, targetTable("t_order")).written()).isEqualTo(10);
		assertThat(result(second, targetTable("t_order")).written()).isEqualTo(10);
		assertThat(count(targetTable("t_order"))).isEqualTo(10);
	}

	@Test
	void aSourceTableWithoutATimestampColumnIsCopiedJustAsWell() {
		// t_dict has no UPDATE_TIME column at all, a full copy does not need one
		insertDictionaries(1, 3);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, targetTable("t_dict")).success()).isTrue();
		assertThat(targetColumns(targetTable("t_dict"))).containsOnlyKeys("id", "code", "label");
		assertThat(count(targetTable("t_dict"))).isEqualTo(3);
	}

	@Test
	void rowsTheSourceDroppedDisappearOnTheNextRunOfTheSameDay() {
		insertOrders(1, 5, T1);
		this.syncService.runAll("test");
		assertThat(count(targetTable("t_order"))).isEqualTo(5);

		this.source.update("DELETE FROM " + sourceTable("t_order") + " WHERE ID IN (2, 4)");
		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, targetTable("t_order")).written()).isEqualTo(3);
		assertThat(count(targetTable("t_order"))).isEqualTo(3);
		assertThat(this.target.queryForObject("SELECT COUNT(*) FROM " + targetTable("t_order")
				+ " WHERE id IN (2, 4)", Long.class)).isZero();
	}

	@Test
	void emptyingTheSourceEmptiesTheTargetTableOfThatDay() {
		insertOrders(1, 4, T1);
		this.syncService.runAll("test");

		this.source.update("DELETE FROM " + sourceTable("t_order"));
		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, targetTable("t_order")).written()).isZero();
		assertThat(count(targetTable("t_order"))).isZero();
	}

	@Test
	void theDayIsRebuiltSoChangedRowsAreNotDuplicated() {
		insertOrders(1, 3, T1);
		this.syncService.runAll("test");
		this.source.update("UPDATE " + sourceTable("t_order") + " SET AMOUNT = ?, ORDER_NO = ?, UPDATE_TIME = ? WHERE ID = 2",
				new BigDecimal("99.99"), "ORD-2-NEW", Timestamp.valueOf(T2));

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, targetTable("t_order")).written()).isEqualTo(3);
		assertThat(count(targetTable("t_order"))).isEqualTo(3);
		assertThat(this.target.queryForObject("SELECT amount FROM " + targetTable("t_order") + " WHERE id = 2",
				BigDecimal.class))
				.isEqualByComparingTo("99.99");
		assertThat(this.target.queryForObject("SELECT order_no FROM " + targetTable("t_order") + " WHERE id = 2",
				String.class))
				.isEqualTo("ORD-2-NEW");
	}

	@Test
	void anExistingTargetTableIsKeptAndOnlyItsCommonColumnsAreFilled() {
		// no remark column, and an extra column with a default value
		this.target.execute("CREATE TABLE " + targetTable("t_order")
				+ " (id BIGINT NOT NULL PRIMARY KEY, order_no VARCHAR(60) NOT NULL, "
				+ "amount DECIMAL(12,2) NULL, update_time DATETIME(3) NULL, note VARCHAR(20) DEFAULT 'zz')");
		insertOrders(1, 2, T1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, targetTable("t_order")).success()).isTrue();
		assertThat(targetColumns(targetTable("t_order"))).containsOnlyKeys("id", "order_no", "amount", "update_time",
				"note");
		assertThat(this.target.queryForObject("SELECT note FROM " + targetTable("t_order") + " WHERE id = 1",
				String.class)).isEqualTo("zz");
		assertThat(count(targetTable("t_order"))).isEqualTo(2);
	}

	@Test
	void aBadRecordIsLoggedAndSkipped() {
		// a pre-existing target column that is narrower than the source one
		this.target.execute("CREATE TABLE " + targetTable("t_order")
				+ " (id BIGINT NOT NULL PRIMARY KEY, order_no VARCHAR(10) NOT NULL, "
				+ "amount DECIMAL(12,2) NULL, remark LONGTEXT NULL, update_time DATETIME(3) NULL)");
		insertOrder(1, "ORD-1", T1);
		insertOrder(2, "FAR-TOO-LONG-FOR-THE-TARGET-COLUMN", T1);
		insertOrder(3, "ORD-3", T1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, targetTable("t_order")).success()).isTrue();
		assertThat(result(results, targetTable("t_order")).skipped()).isEqualTo(1);
		assertThat(count(targetTable("t_order"))).isEqualTo(2);
	}

	@Test
	void aConfiguredTableThatDoesNotExistInTheSourceIsSkipped() {
		this.source.execute("DROP TABLE " + sourceTable("t_dict"));
		insertOrders(1, 4, T1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(tables(results)).containsExactly("t_order");
		assertThat(count(targetTable("t_order"))).isEqualTo(4);
		assertThat(targetColumns(targetTable("t_dict"))).isEmpty();
	}

	@Test
	void aSourceTableThatAppearsLaterIsPickedUpOnTheNextRun() {
		this.source.execute("DROP TABLE " + sourceTable("t_dict"));
		insertOrders(1, 2, T1);

		List<SyncResult> first = this.syncService.runAll("test");
		assertThat(tables(first)).containsExactly("t_order");

		createSourceTable("t_dict");
		insertDictionaries(1, 3);
		List<SyncResult> second = this.syncService.runAll("test");

		assertThat(tables(second)).containsExactly("t_dict", "t_order");
		assertThat(count(targetTable("t_dict"))).isEqualTo(3);
	}

	@Test
	void aDisabledConfigRowIsIgnored() {
		insertOrders(1, 2, T1);
		this.source.update("UPDATE sync_table_config SET enabled = 0 WHERE source_table = 't_dict'");

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(tables(results)).containsExactly("t_order");
		assertThat(targetColumns(targetTable("t_dict"))).isEmpty();
	}

	@Test
	void nullValuesEspeciallyOfTheTimestampColumnAreCopiedAsNull() {
		this.source.update("INSERT INTO " + sourceTable("t_order")
				+ " (ID, ORDER_NO, AMOUNT, REMARK, UPDATE_TIME) VALUES (?, ?, ?, ?, ?)", 1L, "ORD-1",
				new BigDecimal("1.00"), "no timestamp", null);
		insertOrder(2, "ORD-2", T1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(count(targetTable("t_order"))).isEqualTo(2);
		assertThat(this.target.queryForObject("SELECT COUNT(*) FROM " + targetTable("t_order")
				+ " WHERE id = 1 AND update_time IS NULL", Long.class)).isEqualTo(1);
		assertThat(result(results, targetTable("t_order")).success()).isTrue();
	}

	@Test
	void anEmptyConfigTableSynchronisesNothing() {
		this.source.execute("DELETE FROM sync_table_config");
		insertOrders(1, 2, T1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(results).isEmpty();
		// not even the target table is created
		assertThat(targetColumns(targetTable("t_order"))).isEmpty();
	}

	@Test
	void aFailingTableDoesNotStopTheOtherTables() {
		// required_col is not part of the source table, so every insert into the target fails
		this.target.execute("CREATE TABLE " + targetTable("t_order")
				+ " (id BIGINT NOT NULL PRIMARY KEY, order_no VARCHAR(60) NOT NULL, "
				+ "amount DECIMAL(12,2) NULL, remark LONGTEXT NULL, update_time DATETIME(3) NULL, "
				+ "required_col VARCHAR(10) NOT NULL)");
		insertOrders(1, 3, T1);
		insertDictionaries(1, 2);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, targetTable("t_order")).success()).isFalse();
		assertThat(result(results, targetTable("t_dict")).success()).isTrue();
		assertThat(count(targetTable("t_order"))).isZero();
		assertThat(count(targetTable("t_dict"))).isEqualTo(2);
	}

	@Test
	void aConfigRowThatDoesNotFitTheSourceTableIsSkipped() {
		// T_NOID has CODE as primary key, so the configured id-column ID is not there
		configureSourceTable("t_noid");
		this.source.execute("CREATE TABLE T_NOID (CODE VARCHAR2(20) NOT NULL PRIMARY KEY, "
				+ "LABEL VARCHAR2(60), UPDATE_TIME TIMESTAMP(3))");
		insertOrders(1, 2, T1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(tables(results)).containsExactly("t_dict", "t_order");
		assertThat(targetColumns(targetTable("t_noid"))).isEmpty();
	}

	@Test
	void lobValuesSurviveTheCopy() {
		insertOrder(1, "ORD-1", T1);
		this.source.update("UPDATE " + sourceTable("t_order") + " SET REMARK = ? WHERE ID = 1", "中文备注 🚀");

		this.syncService.runAll("test");

		assertThat(this.target.queryForObject("SELECT remark FROM " + targetTable("t_order") + " WHERE id = 1",
				String.class))
				.isEqualTo("中文备注 🚀");
	}

	/** Table as it is named in the source database, e.g. t_order -> T_ORDER. */
	private static String sourceTable(String configuredTable) {
		return configuredTable.toUpperCase(Locale.ROOT);
	}

	/** Target table the engine derives from a source table, e.g. t_order -> t_order20261014. */
	private static String targetTable(String configuredTable) {
		return configuredTable + DateTimeFormatter.BASIC_ISO_DATE.format(RUN_DATE);
	}

	private void configureSourceTable(String configuredTable) {
		this.source.update("INSERT INTO sync_table_config (source_table) VALUES (?)", configuredTable);
	}

	private void createSourceTable(String configuredTable) {
		String name = sourceTable(configuredTable);
		this.source.execute("DROP TABLE IF EXISTS " + name);
		if ("t_order".equalsIgnoreCase(configuredTable)) {
			this.source.execute("CREATE TABLE " + name + " (ID NUMBER(10) NOT NULL, ORDER_NO VARCHAR2(60) NOT NULL, "
					+ "AMOUNT NUMBER(12,2), REMARK CLOB, UPDATE_TIME TIMESTAMP(3), PRIMARY KEY (ID))");
		}
		else {
			// deliberately without a timestamp column
			this.source.execute("CREATE TABLE " + name + " (ID NUMBER(6) NOT NULL, CODE VARCHAR2(20), "
					+ "LABEL VARCHAR2(60), PRIMARY KEY (ID))");
		}
	}

	private void insertOrders(int fromId, int count, LocalDateTime updateTime) {
		for (int id = fromId; id < fromId + count; id++) {
			insertOrder(id, "ORD-" + id, updateTime);
		}
	}

	private void insertOrder(int id, String orderNo, LocalDateTime updateTime) {
		this.source.update("INSERT INTO " + sourceTable("t_order")
				+ " (ID, ORDER_NO, AMOUNT, REMARK, UPDATE_TIME) VALUES (?, ?, ?, ?, ?)", (long) id, orderNo,
				new BigDecimal("10.50"), "remark " + id, Timestamp.valueOf(updateTime));
	}

	private void insertDictionaries(int fromId, int count) {
		for (int id = fromId; id < fromId + count; id++) {
			this.source.update("INSERT INTO " + sourceTable("t_dict") + " (ID, CODE, LABEL) VALUES (?, ?, ?)", (long) id,
					"C" + id, "label " + id);
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

	private static List<String> tables(List<SyncResult> results) {
		return results.stream()
				.map((result) -> result.table().toLowerCase(Locale.ROOT).replace(DATE_SUFFIX, ""))
				.sorted()
				.toList();
	}

	private static SyncResult result(List<SyncResult> results, String table) {
		return results.stream()
				.filter((result) -> result.table().equalsIgnoreCase(table))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No result for " + table + ": " + results));
	}

}
