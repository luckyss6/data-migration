# Oracle → MySQL 定时数据迁移

Spring Boot 定时任务，每天把 Oracle 里配置好的表搬进 MySQL 的按日期命名的快照表。核心是 `@Scheduled` + `JdbcTemplate`，
没有 Spring Batch / XXL-Job 等额外组件。

流程：**读源库的配置表 → 确认源表存在 → 推导目标表名（源表名 + 日期）→ 目标表不存在就按源表结构建 → 清空当天目标表 → 再整表搬数据**。

## 运行环境与技术栈

| 项目 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17** | `pom.xml` 里 `<java.version>17</java.version>`，编译目标就是 17，本机要装 JDK 17+ |
| Maven | 3.10.x | 仓库自带 wrapper（`mvnw` / `mvnw.cmd`，指向 apache-maven 3.10.0），不需要本机预装 Maven |
| Spring Boot | 4.1.1 | 由 `spring-boot-starter-parent` 统一管理版本，下面的依赖大多不用写版本号 |
| 源库 | Oracle（11g 及以上） | 测试环境用 `gvenzl/oracle-xe:21-slim`，见 `docker-compose.yml` |
| 目标库 | MySQL 8.x | 测试环境用 `mysql:8.4`；建表默认 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` |

### pom 依赖

`migration/pom.xml` 里的依赖一览（版本来自 parent 的统一管理，只有驱动显式写了版本）：

| 依赖 | 版本 | scope | 用途 |
| --- | --- | --- | --- |
| `org.springframework.boot:spring-boot-starter-jdbc` | 4.1.1 | compile | `JdbcTemplate` + HikariCP 连接池，迁移主流程全靠它 |
| `org.springframework.boot:spring-boot-starter-webmvc` | 4.1.1 | compile | `POST /sync/run` 手动触发接口 |
| `com.oracle.database.jdbc:ojdbc11` | 21.9.0.0 | runtime | Oracle 源库 JDBC 驱动 |
| `com.mysql:mysql-connector-j` | 8.3.0 | runtime | MySQL 目标库 JDBC 驱动 |
| `org.springframework.boot:spring-boot-starter-test` | 4.1.1 | test | JUnit 5 + AssertJ + MockMvc |
| `com.h2database:h2` | 2.1.214 | test | 测试用源/目标库（Oracle、MySQL 兼容模式） |

两个驱动都是 `runtime`：编译期只靠 JDBC 标准 API，装上驱动就能跑；跑测试只用 H2，不需要真的连 Oracle/MySQL。

构建与运行：

```bash
./mvnw clean package          # Windows 用 mvnw.cmd
java -jar target/migration-0.0.1-SNAPSHOT.jar
```

## 工作原理

1. 读**源库（Oracle）**的配置表 `sync_table_config`，得到需要迁移的**源表**（如 `order`）。
2. 推导目标表名：源表名 + 日期，`order` → `order20261014`（`migration.target-table-pattern`，
   可用 `date-offset-days: -1` 写到昨天的快照表）。
3. 确认这张源表真的存在：**没有就跳过这张表**（打 WARN），不影响其他表——配置表放在源库，
   配的本来就是源头有的表。
4. 源表存在：**目标表不存在时按源表结构自动建表**（Oracle 类型 → MySQL 类型，见下表）；
   已存在则完全不动它。
5. 复制的列 = 源表与目标表的交集（按名字忽略大小写匹配）。源表多出来的列忽略并告警，
   目标表多出来的列保留默认值。
6. 搬数据：**一张表一个线程**，最多 `migration.threads` 张表同时跑。**每次运行都整表全量重搬**，
   没有水位、没有增量：先 `DELETE FROM <当天目标表>` 清掉这一天的旧数据，再按游标列（默认 `ID`）
   顺序分页读取，每页一条 `INSERT ... ON DUPLICATE KEY UPDATE` 批量写入。同一天跑两遍的最终结果
   就是源表当时的内容——源端删掉的行不会残留，也不会重复。
7. 单条写失败 → 打日志跳过，同页其他行正常提交；整页失败或异常 → 该表失败（不影响其他表），
   下次运行再整表重搬一次。

## 目录

```
src/main/java/com/example/migration/
  config/MigrationProperties.java     全局配置
  config/DataSourceConfig.java        Oracle / MySQL 两个数据源
  sync/SyncTableConfigDao.java        读取源库的 sync_table_config（不建表）
  sync/SyncTableFactory.java          推导目标表名、确认源表存在、建计划（列交集）
  sync/TargetTableCreator.java        按源表结构建目标表
  sync/OracleToMySqlTypeMapper.java   Oracle → MySQL 类型映射
  sync/SyncTable.java                 解析后的同步计划
  sync/SqlBuilder.java                分页查询语句 + upsert 语句
  sync/TableSyncService.java          同步主流程
  sync/ValueConverter.java            CLOB/BLOB/日期类型转换
  scheduler/SyncScheduler.java        @Scheduled 触发
  runner/SyncStartupRunner.java       启动时打印配置，可选立即跑一次
