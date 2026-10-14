# Oracle → MySQL 模拟数据测试报告

- 日期：2026-10-14（第五轮：**时间戳专项**；第四轮：同一天重复运行先清空当天目标表再整表重灌）
- 环境：`oracle-test`（gvenzl/oracle-xe:21-slim，PDB `ORCL`，用户 `source_user`）、
  `mysql-test`（mysql:8.4.11，库 `target_db`，用户 `target_user`）
- 被测程序：`migration-0.0.1-SNAPSHOT.jar`（Spring Boot 4.1.1 / Java 17），`threads=4`、`batch-size=2000`
- 单元测试：`mvn test` → **38 个用例全部通过**

## 0. 本轮变更

| 项 | 第二轮 | 第三轮（现在） |
| --- | --- | --- |
| 同步策略 | INCREMENTAL 增量：水位 = 目标表自身 `MAX(时间戳列)`，再回退 `watermark-overlap` | **每轮整表全量**按游标列分页重搬 |
| 同日重复运行 | 只 upsert 增量行，源端删掉的行会残留在目标表 | **先 `DELETE FROM <当天目标表>` 清空，再整表重灌**，结果等于源表当前内容 |
| 相关配置项 | `watermark-overlap`、`timestamp-column`、`sync-mode` | **全部删除** |
| 配置表 | 程序启动时自动创建 | **只读、不建表**；DBA 先执行 `db/sync_table_config.sql`，缺表时只告警 |
| 目标表索引 | 自动给水位列建 `idx_<表>_<列>` | 不再建索引（没有水位列了） |
| `/sync/run` 返回 | 每表带 `watermark` 字段 | 去掉 `watermark`，只剩 `table/status/written/skipped/durationMs` |
| 源表要求 | 增量模式必须有时间戳列，否则整表跳过 | 不需要任何时间戳列 |

## 1. 复跑结果

| 编号 | 场景 | 结果 | 结论 |
| --- | --- | --- | --- |
| R1 | 源库里没有 `sync_table_config` 时启动 | 只打 WARN：`Could not read sync_table_config, create it with db/sync_table_config.sql`，**不再自动建表** | 通过 |
| R2 | 第 1 次全量（6 张正例表 + 3 个负例） | orders→`orders20261014` 5000、customers 2000、products 600、legacy 300、empty_batch 0、nots 20；`tscol20261014` FAILED；`noid`/`missing` 解析期跳过 | 符合预期 |
| R3 | 第 2 次全量（数据没变） | 写入行数与第 1 次**完全相同**（5000/2000/600/300/0/20），目标表行数不变 | 通过（全量重搬且不重复） |
| R4 | 清理负例后再跑 | `status=SUCCESS`，6 张表全绿，`wall=285ms` | 通过 |
| R5 | 目标表 DDL | `orders20261014` 只有主键，**不再有 `idx_*_update_time`** | 符合预期 |
| R6 | 没有任何时间戳列的表（`nots`） | 20 行正常搬入 `nots20261014` | 通过（旧版增量模式下这张表会被跳过） |
| R7 | 源端删过行后再跑（同日） | 日志 `Cleared 5002 row(s) of orders20261014 before the copy`，目标 5002→5001 行、合计 2499902.31→2498952.31，与源库一致，被删的 5000 不再残留 | 通过 |
| R8 | 再删 2 行 + 改 1 行后同日再跑 | 日志 `Cleared 5001 row(s) ...`，写入 4999 行；目标合计 2498869.06、`id=10` 金额 777.77，被删的 5001/5002 消失 | 通过 |

单轮耗时（全量）：第 1 次 1567ms（含建表），第 2 次 689ms，第 3 次 285ms（4 线程并发、共 8120 行）。

## 2. 数据对账（第 3 次全量之后）

| 目标表 | 源表 | 源行数 / 合计 | 目标行数 / 合计 | 结论 |
| --- | --- | --- | --- | --- |
| orders20261014 | ORDERS | 5000 / 2499975 | 5000 / 2499975.00 | 一致 |
| customers20261014 | CUSTOMERS | 2000 / 9919190 | 2000 / 9919190.00 | 一致 |
| products20261014 | PRODUCTS | 600 / 298793 | 600 / 298793.00 | 一致 |
| legacy20261014 | LEGACY | 300 / 127950 | 300 / 127950.00 | 一致 |
| empty_batch20261014 | EMPTY_BATCH | 0 | 0 | 一致 |
| nots20261014 | NOTS | 20 | 20 | 一致 |

