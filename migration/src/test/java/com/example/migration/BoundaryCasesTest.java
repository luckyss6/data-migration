package com.example.migration;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import com.example.migration.config.DataSourceConfig;
import com.example.migration.config.MigrationProperties;
import com.example.migration.support.TestSchema;
import com.example.migration.sync.SyncResult;
import com.example.migration.sync.TableSyncService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * Boundary cases around the page loop: the last page of a table, a cursor that is not unique, a
 * cursor that is NULL, a table that fails as a whole and configuration values at the edge of what
 * makes sense. The tests pin the behaviour of the engine at exactly those edges.
 */
@SpringBootTest
@ActiveProfiles("test")
class BoundaryCasesTest {

	/** The engine builds the target table name from the run date, this test pins that date. */
	private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 14);

	private static final String DATE_SUFFIX = DateTimeFormatter.BASIC_ISO_DATE.format(RUN_DATE);

	/** Small pages, so a boundary is reached with a handful of rows. */
	private static final int PAGE = 4;

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

	@Autowired
	private MigrationProperties properties;

	private int originalBatchSize;

	private int originalThreads;

	private int originalDateOffsetDays;

	private String originalIdColumn;

	@BeforeEach
	void setUp() {
		this.originalBatchSize = this.properties.getBatchSize();
		this.originalThreads = this.properties.getThreads();
		this.originalDateOffsetDays = this.properties.getDateOffsetDays();
		this.originalIdColumn = this.properties.getIdColumn();
		TestSchema.createSyncTableConfig(this.source);
		// this class configures its own tables, never the ones of the other test classes
		this.source.execute("DELETE FROM sync_table_config");
		this.properties.setBatchSize(PAGE);
		for (String table : new String[] { "t_page", "t_grp", "t_onecol", "t_case", "t_rev" }) {
			this.source.execute("DROP TABLE IF EXISTS " + sourceTable(table));
			this.target.execute("DROP TABLE IF EXISTS " + targetTable(table));
		}
	}

	@AfterEach
	void restoreProperties() {
		this.properties.setBatchSize(this.originalBatchSize);
		this.properties.setThreads(this.originalThreads);
		this.properties.setDateOffsetDays(this.originalDateOffsetDays);
		this.properties.setIdColumn(this.originalIdColumn);
	}

	// ------------------------------------------------------------------ page boundaries

	@Test
	void aTableWithExactlyOnePageIsCopiedCompletely() {
		configure("t_page");
		createPageTable();
		insertPages(1, PAGE);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_page").written()).isEqualTo(PAGE);
		assertThat(count("t_page")).isEqualTo(PAGE);
	}

	@Test
	void aTableWithOneRowMoreThanAPageIsCopiedCompletely() {
		configure("t_page");
		createPageTable();
		insertPages(1, PAGE + 1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_page").written()).isEqualTo(PAGE + 1);
		assertThat(count("t_page")).isEqualTo(PAGE + 1);
	}

	@Test
	void aTableWithExactlyTwoFullPagesIsCopiedCompletely() {
		configure("t_page");
		createPageTable();
		insertPages(1, 2 * PAGE);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_page").written()).isEqualTo(2 * PAGE);
		assertThat(count("t_page")).isEqualTo(2 * PAGE);
	}

	@Test
	void aTableWithSparseCursorValuesIsCopiedCompletely() {
		configure("t_page");
		createPageTable();
		for (long id : new long[] { 1, 5, 9, 13, 17, 21 }) {
			insertPageRow(id, "row " + id);
		}

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_page").written()).isEqualTo(6);
		assertThat(count("t_page")).isEqualTo(6);
	}

	@Test
	void aSingleRowTableIsCopiedCompletely() {
		configure("t_page");
		createPageTable();
		insertPages(1, 1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_page").written()).isEqualTo(1);
		assertThat(count("t_page")).isEqualTo(1);
	}

	@Test
	void anEmptySourceTableCreatesTheTargetTableAndCopiesNothing() {
		configure("t_page");
		createPageTable();

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_page").success()).isTrue();
		assertThat(result(results, "t_page").written()).isZero();
		assertThat(count("t_page")).isZero();
	}

	@Test
	void aSourceTableWithOnlyTheCursorColumnIsCopied() {
		configure("t_onecol");
		this.source.execute("CREATE TABLE " + sourceTable("t_onecol") + " (ID NUMBER(10) NOT NULL PRIMARY KEY)");
		for (long id = 1; id <= 5; id++) {
			this.source.update("INSERT INTO " + sourceTable("t_onecol") + " (ID) VALUES (?)", id);
		}

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_onecol").success()).isTrue();
		assertThat(count("t_onecol")).isEqualTo(5);
	}

	@Test
	void aCursorColumnThatIsNotTheFirstColumnIsCopied() {
		configure("t_rev");
		this.source.execute("CREATE TABLE " + sourceTable("t_rev") + " (LABEL VARCHAR2(20) NOT NULL, "
				+ "ID NUMBER(10) NOT NULL, PRIMARY KEY (ID))");
		for (long id = 1; id <= 6; id++) {
			this.source.update("INSERT INTO " + sourceTable("t_rev") + " (LABEL, ID) VALUES (?, ?)", "label " + id, id);
		}

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_rev").written()).isEqualTo(6);
		assertThat(this.target.queryForObject("SELECT label FROM " + targetTable("t_rev") + " WHERE id = 3",
				String.class))
				.isEqualTo("label 3");
	}

	@Test
	void microsecondTimestampsSurviveTheCopy() {
		configure("t_page");
		this.source.execute("CREATE TABLE " + sourceTable("t_page") + " (ID NUMBER(10) NOT NULL, "
				+ "LABEL VARCHAR2(60), UPDATE_TIME TIMESTAMP(6), PRIMARY KEY (ID))");
		LocalDateTime micros = LocalDateTime.of(2026, 10, 1, 8, 0, 0, 123_456_000);
		this.source.update("INSERT INTO " + sourceTable("t_page") + " (ID, LABEL, UPDATE_TIME) VALUES (?, ?, ?)", 1L,
				"row 1", Timestamp.valueOf(micros));

		this.syncService.runAll("test");

		assertThat(this.target.queryForObject("SELECT update_time FROM " + targetTable("t_page") + " WHERE id = 1",
				LocalDateTime.class))
				.isEqualTo(micros);
	}

	@Test
	void anExistingTargetTableWithADifferentColumnOrderIsFilledByName() {
		configure("t_page");
		createPageTable();
		this.target.execute("CREATE TABLE " + targetTable("t_page")
				+ " (label VARCHAR(60) NULL, id INT NOT NULL PRIMARY KEY)");
		insertPages(1, 3);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_page").written()).isEqualTo(3);
		assertThat(this.target.queryForObject("SELECT label FROM " + targetTable("t_page") + " WHERE id = 2",
				String.class))
				.isEqualTo("row 2");
	}

	// ------------------------------------------------------------------ cursor column

	@Test
	@Disabled("BUG: the cursor is only compared with '>', duplicates are never detected - "
			+ "the rows behind such a value are dropped and the run still reports SUCCESS")
	void rowsThatShareTheCursorValueAreNotSilentlyDropped() {
		configure("t_grp");
		createGroupTable();
		for (long id = 1; id <= 10; id++) {
			insertGroupRow(id, 1L);
		}
		this.properties.setIdColumn("GRP");

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_grp").success()).isTrue();
		assertThat(result(results, "t_grp").written()).isEqualTo(10);
		assertThat(count("t_grp")).isEqualTo(10);
	}

	@Test
	@Disabled("BUG: a cursor value that repeats across a page boundary loses the rows behind it")
	void rowsAreNotLostWhenTheCursorRepeatsAcrossAPageBoundary() {
		configure("t_grp");
		createGroupTable();
		for (long id = 1; id <= 3; id++) {
			insertGroupRow(id, 1L);
		}
		for (long id = 4; id <= 6; id++) {
			insertGroupRow(id, 2L);
		}
		this.properties.setIdColumn("GRP");

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_grp").written()).isEqualTo(6);
		assertThat(count("t_grp")).isEqualTo(6);
	}

	@Test
	void aNullCursorValueFailsTheTableInsteadOfCopyingHalfOfIt() {
		configure("t_grp");
		createGroupTable();
		insertGroupRow(1, 5L);
		insertGroupRowWithNullCursor(2);
		insertGroupRow(3, 7L);
		this.properties.setIdColumn("GRP");

		List<SyncResult> results = this.syncService.runAll("test");

		// the unusable cursor value aborts the table, the target is left empty - at least it is loud
		assertThat(result(results, "t_grp").success()).isFalse();
		assertThat(count("t_grp")).isZero();
	}

	@Test
	void aBlankCursorColumnFallsBackToThePrimaryKey() {
		configure("t_page");
		createPageTable();
		insertPages(1, 6);
		this.properties.setIdColumn("   ");

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_page").written()).isEqualTo(6);
		assertThat(count("t_page")).isEqualTo(6);
	}

	// ------------------------------------------------------------------ failing rows and pages

	@Test
	void aFailingRowInEveryPageIsSkippedAndTheRestIsCopied() {
		// the pre-existing target column is too narrow for the ORDER_NO of the rows 3 and 6
		this.target.execute("CREATE TABLE " + targetTable("t_page")
				+ " (id INT NOT NULL PRIMARY KEY, label VARCHAR(10) NULL)");
		configure("t_page");
		createPageTable();
		for (long id = 1; id <= 8; id++) {
			insertPageRow(id, id == 3 || id == 6 ? "far-too-long-for-the-target" : "row " + id);
		}

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_page").success()).isTrue();
		assertThat(result(results, "t_page").written()).isEqualTo(6);
		assertThat(result(results, "t_page").skipped()).isEqualTo(2);
		assertThat(count("t_page")).isEqualTo(6);
	}

	@Test
	@Disabled("BUG: writeOneByOne only fails the table when the page has more than one row, so a "
			+ "single row table that cannot be written is reported as SUCCESS with written=0")
	void aSingleRowTableThatCannotBeWrittenIsReportedAsFailed() {
		this.target.execute("CREATE TABLE " + targetTable("t_page")
				+ " (id INT NOT NULL PRIMARY KEY, label VARCHAR(60) NULL, required_col VARCHAR(10) NOT NULL)");
		configure("t_page");
		createPageTable();
		insertPageRow(1, "row 1");

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_page").success()).isFalse();
		assertThat(count("t_page")).isZero();
	}

	// ------------------------------------------------------------------ configuration edges

	@Test
	@Disabled("BUG: 'batch-size: 0' reads no row at all and still reports SUCCESS, an empty snapshot "
			+ "looks like a table without rows")
	void aZeroBatchSizeDoesNotSilentlyCopyNothing() {
		configure("t_page");
		createPageTable();
		insertPages(1, 3);
		this.properties.setBatchSize(0);

		this.syncService.runAll("test");

		assertThat(count("t_page")).isEqualTo(3);
	}

	@Test
	void aNegativeBatchSizeFailsLoudly() {
		configure("t_page");
		createPageTable();
		insertPages(1, 3);
		this.properties.setBatchSize(-1);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_page").success()).isFalse();
		assertThat(result(results, "t_page").written()).isZero();
	}

	@Test
	void aPositiveDateOffsetWritesYesterdaysSnapshotTable() {
		configure("t_page");
		createPageTable();
		insertPages(1, 2);
		// pins what the code does today: the offset is subtracted from the run date
		this.properties.setDateOffsetDays(1);

		List<SyncResult> results = this.syncService.runAll("test");

		String yesterday = "t_page" + DateTimeFormatter.BASIC_ISO_DATE.format(RUN_DATE.minusDays(1));
		assertThat(results).extracting(SyncResult::table).containsExactly(yesterday);
		assertThat(this.target.queryForObject("SELECT COUNT(*) FROM " + yesterday, Long.class)).isEqualTo(2);
	}

	@Test
	@Disabled("BUG: the offset is subtracted (minusDays), so '-1' writes TOMORROW's snapshot table - the "
			+ "opposite of what README and MigrationProperties promise for the nightly job")
	void aNegativeDateOffsetWritesYesterdaysSnapshotTable() {
		configure("t_page");
		createPageTable();
		insertPages(1, 2);
		this.properties.setDateOffsetDays(-1);

		List<SyncResult> results = this.syncService.runAll("test");

		String yesterday = "t_page" + DateTimeFormatter.BASIC_ISO_DATE.format(RUN_DATE.minusDays(1));
		assertThat(results).extracting(SyncResult::table).containsExactly(yesterday);
		assertThat(this.target.queryForObject("SELECT COUNT(*) FROM " + yesterday, Long.class)).isEqualTo(2);
	}

	@Test
	void zeroThreadsStillCopiesEveryTable() {
		configure("t_page", "t_onecol");
		createPageTable();
		insertPages(1, 3);
		this.source.execute("CREATE TABLE " + sourceTable("t_onecol") + " (ID NUMBER(10) NOT NULL PRIMARY KEY)");
		this.source.update("INSERT INTO " + sourceTable("t_onecol") + " (ID) VALUES (1)");
		this.properties.setThreads(0);

		List<SyncResult> results = this.syncService.runAll("test");

		assertThat(result(results, "t_page").written()).isEqualTo(3);
		assertThat(result(results, "t_onecol").written()).isEqualTo(1);
	}

	@Test
	@Disabled("BUG: two config rows that differ only in case resolve to the same source table and, "
			+ "on MySQL (lower_case_table_names=1), to the same target table - two threads then clear "
			+ "and fill that one table at the same time")
	void theSameTableConfiguredUnderTwoCasingsIsNotCopiedByTwoThreadsAtOnce() {
		configure("t_case", "T_CASE");
		this.source.execute("CREATE TABLE " + sourceTable("t_case") + " (ID NUMBER(10) NOT NULL, "
				+ "LABEL VARCHAR2(60), PRIMARY KEY (ID))");
		for (long id = 1; id <= 5; id++) {
			this.source.update("INSERT INTO " + sourceTable("t_case") + " (ID, LABEL) VALUES (?, ?)", id, "row " + id);
		}

		List<SyncResult> results = this.syncService.runAll("test");

		List<String> targets = results.stream()
				.map((result) -> result.table().toLowerCase(Locale.ROOT))
				.toList();
		assertThat(targets).doesNotHaveDuplicates();
		assertThat(count("t_case")).isEqualTo(5);
	}

	// ------------------------------------------------------------------ fixtures and helpers

	private void configure(String... configuredTables) {
		for (String table : configuredTables) {
			this.source.update("INSERT INTO sync_table_config (source_table) VALUES (?)", table);
		}
	}

	private void createPageTable() {
		this.source.execute("CREATE TABLE " + sourceTable("t_page") + " (ID NUMBER(10) NOT NULL, "
				+ "LABEL VARCHAR2(60), PRIMARY KEY (ID))");
	}

	private void createGroupTable() {
		this.source.execute("CREATE TABLE " + sourceTable("t_grp") + " (ID NUMBER(10) NOT NULL, "
				+ "GRP NUMBER(10), LABEL VARCHAR2(60), PRIMARY KEY (ID))");
	}

	private void insertPages(long fromId, int count) {
		for (long id = fromId; id < fromId + count; id++) {
			insertPageRow(id, "row " + id);
		}
	}

	private void insertPageRow(long id, String label) {
		this.source.update("INSERT INTO " + sourceTable("t_page") + " (ID, LABEL) VALUES (?, ?)", id, label);
	}

	private void insertGroupRow(long id, Long grp) {
		this.source.update("INSERT INTO " + sourceTable("t_grp") + " (ID, GRP, LABEL) VALUES (?, ?, ?)", id, grp,
				"row " + id);
	}

	private void insertGroupRowWithNullCursor(long id) {
		this.source.update(
				"INSERT INTO " + sourceTable("t_grp") + " (ID, GRP, LABEL) VALUES (" + id + ", NULL, 'row " + id + "')");
	}

	/** Table as it is named in the source database, e.g. t_page -> T_PAGE. */
	private static String sourceTable(String configuredTable) {
		return configuredTable.toUpperCase(Locale.ROOT);
	}

	/** Target table the engine derives from a source table, e.g. t_page -> t_page20261014. */
	private static String targetTable(String configuredTable) {
		return configuredTable + DATE_SUFFIX;
	}

	private long count(String configuredTable) {
		return this.target.queryForObject("SELECT COUNT(*) FROM " + targetTable(configuredTable), Long.class);
	}

	private static SyncResult result(List<SyncResult> results, String table) {
		String target = targetTable(table);
		return results.stream()
				.filter((result) -> result.table().equalsIgnoreCase(target))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No result for " + table + ": " + results));
	}

}
