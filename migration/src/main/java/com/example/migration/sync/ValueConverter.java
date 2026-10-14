package com.example.migration.sync;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/**
 * Turns the JDBC values of Oracle into values MySQL accepts: CLOB to String, BLOB to byte[],
 * DATE/TIMESTAMP to {@link LocalDateTime} and NUMBER stays a {@link BigDecimal}.
 */
@Component
public class ValueConverter {

	public Object convert(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Clob clob) {
			return readClob(clob);
		}
		if (value instanceof Blob blob) {
			return readBlob(blob);
		}
		if (value instanceof SQLXML xml) {
			return readSqlXml(xml);
		}
		if (value instanceof java.sql.Timestamp timestamp) {
			return timestamp.toLocalDateTime();
		}
		if (value instanceof java.sql.Date date) {
			return date.toLocalDate().atStartOfDay();
		}
		if (value instanceof java.sql.Time time) {
			return time.toLocalTime();
		}
		if (value instanceof java.util.Date date) {
			return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
		}
		if (value instanceof OffsetDateTime offsetDateTime) {
			return offsetDateTime.toLocalDateTime();
		}
		if (value instanceof BigInteger bigInteger) {
			return new BigDecimal(bigInteger);
		}
		if (value instanceof Character character) {
			return String.valueOf(character);
		}
		return value;
	}

	private static String readClob(Clob clob) {
		try {
			return clob.getSubString(1, (int) Math.min(clob.length(), Integer.MAX_VALUE));
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not read CLOB value", ex);
		}
	}

	private static byte[] readBlob(Blob blob) {
		try {
			return blob.getBytes(1, (int) Math.min(blob.length(), Integer.MAX_VALUE));
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not read BLOB value", ex);
		}
	}

	private static String readSqlXml(SQLXML xml) {
		try {
			return xml.getString();
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not read XML value", ex);
		}
	}

}
