package com.example.migration.scheduler;

import com.example.migration.sync.TableSyncService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Starts the synchronisation, by default every day at 02:30. */
@Component
public class SyncScheduler {

	private static final Logger logger = LoggerFactory.getLogger(SyncScheduler.class);

	private final TableSyncService syncService;

	public SyncScheduler(TableSyncService syncService) {
		this.syncService = syncService;
	}

	@Scheduled(cron = "${migration.cron}")
	public void scheduledSync() {
		logger.info("Scheduled synchronisation started");
		try {
			this.syncService.runAll("schedule");
		}
		catch (IllegalStateException ex) {
			logger.warn("Skipped the scheduled run: {}", ex.getMessage());
		}
	}

}
