# Oracle → MySQL 模拟数据测试报告

- 日期：2026-10-14（第四轮：**同一天重复运行先清空当天目标表再整表重灌**）
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

## 3. 遗留问题（本轮未改动相关代码，状态不变）

| 编号 | 级别 | 问题 | 证据 |
| --- | --- | --- | --- |
| 1 | P0 | TIMESTAMP 列搬不动 | 本轮 `tscol20261014` 依旧 FAILED：`Incorrect datetime value: '\xAC\xED\x00\x05sr\x00\x14oracle.sql.TIMESTAMP…'`（ValueConverter 不认 ojdbc 私有类型） |
| 2 | P0 | XMLTYPE 列被静默丢弃 | 第二轮：源表 3 列、`xmlt20261014` 只有 `id`/`update_time`，运行仍报 SUCCESS（Oracle 把 XMLTYPE 记为虚拟列 `VIRTUAL_COLUMN=YES`，被过滤且无日志） |
| 3 | P1 | INTERVAL 映射不可达 | 第二轮：`No MySQL type known for source type 'INTERVAL DAY(2) TO SECOND(6)'`（`baseName()` 在 `(` 处截断） |
| 4 | P1 | TIMESTAMP WITH TIME ZONE 失败 | 第二轮：`oracle.sql.TIMESTAMPTZ` 序列化写入报错 |
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
