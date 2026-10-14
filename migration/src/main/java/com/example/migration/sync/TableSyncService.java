package com.example.migration.sync;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.migration.config.DataSourceConfig;
import com.example.migration.config.MigrationProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Copies the tables listed in the {@code sync_table_config} table of the source into the matching
 * snapshot tables of MySQL.
 *
 * <p>Every run rebuilds the target table of the run date completely: its previous content is deleted
 * first, then the rows are read page by page in cursor order and written as one JDBC batch per page.
 * Running the same day twice therefore ends with exactly the source content - rows the source dropped
 * in the meantime disappear, nothing is duplicated. Records that cannot be written are logged and
 * skipped, a failing page fails that table.
 */
@Service
public class TableSyncService {

	private static final Logger logger = LoggerFactory.getLogger(TableSyncService.class);

	private final JdbcTemplate sourceJdbcTemplate;

	private final JdbcTemplate targetJdbcTemplate;

	private final TransactionTemplate transactionTemplate;

	private final SyncTableConfigDao config;

	private final SyncTableFactory tableFactory;

	private final ValueConverter converter;

	private final MigrationProperties properties;

	private final Clock clock;

	/** Guards against two runs at the same time, e.g. a manual trigger while the schedule is running. */
	private final AtomicBoolean running = new AtomicBoolean(false);