```

## 配置表（在源库 Oracle 里）

`sync_table_config` 由 **DBA 在源库提前建好**（程序只读，不会建表），DDL 见 `db/sync_table_config.sql`；
一行一张要迁移的源表，每次运行前读取一次：

```sql
INSERT INTO sync_table_config (source_table) VALUES ('order');
```

| 列 | 说明 |
| --- | --- |
| `source_table` | 要迁移的源表（Oracle），唯一，如 `order` |
| `enabled` | 置 0 暂停该表 |
| `remark` | 备注 |

目标表名不用配，由 `source_table` + 运行日期推导。

> 计划新增 `ddl` 等列：当迁移账号读不到数据字典（拿不到源表的字段/索引）时，用配置表里的
> 建表语句来建目标表。设计见《[方案：用配置表里的 DDL 建目标表](#方案用配置表里的-ddl-建目标表待实现)（待实现）》。

## 全局配置

```yaml
migration:
  cron: "0 30 2 * * ?"        # 每天 02:30
  threads: 4                  # 同时同步几张表（一张表一个线程），不要超过连接池大小
  batch-size: 2000            # 每页读取行数 = 每个 JDBC 批的行数
  fetch-size: 1000            # JDBC fetch size
  target-table-pattern: "{table}{date}"   # {table}=源表名，{date}=运行日期
  date-pattern: yyyyMMdd
  date-offset-days: 0         # -1 表示写到昨天那张快照表
  id-column: ID                   # 分页游标列，留空则用源表单列主键
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

源表主键会建成目标表主键。遇到没映射过的类型，该表会被跳过并打 ERROR 日志（可考虑手动建表）。

## 方案：用配置表里的 DDL 建目标表（待实现）

> **本节只是设计稿，代码尚未实现**，现有行为以上文为准。

### 背景与约束

现在"目标表结构"是靠 JDBC 元数据（`DatabaseMetaData.getColumns` / `getPrimaryKeys`）从源库**推导**出来的。
如果迁移账号只有业务表的 `SELECT` 权限、没有数据字典权限（`ALL_TAB_COLUMNS` / `ALL_CONS_COLUMNS` /
`ALL_INDEXES` 等），这些调用会**返回空或者直接报错**：拿不到字段、精度、可空性，也拿不到主键/唯一键，
于是目标表根本建不出来。

### 核心思路

把"源表结构"从**运行时探测**改成**配置里声明**：

1. DBA 把源表的建表语句（`CREATE TABLE`，即 DDL）填进配置表 `sync_table_config.ddl`；
2. 程序**只解析这段 DDL**，不查数据字典 —— 列、类型、精度、可空性、主键全部来自 DDL；
3. 先校验这段 DDL（语法 + 是否和源表对得上），校验通过才用它在 MySQL 建当天的目标表；
4. 搬运逻辑完全不变：整表分页读 + `INSERT ... ON DUPLICATE KEY UPDATE`，每轮先清空当天目标表。

一句话：**配置表里的 DDL 是目标表结构的唯一来源，数据字典权限不再需要。**

### 配置表改造

`db/sync_table_config.sql` 增加下面几列（程序仍然只读，不建表、不回写）：

