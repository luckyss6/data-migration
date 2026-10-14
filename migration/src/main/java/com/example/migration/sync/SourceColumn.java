package com.example.migration.sync;

/**
 * One column of a source snapshot, as reported by the JDBC metadata.
 *
 * @param name column name in the source table
 * @param typeName source type name, e.g. {@code NUMBER} or {@code VARCHAR2}
 * @param jdbcType {@link java.sql.Types} constant
 * @param size precision for numeric types, length for character types
 * @param decimalDigits digits after the decimal point
 * @param nullable whether the source column allows {@code null}
 */
public record SourceColumn(String name, String typeName, int jdbcType, int size, int decimalDigits, boolean nullable) {

}
