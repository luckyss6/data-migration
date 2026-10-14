package com.example.migration;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.example.migration.config.DataSourceConfig;
import com.example.migration.support.TestSchema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** The manual trigger endpoint, which runs exactly the same job as the cron schedule. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SyncApiTest {

	private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 14);

	private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 1, 8, 0);

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@TestConfiguration
	static class FixedClock {

		@Bean
		@Primary
		Clock migrationTestClock() {
			return Clock.fixed(Instant.parse("2026-10-14T02:30:00Z"), ZoneId.of("Asia/Shanghai"));
		}
	}

	@Autowired
	private Environment environment;

	@Autowired
	@Qualifier(DataSourceConfig.SOURCE_JDBC_TEMPLATE)
	private JdbcTemplate source;

	@Autowired
	@Qualifier(DataSourceConfig.TARGET_JDBC_TEMPLATE)
	private JdbcTemplate target;

	@BeforeEach
	void reset() {
		TestSchema.createSyncTableConfig(this.source);
		this.target.execute("DROP TABLE IF EXISTS " + targetTable());
		this.source.execute("DELETE FROM sync_table_config");
		this.source.update("INSERT INTO sync_table_config (source_table, remark) VALUES ('t_order', 'api test')");
		String sourceTable = sourceTable();
		this.source.execute("DROP TABLE IF EXISTS " + sourceTable);
		this.source.execute("CREATE TABLE " + sourceTable + " (ID NUMBER(10) NOT NULL, ORDER_NO VARCHAR2(60) NOT NULL, "
				+ "AMOUNT NUMBER(12,2), REMARK CLOB, UPDATE_TIME TIMESTAMP(3), PRIMARY KEY (ID))");
		for (int id = 1; id <= 3; id++) {
			this.source.update("INSERT INTO " + sourceTable
					+ " (ID, ORDER_NO, AMOUNT, REMARK, UPDATE_TIME) VALUES (?, ?, ?, ?, ?)", (long) id, "ORD-" + id,
					new BigDecimal("10.50"), "remark", Timestamp.valueOf(T1));
		}
	}

	@Test
	void postSyncRunTriggersTheJobAndReturnsTheResultPerTable() throws Exception {
		HttpResponse<String> response = post("/sync/run");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"SUCCESS\"")
				.contains("\"table\":\"" + targetTable() + "\"")
				.contains("\"written\":3");
		// the run really copied the rows and created the missing target table
		assertThat(this.target.queryForObject("SELECT COUNT(*) FROM " + targetTable(), Long.class)).isEqualTo(3);
	}

	@Test
	void postSyncRunWithAnEmptyConfigReturnsNoTableResults() throws Exception {
		this.source.execute("DELETE FROM sync_table_config");

		HttpResponse<String> response = post("/sync/run");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"status\":\"SUCCESS\",\"tables\":[]}");
	}

	@Test
	void postSyncRunReportsFailingTables() throws Exception {
		// required_col is not part of the source table, so every insert into the target fails
		this.target.execute("DROP TABLE IF EXISTS " + targetTable());
		this.target.execute("CREATE TABLE " + targetTable()
				+ " (id BIGINT NOT NULL PRIMARY KEY, order_no VARCHAR(60) NOT NULL, "
				+ "amount DECIMAL(12,2) NULL, remark LONGTEXT NULL, update_time DATETIME(3) NULL, "
				+ "required_col VARCHAR(10) NOT NULL)");

		HttpResponse<String> response = post("/sync/run");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"FAILED\"", "\"table\":\"" + targetTable() + "\"",
				"\"written\":0");
	}

	private HttpResponse<String> post(String path) throws Exception {
		return this.httpClient.send(
				HttpRequest.newBuilder(URI.create(baseUrl() + path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private String baseUrl() {
		return "http://localhost:" + this.environment.getProperty("local.server.port");
	}

	/** Table as it exists in the source database. */
	private static String sourceTable() {
		return "T_ORDER";
	}

	/** Table the engine writes to, source table name plus the run date. */
	private static String targetTable() {
		return "t_order" + DateTimeFormatter.BASIC_ISO_DATE.format(RUN_DATE);
	}

}
