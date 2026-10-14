package com.example.migration.sync;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OracleToMySqlTypeMapperTest {

	private final OracleToMySqlTypeMapper mapper = new OracleToMySqlTypeMapper();

	@Test
	void mapsOracleTypesToMySql() {
		assertThat(type("NUMBER", Types.NUMERIC, 2, 0)).isEqualTo("SMALLINT");
		assertThat(type("NUMBER", Types.NUMERIC, 9, 0)).isEqualTo("INT");
		assertThat(type("NUMBER", Types.NUMERIC, 10, 0)).isEqualTo("BIGINT");
		assertThat(type("NUMBER", Types.NUMERIC, 15, 0)).isEqualTo("BIGINT");
		assertThat(type("NUMBER", Types.NUMERIC, 19, 0)).isEqualTo("DECIMAL(19,0)");
		assertThat(type("NUMBER", Types.NUMERIC, 12, 2)).isEqualTo("DECIMAL(12,2)");
		assertThat(type("NUMBER", Types.NUMERIC, 0, 0)).isEqualTo("DECIMAL(38,10)");
		assertThat(type("VARCHAR2", Types.VARCHAR, 60, 0)).isEqualTo("VARCHAR(60)");
		assertThat(type("VARCHAR2", Types.VARCHAR, 20000, 0)).isEqualTo("LONGTEXT");
		assertThat(type("NVARCHAR2", Types.NVARCHAR, 100, 0)).isEqualTo("VARCHAR(100)");
		assertThat(type("CHAR", Types.CHAR, 3, 0)).isEqualTo("CHAR(3)");
		assertThat(type("CLOB", Types.CLOB, 4000, 0)).isEqualTo("LONGTEXT");
		assertThat(type("BLOB", Types.BLOB, 4000, 0)).isEqualTo("LONGBLOB");
		assertThat(type("LONG RAW", Types.LONGVARBINARY, 0, 0)).isEqualTo("LONGBLOB");
		assertThat(type("RAW", Types.VARBINARY, 16, 0)).isEqualTo("VARBINARY(16)");
		assertThat(type("DATE", Types.TIMESTAMP, 7, 0)).isEqualTo("DATETIME");
		assertThat(type("TIMESTAMP(3)", Types.TIMESTAMP, 23, 3)).isEqualTo("DATETIME(3)");
		assertThat(type("TIMESTAMP", Types.TIMESTAMP, 11, 6)).isEqualTo("DATETIME(6)");
		assertThat(type("BINARY_DOUBLE", Types.DOUBLE, 8, 0)).isEqualTo("DOUBLE");
		assertThat(type("XMLTYPE", Types.SQLXML, 0, 0)).isEqualTo("LONGTEXT");
		assertThat(type("BOOLEAN", Types.BOOLEAN, 1, 0)).isEqualTo("TINYINT(1)");
	}

	@Test
	void fallsBackToTheJdbcTypeForOtherDatabases() {
		assertThat(type("CHARACTER VARYING", Types.VARCHAR, 50, 0)).isEqualTo("VARCHAR(50)");
		assertThat(type("BINARY VARYING", Types.VARBINARY, 32, 0)).isEqualTo("VARBINARY(32)");
	}

	@Test
	void unknownTypesFailWithTheOffendingColumn() {
		assertThatThrownBy(() -> type("SOME_WEIRD_TYPE", Types.OTHER, 0, 0)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("SOME_COLUMN");
	}

	private String type(String typeName, int jdbcType, int size, int scale) {
		return this.mapper.toMySqlType(new SourceColumn("SOME_COLUMN", typeName, jdbcType, size, scale, true));
	}

}
