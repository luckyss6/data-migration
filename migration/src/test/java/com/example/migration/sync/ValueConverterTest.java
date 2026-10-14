package com.example.migration.sync;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Values Oracle hands out have to be bindable to MySQL, this is the conversion table. */
class ValueConverterTest {

	private final ValueConverter converter = new ValueConverter();

	@Test
	void keepsNullsAndSimpleValues() {
		assertThat(this.converter.convert(null)).isNull();
		assertThat(this.converter.convert("text")).isEqualTo("text");
		assertThat(this.converter.convert(42L)).isEqualTo(42L);
		assertThat(this.converter.convert(new BigDecimal("12.34"))).isEqualTo(new BigDecimal("12.34"));
		assertThat(this.converter.convert(true)).isEqualTo(true);
		assertThat(this.converter.convert('x')).isEqualTo("x");
	}

	@Test
	void convertsLobs() throws Exception {
		assertThat(this.converter.convert(new SerialClob("中文 clob".toCharArray()))).isEqualTo("中文 clob");
		assertThat(this.converter.convert(new SerialBlob(new byte[] { 1, 2, 3 }))).isEqualTo(new byte[] { 1, 2, 3 });
	}

	@Test
	void convertsOracleDateAndTimestampToLocalDateTime() {
		LocalDateTime expected = LocalDateTime.of(2026, 10, 14, 1, 2, 3);

		assertThat(this.converter.convert(Timestamp.valueOf(expected))).isEqualTo(expected);
		// Oracle DATE carries a time component and is reported as a timestamp
		assertThat(this.converter.convert(Timestamp.valueOf(LocalDateTime.of(2026, 10, 14, 1, 2, 3, 456_000_000))))
				.isEqualTo(LocalDateTime.of(2026, 10, 14, 1, 2, 3, 456_000_000));
		// a real MySQL DATE (midnight) stays midnight
		assertThat(this.converter.convert(java.sql.Date.valueOf(LocalDate.of(2026, 10, 14))))
				.isEqualTo(LocalDateTime.of(2026, 10, 14, 0, 0));
		assertThat(this.converter.convert(Time.valueOf(LocalTime.of(1, 2, 3)))).isEqualTo(LocalTime.of(1, 2, 3));
	}

	@Test
	void convertsBigIntegerNumbersToDecimal() {
		assertThat(this.converter.convert(BigInteger.valueOf(9007199254740993L)))
				.isEqualTo(new BigDecimal("9007199254740993"));
	}

}