```sql
ALTER TABLE sync_table_config ADD (
  ddl         CLOB,           -- 源表的 CREATE TABLE 语句（推荐裁剪成「列 + 主键」最小集合）
  id_column   VARCHAR2(128),  -- 可选：该表的分页游标列，留空用全局 migration.id-column
  key_columns VARCHAR2(500)   -- 可选：手工指定目标表的主键列（DDL 里没写主键时用）
);

COMMENT ON COLUMN sync_table_config.ddl IS '源表建表语句；迁移账号无数据字典权限时，目标表结构以它为准';
```

一行还是一张表，示例：

```sql
INSERT INTO sync_table_config (source_table, ddl) VALUES (
  'order',
  'CREATE TABLE "ORDER" (
     ID          NUMBER(10) NOT NULL,
     ORDER_NO    VARCHAR2(60) NOT NULL,
     AMOUNT      NUMBER(12,2),
     REMARK      CLOB,
     UPDATE_TIME TIMESTAMP(3),
     CONSTRAINT PK_ORDER PRIMARY KEY (ID)
   )');
```

| 列 | 必填 | 说明 |
| --- | --- | --- |
| `source_table` | 是 | 源表名（Oracle），必须和 DDL 里的表名一致（忽略 schema 与引号） |
| `ddl` | 是（新） | 源表建表语句；`DBMS_METADATA.GET_DDL` 导出的完整 DDL 也能喂进来，多余子句会被忽略 |
| `id_column` | 否（新） | 该表的游标列，覆盖全局 `migration.id-column`；留空则用 DDL 里的主键 |
| `key_columns` | 否（新） | 目标表主键列，逗号分隔；只在 DDL 没带主键时用 |
| `enabled` / `remark` / `updated_at` | 否 | 同现在 |

### 校验流程

每一步的失败都只影响**这一张表**，其他表照跑：

| # | 步骤 | 做什么 | 需要权限 | 不通过时 |
| --- | --- | --- | --- | --- |
| 1 | 结构预检（本地，不连库） | 必须是单条 `CREATE TABLE`；DDL 里的表名与 `source_table` 一致；括号配平；至少一列；每个列的类型都能映射（见《自动建表的类型映射》）；主键列必须出现在列清单里 | 无，纯文本解析 | 该表 ERROR 跳过 |
| 2 | 存在性 + 列比对（源库） | `SELECT <DDL 里的列> FROM <源表> WHERE 1=0`，用 `ResultSetMetaData` 取列名/类型/精度/可空性，**不碰数据字典** | 源表 `SELECT` | 报 `ORA-00942` → 表不存在或没权限，跳过；**DDL 有、表里没有**的列 → 该表失败（DDL 过期）；**表里有、DDL 没有**的列 → WARN 并忽略该列（沿用"只搬交集"） |
| 3 | 语法硬校验（可选） | `DBMS_SQL.PARSE(cursor, ddl, DBMS_SQL.NATIVE)`：只解析、不执行，能挡掉 DDL 文本损坏 | `EXECUTE ON DBMS_SQL` | 解析报错 → 该表跳过；没这个授权就跳过本步，降级为 1+2 |
| 4 | 目标端预演（可选） | 把生成的 MySQL DDL 先建成 `<目标表名>__ddlchk` 再 `DROP`；也可以不预演，直接建当天的目标表（失败即该表 FAILED） | 目标库 `CREATE`/`DROP` | 建表失败 → 该表 FAILED，其他表继续 |
| 5 | 可观测 | 每张表的"结构来源（ddl/metadata）+ 校验结论"进日志和 `/sync/run` 返回 | — | 不回写配置表（保持"程序只读配置表"的约束） |

另外在**启动时**把配置表里所有表过一遍第 1 步（可选带第 2 步），一次性列出"缺 `ddl` / DDL 不合法"的表，
别等到凌晨 02:30 才发现。

### DDL 解析规则

**支持**：

