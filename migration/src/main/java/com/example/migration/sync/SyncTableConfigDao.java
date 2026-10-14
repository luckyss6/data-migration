package com.example.migration.sync;

import java.util.List;

import com.example.migration.config.DataSourceConfig;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code sync_table_config} table of the <em>source</em> database: one row per table that has to
 * be migrated. The table is owned by the source database and created outside of this application, see
 * {@code db/sync_table_config.sql}. Only tables that exist in the source are migrated, the matching
 * target table is derived from the configured name and the run date, see
 * {@code migration.target-table-pattern}.
 */
@Component
public class SyncTableConfigDao {

	private final JdbcTemplate sourceJdbcTemplate;

	public SyncTableConfigDao(@Qualifier(DataSourceConfig.SOURCE_JDBC_TEMPLATE) JdbcTemplate sourceJdbcTemplate) {
		this.sourceJdbcTemplate = sourceJdbcTemplate;
	}

	/** Enabled source tables, in configuration order. Read on every run. */
	public List<String> findEnabledSourceTables() {
		return this.sourceJdbcTemplate.queryForList(
				"SELECT source_table FROM sync_table_config WHERE enabled = 1 ORDER BY id", String.class);
	}

}