类型映射（自动建表）与第二、三轮一致：NUMBER(18)→bigint、NUMBER(9)→int、NUMBER(4)→smallint、
NUMBER(20)→decimal(20,0)、NUMBER(12,2)→decimal(12,2)、无精度 NUMBER→decimal(38,10)、
CLOB→longtext、BLOB→longblob、RAW(16)→varbinary(16)、DATE→datetime、复合主键与无主键表均正确。

## 3. 遗留问题（#1、#4 已由第五轮推进，见 §5）

| 编号 | 级别 | 问题 | 证据 |
| --- | --- | --- | --- |
| 1 | ~~P0~~ **已定位，可配置规避** | TIMESTAMP 列搬不动 | 第五轮复现并定位：ojdbc 把 `TIMESTAMP` 读成私有类型 `oracle.sql.TIMESTAMP`，`ValueConverter` 的 `instanceof java.sql.Timestamp` 不匹配。**加上 `oracle.jdbc.J2EE13Compliant=true` 后 `ts_cols`/`ts_range`/`ts_idkey`/`tscol` 全部通过**（JVM 参数和源 URL 参数两种写法都验过），见 §5.4 |
| 2 | P0 | XMLTYPE 列被静默丢弃 | 第二轮：源表 3 列、`xmlt20261014` 只有 `id`/`update_time`，运行仍报 SUCCESS（Oracle 把 XMLTYPE 记为虚拟列 `VIRTUAL_COLUMN=YES`，被过滤且无日志） |
| 3 | P1 | INTERVAL 映射不可达 | 第二轮：`No MySQL type known for source type 'INTERVAL DAY(2) TO SECOND(6)'`（`baseName()` 在 `(` 处截断） |
| 4 | P0 | TIMESTAMP WITH TIME ZONE 仍失败 | 第五轮：`J2EE13Compliant=true` 对 `oracle.sql.TIMESTAMPTZ` **无效**，`tstz_col` 依然 FAILED，需要改代码按 JDBC 类型取值（`rs.getObject(i, OffsetDateTime.class)`），见 §5.5 |
| 5 | P2 | 源表名撞 Oracle 保留字 | 第二轮：`SELECT ... FROM order ...` 未加引号 → `ORA-00903`，整表 FAILED |
| 6 | P2 | 解析期跳过的表不出现在 `/sync/run` 响应里 | 本轮 `noid`/`missing` 只出现在日志，响应里没有这两张表 |
| 7 | P2 | 失败页逐行重试造成日志风暴 | 本轮 `tscol20261014` 20 行仍产生 20 条 WARN |

已随本轮改动消失的问题：水位回读窗口的额外开销、增量模式必须有时间戳列、目标表名撞 MySQL 保留字
（目标名现在带日期后缀）。

## 4. 复现步骤

```powershell
# 0) 先建配置表（程序不会自己建）
Get-Content .\migration\db\sync_table_config.sql -Raw |
  docker exec -i oracle-test sqlplus -S source_user/source123@localhost:1521/ORCL

# 1) 模拟数据（源表名不带日期）
Get-Content .\db-test\01-oracle-mock-data.sql -Raw |
  docker exec -i oracle-test sqlplus -S source_user/source123@localhost:1521/ORCL

# 2) 迁移清单
Get-Content .\db-test\02-oracle-config.sql -Raw |
  docker exec -i oracle-test sqlplus -S source_user/source123@localhost:1521/ORCL

# 3) 启动并触发（每轮都是全量）
$env:MIGRATION_SOURCE_PASSWORD='source123'; $env:MIGRATION_TARGET_PASSWORD='target123'
java -jar .\migration\target\migration-0.0.1-SNAPSHOT.jar
Invoke-WebRequest -Uri http://localhost:8080/sync/run -Method Post

# 4) 对账
docker exec mysql-test mysql -uroot -proot123 target_db -e "SELECT COUNT(*), SUM(amount) FROM orders20261014"
```

## 5. 第五轮：时间戳专项（2026-10-14）

目标：把"含时间戳列的表"拆成 5 张只测一个维度的表，确认哪些能搬、哪些不能、边界值变成什么样。