- `CREATE [GLOBAL TEMPORARY] TABLE [schema.]name (...)`
- 列定义：`列名 类型[(精度[,小数位])] [DEFAULT …] [NOT NULL]`，类型允许带字符语义，如 `VARCHAR2(60 CHAR)`
- 键定义：表级 `[CONSTRAINT 名] PRIMARY KEY (列, …)` / `UNIQUE (列, …)`；列内联 `PRIMARY KEY` / `NOT NULL`
- 双引号标识符（`"ORDER"`，保留大小写）、`--` 与 `/* */` 注释、多行与多余空白

**忽略（只打 DEBUG 日志）**：`TABLESPACE`、`STORAGE`、`SEGMENT CREATION`、`PCTFREE`、`INITRANS`、
`PARTITION BY …`（只建普通表）、`LOB (…) STORE AS …`、`USING INDEX …`、`ENABLE`、表/列的 `COMMENT`。

**明确不支持（报错跳过，不猜）**：`CREATE TABLE … AS SELECT`、`CREATE TABLE … OF 类型`、虚拟列、
`IDENTITY`/`GENERATED AS IDENTITY` 列（要么 DBA 从 DDL 里去掉，要么明确决定转成 `AUTO_INCREMENT`，
并单独评估它和 upsert 语义的配合）。

**类型映射**复用《自动建表的类型映射》那张表，只是输入从 JDBC 元数据换成 DDL 里的类型文本。
MySQL 侧的表名/列名统一加反引号（顺带修掉保留字列名，如 `order`、`key`、`empty`）；
Oracle 侧按 DDL 里是否带双引号决定要不要加引号。

### 与现有代码的衔接

| 组件 | 变化 |
| --- | --- |
| `db/sync_table_config.sql` | 增加 `ddl` / `id_column` / `key_columns` 列与注释 |
| `SyncTableConfigDao` | 多读上面几列（`ddl` 按 CLOB 读） |
| `OracleDdlParser`（新类） | DDL → 列清单（名字/类型/精度/可空）+ 主键 + 表名 |
| `SyncTableFactory` | 有 `ddl` 走新路径；没有 `ddl` 回落到现在的 `DatabaseMetaData` 路径并打 WARN 提示补 DDL |
| `SyncTableFactory`（存在性/列比对） | 由 `getColumns` / `getPrimaryKeys` 换成 `SELECT … WHERE 1=0` + `ResultSetMetaData` |
| `OracleToMySqlTypeMapper` | 入参由 JDBC 元数据换成解析出来的类型文本，映射规则不变 |
| `TargetTableCreator` | 逻辑不变，只是入参来自解析结果 |
| `TableSyncService` | 不变（分页读 + upsert + 每轮整表重搬） |
| `SyncTable` / `SyncResult` | 增加"结构来源（ddl/metadata）"与校验结论，方便日志和 `/sync/run` 排查 |

### 权限清单（给 DBA 看）

- 源库：业务表 `SELECT`（现在就需要）+ `sync_table_config` 的 `SELECT`；**不需要**任何数据字典/DBA 视图权限；
  可选 `EXECUTE ON DBMS_SQL`（只用于第 3 步硬校验）。
- 目标库：目标库上的 `CREATE` / `DROP` / `INSERT` / `DELETE`（现在就需要）。

### 取舍与风险

- **DDL 与实际表漂移**：第 2 步只能比对列名，类型/长度漂移只告警；建议改表时同步更新 `ddl`，
  或用导出脚本定期刷新这一列。
- **主键来源**：DDL 没带主键时目标表没有主键，`ON DUPLICATE KEY UPDATE` 退化成普通 `INSERT`
  （每轮先 `DELETE`，同一天重跑仍不会重复，但"重跑保护"变弱）→ 建议强制要求 DDL 带主键，或用 `key_columns` 指定。
- **游标列**：改用 DDL 的主键当游标是天然唯一的；若用 `id_column` 覆盖，就要额外校验唯一性，
  否则会踩《边界测试发现的已知缺陷》里的第 1 条（游标值重复会静默丢行）。
- **只建「列 + 主键」**：索引、外键、触发器、分区、列注释都不迁移，需要就在目标库手工建。
- **CLOB 长度**：`DBMS_METADATA` 导出的 DDL 可能几百行，程序只解析、不落文件，日志只打前若干行。
- **灰度**：只在填了 `ddl` 的表上生效，没填的表继续走元数据路径，可以逐表切换。

