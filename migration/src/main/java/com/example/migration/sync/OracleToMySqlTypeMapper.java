package com.example.migration.sync;

import java.sql.Types;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Oracle to MySQL type mapping used when a target table is created from a source snapshot.
 */
@Component
public class OracleToMySqlTypeMapper {

	private static final int MYSQL_MAX_VARCHAR = 8000;

	public String toMySqlType(SourceColumn column) {
		String typeName = column.typeName() == null ? "" : column.typeName().toUpperCase(Locale.ROOT);
		String mapped = switch (baseName(typeName)) {
			case "NUMBER", "NUMERIC", "DECIMAL", "DEC" -> numeric(column.size(), column.decimalDigits());
			case "INTEGER", "INT", "PLS_INTEGER" -> "INT";
			case "SMALLINT" -> "SMALLINT";
			case "TINYINT" -> "TINYINT";
			case "BIGINT" -> "BIGINT";
			case "FLOAT", "BINARY_FLOAT", "REAL" -> "FLOAT";
			case "DOUBLE", "BINARY_DOUBLE", "DOUBLE PRECISION" -> "DOUBLE";
			case "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "CHARACTER VARYING", "LONGVARCHAR" ->
				varchar(column.size());
			case "CHAR", "NCHAR", "CHARACTER", "BPCHAR" -> "CHAR(" + clamp(column.size(), 1, 255, 1) + ")";
			case "CLOB", "NCLOB", "LONG", "CHARACTER LARGE OBJECT", "TEXT", "TINYTEXT", "MEDIUMTEXT" -> "LONGTEXT";
			case "BLOB", "BINARY LARGE OBJECT", "LONG RAW", "MEDIUMBLOB" -> "LONGBLOB";
			case "RAW", "BINARY VARYING", "VARBINARY", "BINARY" -> varbinary(column.size());
			case "DATE" -> "DATETIME";
			case "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE", "DATETIME" ->
				"DATETIME(" + fractionalSeconds(typeName, column.decimalDigits()) + ")";
			case "TIME", "TIME WITH TIME ZONE" -> "TIME";
			case "INTERVAL DAY TO SECOND", "INTERVAL YEAR TO MONTH" -> "VARCHAR(64)";
			case "XMLTYPE", "XML" -> "LONGTEXT";
			case "BOOLEAN", "BOOL", "BIT" -> "TINYINT(1)";
			default -> null;
		};
		if (mapped != null) {
			return mapped;
		}
		String fallback = byJdbcType(column);
		if (fallback != null) {
			return fallback;
		}
		throw new IllegalStateException("No MySQL type known for source type '" + column.typeName() + "' (jdbc type "
				+ column.jdbcType() + ") of column " + column.name() + ", create the target table yourself");
	}

	private static String byJdbcType(SourceColumn column) {
		return switch (column.jdbcType()) {
			case Types.NUMERIC, Types.DECIMAL -> numeric(column.size(), column.decimalDigits());
			case Types.BIGINT -> "BIGINT";
			case Types.INTEGER -> "INT";
			case Types.SMALLINT -> "SMALLINT";
			case Types.TINYINT -> "TINYINT";
			case Types.FLOAT, Types.REAL -> "FLOAT";
			case Types.DOUBLE -> "DOUBLE";
			case Types.VARCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR -> varchar(column.size());
			case Types.CHAR, Types.NCHAR -> "CHAR(" + clamp(column.size(), 1, 255, 1) + ")";
			case Types.CLOB, Types.NCLOB -> "LONGTEXT";
			case Types.BLOB -> "LONGBLOB";
			case Types.VARBINARY, Types.BINARY, Types.LONGVARBINARY -> varbinary(column.size());
			case Types.DATE, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "DATETIME(3)";
			case Types.TIME, Types.TIME_WITH_TIMEZONE -> "TIME";
			case Types.BOOLEAN, Types.BIT -> "TINYINT(1)";
			default -> null;
		};
	}

	private static String numeric(int precision, int scale) {
		if (precision <= 0) {
			// NUMBER without precision: keep room for both integer and decimal business values.
			return "DECIMAL(38,10)";
		}
		if (scale > 0) {
			return "DECIMAL(" + clamp(precision, 1, 65, 38) + "," + clamp(scale, 1, 30, 10) + ")";
		}
		if (precision <= 4) {
			return "SMALLINT";
		}
		if (precision <= 9) {
			return "INT";
		}
		if (precision <= 18) {
			return "BIGINT";
		}
		return "DECIMAL(" + clamp(precision, 19, 65, 38) + ",0)";
	}

	private static String varchar(int size) {
		if (size <= 0) {
			return "VARCHAR(255)";
		}
		return size <= MYSQL_MAX_VARCHAR ? "VARCHAR(" + size + ")" : "LONGTEXT";
	}

	private static String varbinary(int size) {
		if (size <= 0) {
			return "VARBINARY(255)";
		}
		return size <= 65535 ? "VARBINARY(" + size + ")" : "LONGBLOB";
	}

	private static int fractionalSeconds(String typeName, int decimalDigits) {
		int fromTypeName = 0;
		int open = typeName.indexOf('(');
		int close = typeName.indexOf(')', open + 1);
		if (open >= 0 && close > open) {
			try {
				fromTypeName = Integer.parseInt(typeName.substring(open + 1, close).trim());
			}
			catch (NumberFormatException ex) {
				fromTypeName = 0;
			}
		}
		return clamp(fromTypeName > 0 ? fromTypeName : decimalDigits, 0, 6, 3);
	}

	private static int clamp(int value, int min, int max, int fallback) {
		return value < min ? fallback : Math.min(value, max);
	}

	private static String baseName(String typeName) {
		int paren = typeName.indexOf('(');
		return (paren >= 0 ? typeName.substring(0, paren) : typeName).trim().toUpperCase(Locale.ROOT);
	}

}
