# Oracle → MySQL 定时数据迁移

Spring Boot 定时任务，每天把 Oracle 里的快照表搬进 MySQL。核心是 `@Scheduled` + `JdbcTemplate`，
没有 Spring Batch / XXL-Job 等额外组件。

流程：**读库里的配置表 → 拿到目标表 → 按目标表名去源库找快照 → 有就建目标表 → 再搬数据**。

## 工作原理

1. 读目标库的配置表 `sync_table_config`，得到需要同步的**目标表**（如 `order`）。
2. 推导源表名：目标表名 + 日期，`order` → `order20261014`（`migration.source-table-pattern`，
   可用 `date-offset-days: -1` 取昨天的快照）。
3. 去源库找这张快照：**没有就跳过这张表**（打 WARN），不影响其他表。
4. 有快照：**目标表不存在时按源表结构自动建表**（Oracle 类型 → MySQL 类型，见下表）；
   已存在则完全不动它。
5. 复制的列 = 源表与目标表的交集（按名字忽略大小写匹配）。源表多出来的列忽略并告警，
   目标表多出来的列保留默认值。
6. 搬数据：**一张表一个线程**，最多 `migration.threads` 张表同时跑。水位 = **目标表自身的 `MAX(时间戳列)`**，
   只搬比它新的行（再减去 `watermark-overlap`）；按 `(时间戳列, 游标列)` keyset 分页，每页一条
   `INSERT ... ON DUPLICATE KEY UPDATE` 批量写入，重复执行不产生重复数据。
7. 单条写失败 → 打日志跳过，同页其他行正常提交；整页失败或异常 → 该表失败（不影响其他表），
   水位自然停在上次位置，下次重试。

## 目录

```
src/main/java/com/example/migration/
  config/MigrationProperties.java     全局配置
  config/DataSourceConfig.java        Oracle / MySQL 两个数据源
  sync/SyncTableConfigDao.java        sync_table_config 建表与读取
  sync/SyncTableFactory.java          推导源表名、找快照、建计划（列交集）
  sync/TargetTableCreator.java        按源表结构建目标表（含水位列索引）
  sync/OracleToMySqlTypeMapper.java   Oracle → MySQL 类型映射
  sync/SyncTable.java                 解析后的同步计划
  sync/SqlBuilder.java                分页查询语句 + upsert 语句
  sync/TableSyncService.java          同步主流程
  sync/ValueConverter.java            CLOB/BLOB/日期类型转换
  scheduler/SyncScheduler.java        @Scheduled 触发
  runner/SyncStartupRunner.java       启动时打印配置，可选立即跑一次
```

## 配置表（在目标库里）

`sync_table_config` 由程序在目标库自动创建，一行一张目标表，每次运行前读取一次：

```sql
INSERT INTO sync_table_config (target_table) VALUES ('order');
```

| 列 | 说明 |
| --- | --- |
| `target_table` | 要填充的目标表，唯一，如 `order` |
| `enabled` | 置 0 暂停该表 |
| `remark` | 备注 |

源表名不用配，由 `target_table` + 运行日期推导。

## 全局配置

```yaml
migration:
  cron: "0 30 2 * * ?"        # 每天 02:30
  threads: 4                  # 同时同步几张表（一张表一个线程），不要超过连接池大小
  batch-size: 2000            # 每页读取行数 = 每个 JDBC 批的行数
  fetch-size: 1000            # JDBC fetch size
  watermark-overlap: 1m       # 每次回退重读的时间
  source-table-pattern: "{table}{date}"   # {table}=目标表名，{date}=运行日期
  date-pattern: yyyyMMdd
  date-offset-days: 0         # -1 表示取昨天的快照
  timestamp-column: UPDATE_TIME   # 水位列，源表和目标表都要有
  id-column: ID                   # 分页游标列，留空则用源表单列主键
  sync-mode: INCREMENTAL          # 或 FULL（每次整表重读快照）
  table-options: "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"   # 建表语句后缀
  source:
    url: jdbc:oracle:thin:@//host:1521/ORCL
    username: source_user
    password: xxx
  target:
    url: jdbc:mysql://host:3306/target_db?rewriteBatchedStatements=true&characterEncoding=utf8
    username: target_user
    password: xxx
```

连接串和口令建议用环境变量注入（配置里已写成 `${MIGRATION_SOURCE_URL:...}`）。

## 自动建表的类型映射

| Oracle | MySQL |
| --- | --- |
| `NUMBER(p,s)` | `DECIMAL(p,s)` |
| `NUMBER(p)`（p≤4 / ≤9 / ≤18） | `SMALLINT` / `INT` / `BIGINT` |
| `NUMBER`（无精度） | `DECIMAL(38,10)` |
| `VARCHAR2(n)` / `NVARCHAR2(n)` | `VARCHAR(n)`，超过 8000 用 `LONGTEXT` |
| `CHAR(n)` / `NCHAR(n)` | `CHAR(n)` |
| `CLOB` / `NCLOB` / `XMLTYPE` | `LONGTEXT` |
| `BLOB` / `LONG RAW` | `LONGBLOB` |
| `RAW(n)` | `VARBINARY(n)` |
| `DATE` | `DATETIME` |
| `TIMESTAMP(n)` | `DATETIME(n)`，默认 3，最多 6 |
| `FLOAT` / `BINARY_FLOAT` / `BINARY_DOUBLE` | `FLOAT` / `DOUBLE` |