	public TableSyncService(@Qualifier(DataSourceConfig.SOURCE_JDBC_TEMPLATE) JdbcTemplate sourceJdbcTemplate,
			@Qualifier(DataSourceConfig.TARGET_JDBC_TEMPLATE) JdbcTemplate targetJdbcTemplate,
			PlatformTransactionManager transactionManager, SyncTableConfigDao config, SyncTableFactory tableFactory,
			ValueConverter converter, MigrationProperties properties, Clock clock) {
		this.sourceJdbcTemplate = sourceJdbcTemplate;
		this.targetJdbcTemplate = targetJdbcTemplate;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.config = config;
		this.tableFactory = tableFactory;
		this.converter = converter;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * The tables to copy: the source tables of {@code sync_table_config} that really exist in the
	 * source. The target table of each of them is derived from the run date, see
	 * {@code migration.target-table-pattern}. Tables that are not there are skipped.
	 */
	public List<SyncTable> tables() {
		LocalDate date = snapshotDate();
		List<SyncTable> resolved = new ArrayList<>();
		for (String sourceTable : this.config.findEnabledSourceTables()) {
			try {
				this.tableFactory.resolve(sourceTable, date).ifPresent(resolved::add);
			}
			catch (RuntimeException ex) {
				logger.error("Skipping source table {}: {}", sourceTable, ex.getMessage());
			}
		}
		logger.info("{} table(s) to synchronise into the snapshot of {}", resolved.size(), date);
		return resolved;
	}

	/** Date of the snapshot that is written by this run. */
	public LocalDate snapshotDate() {
		return LocalDate.now(this.clock).minusDays(this.properties.getDateOffsetDays());
	}

	/** Source tables of {@code sync_table_config}, used for the startup log. */
	public List<String> configuredTables() {
		return this.config.findEnabledSourceTables();
	}

	/**
	 * Copies every configured table once. One thread per table, at most {@code migration.threads} of
	 * them run at the same time; a failing table never stops the other ones.
	 *
	 * @throws IllegalStateException when another run is still in progress
	 */
	public List<SyncResult> runAll(String trigger) {
		if (!this.running.compareAndSet(false, true)) {
			throw new IllegalStateException("A synchronisation run is already in progress");
		}
		try {
			return doRunAll(trigger);
		}
		finally {
			this.running.set(false);
		}
	}

	/** Whether a run is currently in progress. */
	public boolean isRunning() {
		return this.running.get();
	}

	private List<SyncResult> doRunAll(String trigger) {
		List<SyncTable> configured = tables();
		if (configured.isEmpty()) {
			logger.warn("No enabled table in sync_table_config, nothing to do");
			return List.of();
		}
		int threads = Math.max(1, Math.min(this.properties.getThreads(), configured.size()));
		logger.info("Synchronising {} table(s) with {} thread(s)", configured.size(), threads);
		AtomicInteger worker = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(threads, (runnable) -> {
			Thread thread = new Thread(runnable, "sync-worker-" + worker.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
		try {
			List<Future<SyncResult>> futures = new ArrayList<>();
			for (SyncTable table : configured) {
				futures.add(executor.submit(() -> sync(table)));
			}
			List<SyncResult> results = new ArrayList<>();
			for (int index = 0; index < futures.size(); index++) {
				results.add(await(futures.get(index), configured.get(index), executor));
			}
			long written = results.stream().mapToLong(SyncResult::written).sum();
			long skipped = results.stream().mapToLong(SyncResult::skipped).sum();
			logger.info("Synchronisation {}: {} table(s) on {} thread(s), written={}, skipped={}", trigger,
					results.size(), threads, written, skipped);
			results.forEach((result) -> logger.info("  {}", result.describe()));
			return results;
		}
		finally {
			executor.shutdown();
		}
	}

	private SyncResult await(Future<SyncResult> future, SyncTable table, ExecutorService executor) {
		try {
			return future.get();
		}
		catch (ExecutionException ex) {
			logger.error("Table {} failed unexpectedly", table.targetTable(), ex.getCause());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			executor.shutdownNow();
			logger.error("Interrupted while waiting for table {}", table.targetTable());
		}
		return new SyncResult(table.targetTable(), "FAILED", 0, 0, 0);
	}

	private SyncResult sync(SyncTable table) {
		long startedAt = System.currentTimeMillis();
		long written = 0;
		long skipped = 0;
		Object cursorId = null;
		try {
			long cleared = clearTarget(table);
			if (cleared > 0) {
				logger.info("Cleared {} row(s) of {} before the copy", cleared, table.targetTable());
			}
			logger.info("Reading {}", table.sourceTable());
			while (true) {
				boolean firstPage = cursorId == null;
				List<Row> page = this.sourceJdbcTemplate.query(
						SqlBuilder.selectPage(table, firstPage, table.batchSize()),
						(rs, rowNum) -> readRow(table, rs),
						SqlBuilder.pageArgs(table, firstPage, cursorId).toArray());
				if (page.isEmpty()) {
					break;
				}
				Page result = write(table, page);
				written += result.written();
				skipped += result.skipped();
				cursorId = page.get(page.size() - 1).id();
				if (page.size() < table.batchSize()) {
					break;
				}
			}
			if (skipped > 0) {
				logger.warn("{} row(s) of {} could not be written and were skipped", skipped, table.targetTable());
			}
			return new SyncResult(table.targetTable(), "SUCCESS", written, skipped,
					System.currentTimeMillis() - startedAt);
		}
		catch (RuntimeException ex) {
			logger.error("Table {} failed after {} row(s), the next run copies the table again",
					table.targetTable(), written, ex);
			return new SyncResult(table.targetTable(), "FAILED", written, skipped,
					System.currentTimeMillis() - startedAt);
		}
	}

	/** Rows the previous run of the same day left behind, they are replaced by the new content. */
	private long clearTarget(SyncTable table) {
		return this.targetJdbcTemplate.update("DELETE FROM " + table.targetTable());
	}

	private Page write(SyncTable table, List<Row> page) {
		String sql = SqlBuilder.upsert(table);
		try {
			this.transactionTemplate.executeWithoutResult((status) -> this.targetJdbcTemplate.batchUpdate(sql,
					page.stream().map((row) -> row.values().toArray()).toList()));
			return new Page(page.size(), 0);
		}
		catch (DataAccessException ex) {
			logger.warn("Writing {} row(s) into {} as one batch failed ({}), retrying them one by one", page.size(),
					table.targetTable(), ex.getMostSpecificCause().getMessage());
			return writeOneByOne(table, sql, page);
		}
	}

	/** Isolates bad records: the good ones are committed, the others only end up in the log. */
	private Page writeOneByOne(SyncTable table, String sql, List<Row> page) {
		long written = 0;
		long skipped = 0;
		for (Row row : page) {
			try {
				this.transactionTemplate.executeWithoutResult(
						(status) -> this.targetJdbcTemplate.update(sql, row.values().toArray()));
				written++;
			}
			catch (RuntimeException ex) {
				skipped++;
				logger.warn("Skipping {}.{}: {}", table.targetTable(), row.idAsString(), message(ex));
			}
		}
		if (page.size() > 1 && skipped == page.size()) {
			throw new IllegalStateException("None of the " + page.size() + " rows of " + table.targetTable()
					+ " could be written");
		}
		return new Page(written, skipped);
	}

	private Row readRow(SyncTable table, ResultSet rs) throws SQLException {
		List<String> columns = table.sourceColumns();
		List<Object> values = new ArrayList<>(columns.size());
		Object id = null;
		for (int index = 0; index < columns.size(); index++) {
			String column = columns.get(index);
			Object raw = rs.getObject(index + 1);
			values.add(this.converter.convert(raw));
			if (column.equalsIgnoreCase(table.idColumn())) {
				id = raw;
			}
		}
		if (id == null) {
			throw new IllegalStateException(
					"Cursor column " + table.idColumn() + " of " + table.sourceTable() + " is null");
		}
		return new Row(id, values);
	}

	private static String message(Throwable error) {
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root.getClass().getSimpleName() + ": " + root.getMessage();
	}

	private record Row(Object id, List<Object> values) {

		String idAsString() {
			return this.id == null ? null : String.valueOf(this.id);
		}
	}

	private record Page(long written, long skipped) {
	}

}
