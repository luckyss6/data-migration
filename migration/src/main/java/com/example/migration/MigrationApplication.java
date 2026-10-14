package com.example.migration;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MigrationApplication {

	public static void main(String[] args) {
		SpringApplication.run(MigrationApplication.class, args);
	}

	/** Decides which day's snapshot is read, see {@code migration.date-offset-days}. */
	@Bean
	public Clock migrationClock() {
		return Clock.systemDefaultZone();
	}

}
