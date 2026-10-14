package com.example.migration.sync;

import java.time.LocalDate;

import com.example.migration.config.MigrationProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The source table name is derived from the target table name and the run date. */
class SyncTableFactoryTest {

	private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 14);

	private final MigrationProperties properties = new MigrationProperties();

	private final SyncTableFactory factory = new SyncTableFactory(null, null, null, this.properties);

	@Test
	void derivesTheSourceTableFromTheTargetTableAndTheRunDate() {
		assertThat(this.factory.sourceTable("order", RUN_DATE)).isEqualTo("order20261014");
		assertThat(this.factory.sourceTable("ORDER", RUN_DATE)).isEqualTo("ORDER20261014");
		assertThat(this.factory.sourceTable("order", RUN_DATE.minusDays(1))).isEqualTo("order20261013");
	}

	@Test
	void supportsACustomPatternAndDateFormat() {
		this.properties.setSourceTablePattern("{table}_{date}");
		this.properties.setDatePattern("yyyy-MM-dd");

		assertThat(this.factory.sourceTable("order", RUN_DATE)).isEqualTo("order_2026-10-14");
	}

	@Test
	void aPatternWithoutTheTablePlaceholderIsRejected() {
		this.properties.setSourceTablePattern("{date}");

		assertThatThrownBy(() -> this.factory.sourceTable("order", RUN_DATE)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("source-table-pattern");
	}

}