### 5.1 新增夹具

`db-test/05-oracle-timestamp.sql`（可重复执行：先 drop 再建，并把自己那 5 行追加进 `sync_table_config`）：

| 表 | 行数 | 只测这一个维度 |
| --- | --- | --- |
| `TS_COLS` | 8 | `DATE` + `TIMESTAMP(0)`/`(3)`/`(6)`/`(9)` + `TIMESTAMP`(默认精度) 放在同一张表，行之间只换值：普通值 / 整秒 / 9 位小数秒 / 小数秒进位 / 纪元 / 纪元前 / 月末 / 全 NULL |
| `TS_RANGE` | 3 | 年份越界：`0001-01-01`、`0999-12-31`、`9999-12-31 23:59:59.999999` |
| `TS_DATEKEY` | 20 | 主键（= 分页游标列）是 `DATE`，跨 4 页（`batch-size=5`） |
| `TS_IDKEY` | 20 | 主键（= 分页游标列）是 `TIMESTAMP(6)`，跨 4 页 |
| `TSTZ_COL` | 5 | `TIMESTAMP WITH TIME ZONE` |

### 5.2 怎么跑的

```powershell
Get-Content .\db-test\02-oracle-config.sql -Raw | docker exec -i oracle-test sqlplus -S source_user/source123@localhost:1521/ORCL
Get-Content .\db-test\05-oracle-timestamp.sql -Raw | docker exec -i oracle-test sqlplus -S source_user/source123@localhost:1521/ORCL
mvn -o -f migration\pom.xml package -DskipTests
java -jar migration\target\migration-0.0.1-SNAPSHOT.jar --server.port=8081 --migration.batch-size=5
Invoke-WebRequest -Uri http://localhost:8081/sync/run -Method Post
```

`batch-size=5` 是故意的：20 行的表要翻 4 页，才能把"游标列是日期/时间戳类型时的分页"真正跑到。
本轮 `application.yaml` 里是 `date-offset-days: -2`（走的是本文档 §1 之外的那条语义：`-2` → 目标表后缀 `20261016`，
即"减几天"实际是"往后几天"），所以下面所有目标表都叫 `*20261016`。

### 5.3 结果（默认配置，未加任何参数）

| 目标表 | 结果 | 说明 |
| --- | --- | --- |
| `orders`/`customers`/`products`/`legacy`/`empty_batch`/`nots` | SUCCESS 4999/2001/600/300/0/20 | 与第四轮一致，没有回归 |
| `ts_datekey20261016` | **SUCCESS 20 行** | **DATE 当主键/游标可以搬，且 5 行一页翻了 4 页搬全**（min/max/sum 与源库逐值一致：`2026-10-13 04:00:00` ~ `23:00:00`、315） |
| `ts_cols20261016` | FAILED 0 行 | TIMESTAMP 列写不进去，整表失败 |
| `ts_range20261016` | FAILED 0 行 | 同上（年份范围还没轮到就被类型挡住） |
| `ts_idkey20261016` | FAILED 0 行 | 主键就是 TIMESTAMP(6)，同样写不进去 |
| `tscol20261016`（第三轮负例） | FAILED 0 行 | 与第三、四轮一致 |
| `tstz_col20261016` | FAILED 0 行 | TIMESTAMP WITH TIME ZONE |

目标表 DDL 本身是对的（建表阶段没问题，卡在写数据）：

```sql
CREATE TABLE `ts_cols20261016` (
  `id` bigint NOT NULL, `d_date` datetime, `ts0` datetime, `ts3` datetime(3),
  `ts6` datetime(6), `ts9` datetime(6), `ts_def` datetime(6), `label` varchar(120),
  PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
```

失败日志（根因）：

```
Writing 3 row(s) into ts_range20261016 as one batch failed
  (Data truncation: Incorrect datetime value:
   '\xAC\xED\x00\x05sr\x00\x14oracle.sql.TIMESTAMP\x91w\x95\xC2\x99Ud\x1F…' for column 'ts6' at row 1)
Skipping ts_range20261016.1: MysqlDataTruncation: …
Table ts_range20261016 failed after 0 row(s)
```

