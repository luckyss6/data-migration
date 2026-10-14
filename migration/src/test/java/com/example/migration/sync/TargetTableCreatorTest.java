package com.example.migration.sync;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@code CREATE TABLE} statement a missing target table is created with. */
class TargetTableCreatorTest {

	private final TargetTableCreator creator = new TargetTableCreator(null, new OracleToMySqlTypeMapper());

	@Test
	void buildsMySqlDdlFromTheSnapshotMetadata() {
		String ddl = this.creator.createTableSql("order", columns(), List.of("ID"),
				"ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

		assertThat(ddl).startsWith("CREATE TABLE order (\n");
		assertThat(ddl).contains("id INT NOT NULL");
		assertThat(ddl).contains("order_no VARCHAR(60) NOT NULL");
		assertThat(ddl).contains("amount DECIMAL(12,2)");
		assertThat(ddl).contains("remark LONGTEXT");
		assertThat(ddl).contains("update_time DATETIME(3)");
		assertThat(ddl).contains("PRIMARY KEY (id)");
		assertThat(ddl).endsWith("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
	}

	@Test
	void createsACompositePrimaryKeyAndSkipsTheTableOptionsWhenTheyAreEmpty() {
		String ddl = this.creator.createTableSql("link", columns(), List.of("ID", "ORDER_NO"), "");

		assertThat(ddl).contains("PRIMARY KEY (id, order_no)");
		assertThat(ddl).doesNotContain("ENGINE=");
	}

	@Test
	void keepsANullableKeyColumnNotNull() {
		// id is nullable in the snapshot metadata but part of the key, the copy needs it not null
		String ddl = this.creator.createTableSql("order", columns(), List.of("ID"), null);

		assertThat(ddl).contains("id INT NOT NULL");
	}

	private static List<SourceColumn> columns() {
		return List.of(new SourceColumn("ID", "NUMBER", Types.NUMERIC, 9, 0, true),
				new SourceColumn("ORDER_NO", "VARCHAR2", Types.VARCHAR, 60, 0, false),
				new SourceColumn("AMOUNT", "NUMBER", Types.NUMERIC, 12, 2, true),
				new SourceColumn("REMARK", "CLOB", Types.CLOB, 4000, 0, true),
				new SourceColumn("UPDATE_TIME", "TIMESTAMP(3)", Types.TIMESTAMP, 23, 3, true));
	}

}
