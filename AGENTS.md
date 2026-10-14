# Repository Guidelines

## Project Structure & Module Organization

- `migration/` — the only Maven module. Application code lives in
  `src/main/java/com/example/migration/`, split by layer: `config/` (properties, data sources),
  `sync/` (migration engine), `scheduler/`, `api/`, `runner/`.
- `migration/src/test/java/...` mirrors the main packages; `support/TestSchema.java` holds shared fixtures.
- Configuration: `src/main/resources/application.yaml` and `src/test/resources/application-test.yaml`
  (H2 Oracle/MySQL compatibility modes).
- `migration/db/sync_table_config.sql` — DDL for the Oracle-side config table a DBA creates.
- `docker-compose.yml`, `db-test/*.sql`, `db-test/test-report.md` — local Oracle XE + MySQL 8.4 pair,
  manual end-to-end fixtures, and results.
- `README.md` at the repo root is the main documentation (Chinese); code comments and Javadoc are English.

## Build, Test, and Development Commands

Run from `migration/` with JDK 17 and the bundled wrapper (`./mvnw`, or `mvnw.cmd` on Windows):

- `./mvnw test` — full H2-backed test suite, no live database needed.
- `./mvnw spring-boot:run` — start the app; the cron schedule drives the job.
- `./mvnw clean package` — build `target/migration-0.0.1-SNAPSHOT.jar`.
- `docker compose up -d` — start the local Oracle/MySQL pair, then `curl -X POST
  http://localhost:8080/sync/run`; `docker compose down -v` resets it.

## Coding Style & Naming Conventions

- Java 17, tab indentation, opening brace on the same line.
- Import groups: `java.*`/`javax.*`, then third-party and Spring, with `static` imports last.
- PascalCase classes, camelCase methods and fields. Sync types are named `Sync*` (`SyncTableFactory`,
  `SqlBuilder`).
- No formatter or linter is configured — match the surrounding file.

## Testing Guidelines

- JUnit 5 + AssertJ. Integration tests use `@SpringBootTest` with `@ActiveProfiles("test")`, H2
  `MODE=Oracle` as source and `MODE=MySQL` as target.
- One test class per production class in the matching package (`sync/SqlBuilderTest.java`).
- Name tests as behavior sentences without a `test` prefix:
  `void everyRunCopiesTheWholeTableAgainWithoutCreatingDuplicates()`.
- Pin dates with constants (`RUN_DATE = LocalDate.of(2026, 10, 14)`) rather than the system clock.
- Add a case for every new type mapping, SQL shape, or `/sync/run` response field.

## Commit & Pull Request Guidelines

- Commit subjects are imperative, sentence case, no type prefix — "Switch migrations to full daily
  table refresh" — followed by a blank line and a body explaining what and why.
- Pull requests state the motivation, list affected config keys or DDL, include `./mvnw test` output,
  and link related issues.
- Update `README.md` for user-visible changes and `db-test/test-report.md` for database-level findings.

## Security & Configuration Tips

- `application.yaml` reads `MIGRATION_SOURCE_URL/_USERNAME/_PASSWORD` and `MIGRATION_TARGET_*`; the
  built-in defaults are for the local Docker databases only — never commit real credentials.
- Keep `rewriteBatchedStatements=true` on the target URL, and keep `migration.threads` at or below
  both `pool-size` values.
- The app only reads `sync_table_config`; run `db/sync_table_config.sql` (or a DBA change) first.
