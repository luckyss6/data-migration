package com.example.migration.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Both databases of the migration: Oracle as source, MySQL as target (primary). */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

	public static final String SOURCE_DATA_SOURCE = "sourceDataSource";

	public static final String TARGET_DATA_SOURCE = "targetDataSource";

	public static final String SOURCE_JDBC_TEMPLATE = "sourceJdbcTemplate";

	public static final String TARGET_JDBC_TEMPLATE = "targetJdbcTemplate";

	@Bean(SOURCE_DATA_SOURCE)
	public DataSource sourceDataSource(MigrationProperties properties) {
		return create(properties.getSource(), "migration-source", true);
	}

	@Bean(TARGET_DATA_SOURCE)
	@Primary
	public DataSource targetDataSource(MigrationProperties properties) {
		return create(properties.getTarget(), "migration-target", false);
	}

	@Bean(SOURCE_JDBC_TEMPLATE)
	public JdbcTemplate sourceJdbcTemplate(@Qualifier(SOURCE_DATA_SOURCE) DataSource dataSource,
			MigrationProperties properties) {
		JdbcTemplate template = new JdbcTemplate(dataSource);
		template.setFetchSize(properties.getFetchSize());
		return template;
	}

	@Bean(TARGET_JDBC_TEMPLATE)
	@Primary
	public JdbcTemplate targetJdbcTemplate(@Qualifier(TARGET_DATA_SOURCE) DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

	/** Transactions always run on the target database (one chunk of rows). */
	@Bean
	@Primary
	public PlatformTransactionManager transactionManager(@Qualifier(TARGET_DATA_SOURCE) DataSource dataSource) {
		return new DataSourceTransactionManager(dataSource);
	}

	private static DataSource create(MigrationProperties.Connection settings, String poolName, boolean readOnly) {
		HikariConfig config = new HikariConfig();
		config.setPoolName(poolName);
		config.setJdbcUrl(settings.getUrl());
		config.setUsername(settings.getUsername());
		config.setPassword(settings.getPassword());
		if (settings.getDriverClassName() != null && !settings.getDriverClassName().isBlank()) {
			config.setDriverClassName(settings.getDriverClassName());
		}
		config.setMaximumPoolSize(settings.getPoolSize());
		config.setReadOnly(readOnly);
		return new HikariDataSource(config);
	}

}