### 实施顺序

1. 配置表加列 + DAO 读列（不改变任何现有行为）。
2. `OracleDdlParser` + 单测（各种 DDL 片段：引号、注释、多余子句、缺主键）。
3. 校验第 1+2 步，失败时跳过该表、并在 `/sync/run` 里可见。
4. 生成 MySQL DDL 并接到 `TargetTableCreator`（只在有 `ddl` 的表上生效）。
5. 可选：`DBMS_SQL` 硬校验、目标端预演、`id_column` 唯一性校验。
6. 文档与 `db-test` 用例同步：DDL 与表不一致、DDL 缺主键、保留字列名、超长 DDL、DDL 过期。

### 待确认

- `ddl` 由谁产出：DBA 手工裁剪，还是提供一段 `DBMS_METADATA.GET_DDL` 的导出脚本？
- 是否**强制**要求 DDL 带 `PRIMARY KEY`？
- 源表里的 `IDENTITY` / 序列列要不要在 MySQL 侧保留自增语义？

## 性能调优

| 参数 | 说明 |
| --- | --- |
| `threads` | 并发同步的表数，默认 4。受连接池限制：`source.pool-size` / `target.pool-size` 要 ≥ `threads`，否则会排队等连接（启动日志会告警）。表少于线程数时按表数开线程。 |
| `batch-size` | 每页从 Oracle 读的行数，同时也是写入 MySQL 的一个 JDBC 批的行数。默认 2000，可按单行大小往上调（注意 MySQL `max_allowed_packet`）。 |
| `fetch-size` | Oracle 侧 JDBC fetch size，默认 1000，减少网络往返。 |
| `rewriteBatchedStatements=true` | **必加**在目标 JDBC url 上，否则这批语句会被逐条发送；启动时会检查并告警。 |

单表数据量很大时，把 `batch-size` 调到 5000 左右；表很多时优先加 `threads`。

## 运行

本机需要 JDK 17（`java -version` 确认）；没有 Maven 的话把下面的 `mvn` 换成仓库自带的 `./mvnw`（Windows 用 `mvnw.cmd`）。

```bash
mvn spring-boot:run                                        # 常驻，按 cron 触发
mvn spring-boot:run -Dspring-boot.run.arguments=--sync-now  # 立刻跑一次
java -jar target/migration-0.0.1-SNAPSHOT.jar --migration.run-on-startup=true
```

启动日志会打印目标表名规则和 `sync_table_config` 里的源表。目标表可以完全不存在，会被自动创建；
自己维护目标表的话，记得主键/唯一键建在游标列上，否则 upsert 无法去重。

每张源表在目标库里的名字是 `源表名 + 运行日期`（`order` → `order20261014`），所以**每天一张新表**：
每次运行都把整张源表重搬一遍（upsert），当天多跑几次也不会产生重复数据。

## 手动触发（测试用接口）

服务默认监听 8080（可用 `server.port` 改），跑的是和 cron 完全相同的那次任务：

```bash
curl -X POST http://localhost:8080/sync/run
# {"status":"SUCCESS","tables":[{"table":"order20261014","status":"SUCCESS","written":1201,"skipped":0,
#   "durationMs":1843}]}
```

`/sync/run` 是同步的：请求会一直等到这次任务跑完再返回每张表的结果。如果正好有一轮任务在跑，
接口返回 `409` 和 `{"status":"RUNNING"}`，不会并发跑两遍。

## 测试

`mvn test`（60 个用例）用 H2 的 Oracle 兼容模式当源库、MySQL 兼容模式当目标库跑整条链路。
其中 6 个用例标了 `@Disabled`：它们是边界测试复现出来的**已知缺陷**，修掉之前不要打开
（`./mvnw test -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition` 可以强制跑一遍，
看到真实失败）。

