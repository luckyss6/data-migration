package com.example.migration.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global settings of the synchronisation ({@code migration.*}).
 *
 * <p>The tables to copy are not configured at all: they are the tables that exist in the target
 * database, and the matching source table is derived from the target table name plus a date, e.g.
 * {@code order -> order20261014}.
 */
@ConfigurationProperties(prefix = "migration")
public class MigrationProperties {

	/** Cron expression of the daily run. */
	private String cron = "0 30 2 * * ?";

	/** How many tables are synchronised at the same time, one thread per table. */
	private int threads = 4;

	/** Rows per page read from the source and rows per JDBC batch written to the target. */
	private int batchSize = 2000;

	/** JDBC fetch size used when reading from Oracle. */
	private int fetchSize = 1000;

	/** Run one synchronisation while the application starts. */
	private boolean runOnStartup = false;

	/** Re-read this much data on every run to absorb rows written while a run is in progress. */
	private Duration watermarkOverlap = Duration.ofMinutes(1);

	/** Source table name of a target table: {@code {table}} is the target table, {@code {date}} the run date. */
	private String sourceTablePattern = "{table}{date}";

	/** Date format used for {@code {date}}. */
	private String datePattern = "yyyyMMdd";

	/** Shift the date, e.g. {@code -1} to read yesterday's snapshot in a nightly job. */
	private int dateOffsetDays = 0;

	/** Source column holding the last modification time, used in INCREMENTAL mode. */
	private String timestampColumn = "UPDATE_TIME";

	/** Source column used as pagination cursor, empty means "single column primary key". */
	private String idColumn = "ID";

	private Mode syncMode = Mode.INCREMENTAL;

	/** Appended to a generated {@code CREATE TABLE}, e.g. the MySQL storage engine and charset. */
	private String tableOptions = "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

	private final Connection source = new Connection();

	private final Connection target = new Connection();

	public enum Mode {

		/** Copy the rows that are newer than the target table's watermark. */
		INCREMENTAL,

		/** Copy the whole snapshot on every run. */
		FULL
	}

	/** JDBC settings of one database. */
	public static class Connection {

		private String url;

		private String username;

		private String password;

		private String driverClassName;

		private int poolSize = 5;

		public String getUrl() {
			return this.url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

		public String getUsername() {
			return this.username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return this.password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public String getDriverClassName() {
			return this.driverClassName;
		}

		public void setDriverClassName(String driverClassName) {
			this.driverClassName = driverClassName;
		}

		public int getPoolSize() {
			return this.poolSize;
		}

		public void setPoolSize(int poolSize) {
			this.poolSize = poolSize;
		}
	}

	public String getCron() {
		return this.cron;
	}

	public void setCron(String cron) {
		this.cron = cron;
	}

	public int getThreads() {
		return this.threads;
	}

	public void setThreads(int threads) {
		this.threads = threads;
	}

	public int getBatchSize() {
		return this.batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	public int getFetchSize() {
		return this.fetchSize;
	}

	public void setFetchSize(int fetchSize) {
		this.fetchSize = fetchSize;
	}

	public boolean isRunOnStartup() {
		return this.runOnStartup;
	}

	public void setRunOnStartup(boolean runOnStartup) {
		this.runOnStartup = runOnStartup;
	}

	public Duration getWatermarkOverlap() {
		return this.watermarkOverlap;
	}

	public void setWatermarkOverlap(Duration watermarkOverlap) {
		this.watermarkOverlap = watermarkOverlap;
	}

	public String getSourceTablePattern() {
		return this.sourceTablePattern;
	}

	public void setSourceTablePattern(String sourceTablePattern) {
		this.sourceTablePattern = sourceTablePattern;
	}

	public String getDatePattern() {
		return this.datePattern;
	}

	public void setDatePattern(String datePattern) {
		this.datePattern = datePattern;
	}

	public int getDateOffsetDays() {
		return this.dateOffsetDays;
	}

	public void setDateOffsetDays(int dateOffsetDays) {
		this.dateOffsetDays = dateOffsetDays;
	}

	public String getTimestampColumn() {
		return this.timestampColumn;
	}

	public void setTimestampColumn(String timestampColumn) {
		this.timestampColumn = timestampColumn;
	}

	public String getIdColumn() {
		return this.idColumn;
	}

	public void setIdColumn(String idColumn) {
		this.idColumn = idColumn;
	}

	public Mode getSyncMode() {
		return this.syncMode;
	}

	public void setSyncMode(Mode syncMode) {
		this.syncMode = syncMode;
	}

	public String getTableOptions() {
		return this.tableOptions;
	}

	public void setTableOptions(String tableOptions) {
		this.tableOptions = tableOptions;
	}

	public Connection getSource() {
		return this.source;
	}

	public Connection getTarget() {
		return this.target;
	}

}
