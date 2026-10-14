package com.example.migration.sync;

import java.util.List;

import com.example.migration.config.DataSourceConfig;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code sync_table_config} table of the target database: one row per target table that has to be
 * filled. The source table of a row is derived from the target table name and the run date, see
 * {@code migration.source-table-pattern}.
 */
@Component
public class SyncTableConfigDao {

	private final JdbcTemplate targetJdbcTemplate;

	public SyncTableConfigDao(@Qualifier(DataSourceConfig.TARGET_JDBC_TEMPLATE) JdbcTemplate targetJdbcTemplate) {
		this.targetJdbcTemplate = targetJdbcTemplate;
		createTableIfMissing();
	}

	/** Enabled target tables, in configuration order. Read on every run. */
	public List<String> findEnabledTargetTables() {
		return this.targetJdbcTemplate.queryForList(
				"SELECT target_table FROM sync_table_config WHERE enabled = 1 ORDER BY id", String.class);
	}

	private void createTableIfMissing() {
		this.targetJdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sync_table_config ("
				+ "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, target_table VARCHAR(100) NOT NULL, "
				+ "enabled TINYINT NOT NULL DEFAULT 1, remark VARCHAR(500) NULL, updated_at TIMESTAMP NULL, "
				+ "UNIQUE (target_table))");
	}

}