| 测试类 | 覆盖 |
| --- | --- |
| `TableSyncServiceTest` | 多表并发（一表一线程）、自动建表并搬全量（1201 行分 3 页）、每轮整表重搬且不产生重复、源表没有时间戳列也照搬、改动的行被更新、已存在的目标表不被重建（多出列保留默认值、源表多出列不搬）、坏数据只跳过、整表失败不影响其他表、config 里源表不存在的行被跳过、空配置表什么都不做、CLOB 中文值搬对、时间戳列为 NULL 的行照搬 |
| `SyncApiTest` | `POST /sync/run` 真起 HTTP 触发并返回每表结果、数据确实落库；空配置返回空结果；有表失败时 `status=FAILED` |
| `SyncTableFactoryTest` | 目标表名推导（默认/自定义 pattern、日期格式、带 schema 的源表名），pattern 缺 `{table}` 直接报错 |
| `OracleToMySqlTypeMapperTest` | Oracle → MySQL 类型映射表，未知类型带列名报错 |
| `TargetTableCreatorTest` | 生成的 `CREATE TABLE`（列、NOT NULL、主键、复合主键、表后缀） |
| `SqlBuilderTest` | 全量分页语句与游标参数、upsert 语句（含只有键列的表） |
| `ValueConverterTest` | CLOB/BLOB/时间/大数/字符的类型转换 |
| `BoundaryCasesTest` | 分页边界（整页、整页+1、两整页、跳跃主键、单行、空表、单列表、微秒时间戳、目标表列序不同）、游标列重复或为 NULL、空白的 `id-column`、`date-offset-days`、`threads: 0`、`batch-size` 为 0 或负数；6 个 `@Disabled` 用例复现已知缺陷，见下 |

### 边界测试发现的已知缺陷

6 个 `@Disabled` 用例各自钉住一条，名字和 `@Disabled` 的说明就是缺陷描述：

| 用例 | 现象 |
| --- | --- |
| `rowsThatShareTheCursorValueAreNotSilentlyDropped` | 游标列值重复时（10 行同值、`batch-size: 4`）只搬 4 行，却报 `SUCCESS`——分页只用 `>` 比较，从不检查游标列唯一性 |
| `rowsAreNotLostWhenTheCursorRepeatsAcrossAPageBoundary` | 游标值跨页重复时同样静默丢行（6 行只搬 4 行） |
| `aNegativeDateOffsetWritesYesterdaysSnapshotTable` | `date-offset-days: -1` 写的是**明天**那张表（代码是 `minusDays(offset)`），与本文档和 `application.yaml` 的说明相反 |
| `aSingleRowTableThatCannotBeWrittenIsReportedAsFailed` | 只有 1 行的表写不进去时报 `SUCCESS`（`written=0 skipped=1`），因为 `writeOneByOne` 只在页内行数 > 1 时才判失败 |
| `aZeroBatchSizeDoesNotSilentlyCopyNothing` | `batch-size: 0` 时一行不搬、目标表为空，却报 `SUCCESS`，和"源表本来就没数据"无法区分 |
| `theSameTableConfiguredUnderTwoCasingsIsNotCopiedByTwoThreadsAtOnce` | 配置表里写 `t_case` 和 `T_CASE` 两行会解析成同一张目标表（MySQL `lower_case_table_names=1`），两个线程同时清空/写入同一张表 |

## 已知限制

- 配置表在源库里，程序只读、**不会自动建表**：先执行 `db/sync_table_config.sql` 建好，否则启动时
  会告警、手动触发 `/sync/run` 会直接报错。
- 目标表名带运行日期，因此**目标库里的表会按天累积**，需要自己定保留策略（例如 DROP 掉 N 天前的表）。
- 源表不存在、或者缺少配置的游标列（默认 `ID`）时，该表在解析阶段被跳过，只会打日志，
  不会出现在 `/sync/run` 的返回里。
- 每天一张目标表；**同一天重复运行会先清空当天目标表再整表重灌**，所以源端删掉的行不会残留。
  代价是：某一轮如果清空之后失败，当天目标表会停在"部分数据"的状态，下一轮会重新清空重来
  （不会自动回滚到上一轮的完整状态）。
- 清空用的是 `DELETE FROM`（可回滚、不要求额外权限），表特别大的话可以改成 `TRUNCATE TABLE` 提速。
- 被跳过的记录只留在日志里不会自动重试；因为每轮都是整表重搬，只要修好数据下一轮就会带上。
