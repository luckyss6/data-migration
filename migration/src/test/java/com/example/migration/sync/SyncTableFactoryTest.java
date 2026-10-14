package com.example.migration.sync;

import java.time.LocalDate;

import com.example.migration.config.MigrationProperties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The target table name is derived from the source table name and the run date. */
class SyncTableFactoryTest {

	private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 14);

	private final MigrationProperties properties = new MigrationProperties();

	private final SyncTableFactory factory = new SyncTableFactory(null, null, null, this.properties);

	@Test
	void derivesTheTargetTableFromTheSourceTableAndTheRunDate() {
		assertThat(this.factory.targetTable("order", RUN_DATE)).isEqualTo("order20261014");
		assertThat(this.factory.targetTable("ORDER", RUN_DATE)).isEqualTo("ORDER20261014");
		assertThat(this.factory.targetTable("order", RUN_DATE.minusDays(1))).isEqualTo("order20261013");
	}

	@Test
	void aSchemaQualifiedSourceTableDoesNotRepeatItsSchemaInTheTarget() {
		assertThat(this.factory.targetTable("APP.ORDER", RUN_DATE)).isEqualTo("ORDER20261014");
	}

	@Test
	void supportsACustomPatternAndDateFormat() {
		this.properties.setTargetTablePattern("{table}_{date}");
		this.properties.setDatePattern("yyyy-MM-dd");

		assertThat(this.factory.targetTable("order", RUN_DATE)).isEqualTo("order_2026-10-14");
	}

	@Test
	void aPatternWithoutTheTablePlaceholderIsRejected() {
		this.properties.setTargetTablePattern("{date}");

		assertThatThrownBy(() -> this.factory.targetTable("order", RUN_DATE)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("target-table-pattern");
	}

}