源表主键会建成目标表主键，水位列自动建索引。遇到没映射过的类型，该表会被跳过并打 ERROR 日志（可考虑手动建表）。

## 性能调优

| 参数 | 说明 |
| --- | --- |
| `threads` | 并发同步的表数，默认 4。受连接池限制：`source.pool-size` / `target.pool-size` 要 ≥ `threads`，否则会排队等连接（启动日志会告警）。表少于线程数时按表数开线程。 |
| `batch-size` | 每页从 Oracle 读的行数，同时也是写入 MySQL 的一个 JDBC 批的行数。默认 2000，可按单行大小往上调（注意 MySQL `max_allowed_packet`）。 |
| `fetch-size` | Oracle 侧 JDBC fetch size，默认 1000，减少网络往返。 |
| `rewriteBatchedStatements=true` | **必加**在目标 JDBC url 上，否则这批语句会被逐条发送；启动时会检查并告警。 |

单表数据量很大时，把 `batch-size` 调到 5000 左右；表很多时优先加 `threads`。

## 运行

```bash
mvn spring-boot:run                                        # 常驻，按 cron 触发
mvn spring-boot:run -Dspring-boot.run.arguments=--sync-now  # 立刻跑一次
java -jar target/migration-0.0.1-SNAPSHOT.jar --migration.run-on-startup=true
```

启动日志会打印源表名规则和 `sync_table_config` 里的目标表。目标表可以完全不存在，会被自动创建；
自己维护目标表的话，记得主键/唯一键建在游标列上，否则 upsert 无法去重。

## 手动触发（测试用接口）

服务默认监听 8080（可用 `server.port` 改），跑的是和 cron 完全相同的那次任务：

```bash
curl -X POST http://localhost:8080/sync/run
# {"status":"SUCCESS","tables":[{"table":"order","status":"SUCCESS","written":1201,"skipped":0,
#   "watermark":"2026-10-14T08:00:00","durationMs":1843}]}
```

`/sync/run` 是同步的：请求会一直等到这次任务跑完再返回每张表的结果。如果正好有一轮任务在跑，
接口返回 `409` 和 `{"status":"RUNNING"}`，不会并发跑两遍。

## 测试

`mvn test`（39 个用例）用 H2 的 Oracle 兼容模式当源库、MySQL 兼容模式当目标库跑整条链路。

| 测试类 | 覆盖 |
| --- | --- |
| `TableSyncServiceTest` | 多表并发（一表一线程）、自动建表并搬全量（1201 行分 3 页）、下次只搬新增、重复搬运不重复、已存在的目标表不被重建（多出列保留默认值、源表多出列不搬）、坏数据只跳过、整表失败不影响其他表、config 里对不上快照的行被跳过、空配置表什么都不做、CLOB 中文值搬对、无时间戳的行不进增量 |
| `FullModeTest` | `sync-mode=FULL`：快照没有时间戳列时每次整表重读，且不产生重复行，改动的行会被更新 |
| `OverlapWindowTest` | `watermark-overlap` 回退重读窗口：重读尾部不重复，上一轮运行期间新写入的行下一轮会被捞到 |
| `SyncApiTest` | `POST /sync/run` 真起 HTTP 触发并返回每表结果、数据确实落库；`GET /sync/tables` 返回配置表；空配置返回空结果；有表失败时 `status=FAILED` |
| `SyncTableFactoryTest` | 源表名推导（默认/自定义 pattern、日期格式），pattern 缺 `{table}` 直接报错 |
| `OracleToMySqlTypeMapperTest` | Oracle → MySQL 类型映射表，未知类型带列名报错 |
| `TargetTableCreatorTest` | 生成的 `CREATE TABLE`（列、NOT NULL、主键、复合主键、表后缀） |
| `SqlBuilderTest` | 增量/全量分页语句与参数、upsert 语句（含只有键列的表） |
| `ValueConverterTest` | CLOB/BLOB/时间/大数/字符的类型转换 |

## 已知限制

- 时间戳方案捕获不到物理删除；需要同步删除请改用逻辑删除列或触发器等方案。
- 增量模式下每张源表都必须有时间戳列（否则该表被跳过并记 ERROR），没有该列的表请用 `FULL` 模式。
- 被跳过的记录只留在日志里，不会自动重试（水位已经越过它）；补数时把该行时间戳改大，或临时调小目标表的
  `MAX(时间戳列)` 让它重新进入范围。
- 水位取自目标表的 `MAX(时间戳列)`，所以不要往目标表写比源端更新的时间戳。
- 应用与数据库的时钟偏差由 `watermark-overlap` 兜底。
