package com.example.migration.runner;

import java.util.List;

import com.example.migration.config.MigrationProperties;
import com.example.migration.sync.TableSyncService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Logs the effective configuration and copies everything once when {@code migration.run-on-startup}
 * is enabled or when the application is started with {@code --sync-now}.
 */
@Component
public class SyncStartupRunner implements ApplicationRunner {

	private static final Logger logger = LoggerFactory.getLogger(SyncStartupRunner.class);

	private final TableSyncService syncService;

	private final MigrationProperties properties;

	public SyncStartupRunner(TableSyncService syncService, MigrationProperties properties) {
		this.syncService = syncService;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		logger.info("Source {} -> target {}", this.properties.getSource().getUrl(), this.properties.getTarget().getUrl());
		logger.info("Cron '{}', threads={}, batchSize={}, fetchSize={}, watermarkOverlap={}", this.properties.getCron(),
				this.properties.getThreads(), this.properties.getBatchSize(), this.properties.getFetchSize(),
				this.properties.getWatermarkOverlap());
		warnAboutPoolSizes();
		warnAboutBatchRewriting();
		logger.info("Source table pattern '{}' (date pattern '{}', offset {} day(s)), mode {}",
				this.properties.getSourceTablePattern(), this.properties.getDatePattern(),
				this.properties.getDateOffsetDays(), this.properties.getSyncMode());
		try {
			List<String> targetTables = this.syncService.configuredTables();
			if (targetTables.isEmpty()) {
				logger.warn("sync_table_config is empty, nothing will be synchronised");
			}
			else {
				logger.info("{} target table(s) in sync_table_config: {}", targetTables.size(), targetTables);
			}
		}
		catch (RuntimeException ex) {
			logger.warn("Could not read sync_table_config: {}", ex.getMessage());
		}
		if (this.properties.isRunOnStartup() || args.containsOption("sync-now")) {
			logger.info("Running one synchronisation on startup");
			try {
				this.syncService.runAll("startup");
			}
			catch (IllegalStateException ex) {
				logger.warn("{}", ex.getMessage());
			}
		}
	}

	/** Every running table needs a connection on both sides. */
	private void warnAboutPoolSizes() {
		int threads = this.properties.getThreads();
		if (this.properties.getSource().getPoolSize() < threads) {
			logger.warn("Source pool-size {} is smaller than threads {}, tables will wait for a connection",
					this.properties.getSource().getPoolSize(), threads);
		}
		if (this.properties.getTarget().getPoolSize() < threads) {
			logger.warn("Target pool-size {} is smaller than threads {}, tables will wait for a connection",
					this.properties.getTarget().getPoolSize(), threads);
		}
	}

	/** Without this MySQL flag a JDBC batch is sent as many single statements. */
	private void warnAboutBatchRewriting() {
		String url = this.properties.getTarget().getUrl();
		if (url != null && url.contains(":mysql:") && !url.contains("rewriteBatchedStatements=true")) {
			logger.warn("Add 'rewriteBatchedStatements=true' to the target JDBC url, otherwise the {} rows per batch"
					+ " are sent as single inserts", this.properties.getBatchSize());
		}
	}

}