`\xAC\xED\x00\x05` 是 Java 序列化的魔数：ojdbc 默认把 `TIMESTAMP` 读成私有类型 `oracle.sql.TIMESTAMP`
（不是 `java.sql.Timestamp`），`ValueConverter` 的 `instanceof` 分支全部不匹配，值被原样交给 MySQL 驱动，
驱动只好按 Java 序列化字节写 → MySQL 报 `Incorrect datetime value`。整表因此 FAILED、目标表 0 行。

### 5.4 修复验证：只加一个 ojdbc 参数，不改代码

两种写法都验过，效果相同，TIMESTAMP 全部通过（`tscol` 20、`ts_cols` 8、`ts_range` 3、`ts_idkey` 20）：

| 写法 | 具体值 |
| --- | --- |
| JVM 参数 | `java -Doracle.jdbc.J2EE13Compliant=true -jar migration-0.0.1-SNAPSHOT.jar` |
| 源库 URL 参数 | `MIGRATION_SOURCE_URL='jdbc:oracle:thin:@//localhost:1521/ORCL?oracle.jdbc.J2EE13Compliant=true'` |

没有任何副作用被发现：`DATE` 列完全不受影响（`ts_cols.d_date`、`ts_datekey`、`orders.update_time`
与源库逐值一致，没有被压成 00:00:00），CLOB/中文/大数等列也与加参数前一致
（`label` 的 `HEX()` 是标准 UTF-8，例如 `普通值…` = `E699AEE9809AE580BC…`）。

`TIMESTAMP WITH TIME ZONE` **例外**：这个参数对它无效，`tstz_col` 仍报 `oracle.sql.TIMESTAMPTZ` 序列化错误，
必须改代码（`readRow` 里按 `ResultSetMetaData.getColumnType` 走带类型的取值，
如 `rs.getObject(i, LocalDateTime.class)` / `rs.getObject(i, OffsetDateTime.class)`）。

### 5.5 精度与范围的实际表现（加参数之后的取值）

| 源（Oracle） | 目标（MySQL） | 结论 |
| --- | --- | --- |
| `ts9 = …08:00:00.123456789` | `…08:00:00.123457` | 9 位小数秒 → `DATETIME(6)` 是**四舍五入**，不是截断 |
| `ts9 = …11:00:00.999999499` | `…11:00:00.999999` | 舍入到 6 位 |
| `ts9 = 2026-02-28 23:59:59.999999999` | **`2026-03-01 00:00:00.000000`** | 舍入进位会**跨天/跨月**：目标值比源值晚一天（本轮唯一一处"日期都变了"的值） |
| `ts0`（TIMESTAMP(0)） | `datetime`（无小数位） | 类型映射与精度都正确 |
| `TS_RANGE`：`0001-01-01` / `0999-12-31` | `0001-01-01 00:00:00.000000` / `0999-12-31 23:59:59` | MySQL 8.4 的 `DATETIME` 实际能吃下 1000 年之前的年份（`min(ts6)=0001-01-01`、`max(ts6)=9999-12-31 23:59:59.999999`），**不需要为越界年份做特殊处理** |
| `TS_IDKEY` 的 20 行 | `2026-10-14 00:00:00.000000` 起逐秒递增 | 主键/游标是 TIMESTAMP 时，`WHERE ID > ?` 分页正常（4 页搬全） |
| `TS_COLS.ID = 8`（时间戳列全 NULL） | 6 个时间戳列全是 NULL | NULL 照搬 |

### 5.6 本轮结论

1. **P0 有了不改代码的解法**：源库连接加 `oracle.jdbc.J2EE13Compliant=true`（JVM 参数或 URL 参数），TIMESTAMP 列就能搬；
   这是第一优先级，因为它把上一轮范围里"含时间戳的表"从"整表 FAILED"变成"可用"。
2. **`TIMESTAMP WITH TIME ZONE` 仍需改代码**，参数无效。
3. `DATE` 列、日期/时间戳当游标列、`batch-size` 分页本身都没有问题——出问题的只有"值类型"这一层。
4. `TIMESTAMP(9)` → `DATETIME(6)` 会**舍入**并可能进位到下一秒/次日；如果业务要求"只截断不舍入"，
   需要在写库前自己截断到微秒。
5. 遗留问题 #7（失败页逐行重试的日志风暴）在本轮依旧：`tstz_col` 5 行 = 1 条批量 WARN + 5 条逐行 WARN + 1 条 ERROR。
