package com.example.migration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global settings of the synchronisation ({@code migration.*}).
 *
 * <p>The tables to copy are the ones listed in the {@code sync_table_config} table of the
 * <em>source</em> database, so only tables that exist in the source are migrated. Every configured
 * source table is copied into a snapshot table of the target database whose name carries the run
 * date, e.g. {@code order -> order20261014}. Every run copies the whole table.
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

	/** Target table name of a source table: {@code {table}} is the configured source table, {@code {date}} the run date. */
	private String targetTablePattern = "{table}{date}";

	/** Date format used for {@code {date}}. */
	private String datePattern = "yyyyMMdd";

	/** Shift the date, e.g. {@code -1} to fill yesterday's snapshot table in a nightly job. */
	private int dateOffsetDays = 0;

	/** Source column used as pagination cursor, empty means "single column primary key". */
	private String idColumn = "ID";

	/** Appended to a generated {@code CREATE TABLE}, e.g. the MySQL storage engine and charset. */
	private String tableOptions = "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

	private final Connection source = new Connection();

	private final Connection target = new Connection();

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

	public String getTargetTablePattern() {
		return this.targetTablePattern;
	}

	public void setTargetTablePattern(String targetTablePattern) {
		this.targetTablePattern = targetTablePattern;
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

	public String getIdColumn() {
		return this.idColumn;
	}

	public void setIdColumn(String idColumn) {
		this.idColumn = idColumn;
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
