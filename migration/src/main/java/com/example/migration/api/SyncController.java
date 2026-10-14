package com.example.migration.api;

import java.util.List;

import com.example.migration.sync.SyncResult;
import com.example.migration.sync.TableSyncService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for manual runs, mainly useful while testing:
 *
 * <pre>
 * curl -X POST http://localhost:8080/sync/run      # same work the cron trigger does
 * </pre>
 */
@RestController
@RequestMapping("/sync")
public class SyncController {

	private static final Logger logger = LoggerFactory.getLogger(SyncController.class);

	private final TableSyncService syncService;

	public SyncController(TableSyncService syncService) {
		this.syncService = syncService;
	}

	/** Runs the synchronisation once and returns the result of every table. */
	@PostMapping("/run")
	public ResponseEntity<SyncRunResponse> run() {
		if (this.syncService.isRunning()) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(new SyncRunResponse("RUNNING", List.of()));
		}
		List<SyncResult> results;
		try {
			results = this.syncService.runAll("api");
		}
		catch (IllegalStateException ex) {
			logger.warn("Manual run rejected: {}", ex.getMessage());
			return ResponseEntity.status(HttpStatus.CONFLICT).body(new SyncRunResponse("RUNNING", List.of()));
		}
		String status = results.stream().allMatch(SyncResult::success) ? "SUCCESS" : "FAILED";
		logger.info("Manual run finished with {} ({} table(s))", status, results.size());
		return ResponseEntity.ok(new SyncRunResponse(status, results));
	}

	/**
	 * @param status {@code SUCCESS} when every table was copied, otherwise {@code FAILED} (or
	 * {@code RUNNING} when the request was rejected because a run is already in progress)
	 * @param tables result per table
	 */
	public record SyncRunResponse(String status, List<SyncResult> tables) {
	}

}
