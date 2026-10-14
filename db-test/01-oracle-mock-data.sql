-- ============================================================================
-- 模拟数据（Oracle 源库 source_user / 服务名 ORCL）
--
-- 用法：
--   Get-Content .\db-test\01-oracle-mock-data.sql -Raw |
--     docker exec -i oracle-test sqlplus -S source_user/source123@localhost:1521/ORCL
--
-- 源表就是 Oracle 里的普通业务表（不带日期）；目标表名由程序按「源表名 + 运行日期」生成，
-- 例如 ORDERS -> orders20261014（见 02-oracle-config.sql 里的 sync_table_config）。
-- 表结构刻意覆盖 OracleToMySqlTypeMapper 的映射矩阵，取值全部可复算，方便搬完逐列对账。
-- 脚本可重复执行：先删掉所有测试表再重建。
-- ============================================================================

set echo off
set feedback on
set linesize 200
set pagesize 100

-- 0) 清理旧表
declare
  v_sql varchar2(200);
begin
  for t in (select table_name from user_tables where table_name in
             ('ORDERS', 'CUSTOMERS', 'PRODUCTS', 'LEGACY', 'EMPTY_BATCH', 'NOTS', 'NOID', 'TSCOL',
              'INTV', 'TSTZ', 'XMLT', 'ORDER', 'EMPTY')) loop
    v_sql := 'drop table "' || t.table_name || '" purge';
    execute immediate v_sql;
  end loop;
end;
/

-- ----------------------------------------------------------------------------
-- 1) ORDERS —— 基础业务表，5000 行
--    number(10)->BIGINT, varchar2(32)->VARCHAR(32), number(12,2)->DECIMAL(12,2),
--    clob->LONGTEXT, date->DATETIME
--    UPDATE_TIME 每 50 行共用一个秒值，专门压 keyset 分页里 (水位, 游标) 相同的场景
-- ----------------------------------------------------------------------------
create table ORDERS (
  ID          number(10)   not null,
  ORDER_NO    varchar2(32) not null,
  AMOUNT      number(12,2),
  REMARK      clob,
  CREATED_AT  date,
  UPDATE_TIME date,
  constraint PK_ORDERS primary key (ID)
);

insert into ORDERS (ID, ORDER_NO, AMOUNT, REMARK, CREATED_AT, UPDATE_TIME)
select
  level,
  'SO-20261014-' || lpad(level, 6, '0'),
  round(mod(level * 7919, 100000) / 100, 2),
  case when mod(level, 10) = 0 then null
       else '中文备注：第 ' || level || ' 号订单，用于验证 CLOB 的搬运' end,
  trunc(sysdate) + level / 86400,
  trunc(sysdate) + floor(level / 50) / 86400
from dual
connect by level <= 5000;

-- ----------------------------------------------------------------------------
-- 2) CUSTOMERS —— 类型矩阵表，2000 行
--    number(18)->BIGINT, number(4)->SMALLINT, number(9)->INT, number(20)->DECIMAL(20,0),
--    number(无精度)->DECIMAL(38,10), char(2)->CHAR(2), binary_double->DOUBLE,
--    binary_float->FLOAT, raw(16)->VARBINARY(16)
--    日期列都用 DATE：TIMESTAMP 列目前搬不动（ValueConverter 不认 oracle.sql.TIMESTAMP），
--    见第 8 节的 TSCOL 负例。
-- ----------------------------------------------------------------------------
create table CUSTOMERS (
  ID            number(18)   not null,
  CUSTOMER_NAME varchar2(64) not null,
  COUNTRY       char(2),
  LEVEL_NO      number(4),
  POINTS        number(9),
  BALANCE       number(12,2),
  HUGE_NUM      number(20),
  RATIO         number,
  SCORE         binary_double,
  WEIGHT        binary_float,
  MEMO          clob,
  AVATAR        blob,
  TOKEN         raw(16),
  CREATED_AT    date,
  LAST_SEEN     date,
  UPDATE_TIME   date,
  constraint PK_CUSTOMERS primary key (ID)
);

insert into CUSTOMERS
  (ID, CUSTOMER_NAME, COUNTRY, LEVEL_NO, POINTS, BALANCE, HUGE_NUM, RATIO, SCORE, WEIGHT,
   MEMO, AVATAR, TOKEN, CREATED_AT, LAST_SEEN, UPDATE_TIME)
select
  level,
  '客户-' || lpad(level, 6, '0'),
  case mod(level, 4) when 0 then 'CN' when 1 then 'US' when 2 then 'DE' else 'JP' end,
  mod(level, 100),
  mod(level * 37, 1000000),
  round(mod(level * 7919, 1000000) / 100, 2),
  level * 1000000000000,
  round(level / 3, 8),
  level * 0.125,
  level * 1.5,
  '中文备注：第 ' || level || ' 个客户，验证 CLOB 转 LONGTEXT',
  to_blob(utl_raw.cast_to_raw(rpad('B' || level, 48, '#'))),
  utl_raw.cast_to_raw('TKN-' || lpad(level, 4, '0')),
  trunc(sysdate) - mod(level, 365),
  trunc(sysdate) + (floor(level / 20) + mod(level, 20) / 20) / 86400,
  trunc(sysdate) + floor(level / 20) / 86400
from dual
connect by level <= 2000;

-- ----------------------------------------------------------------------------
-- 3) PRODUCTS —— 复合主键 (ID, REGION)，600 行
-- ----------------------------------------------------------------------------
create table PRODUCTS (
  ID          number(9)     not null,
  REGION      char(2)       not null,
  SKU         varchar2(32)  not null,
  PRICE       number(10,2),
  STOCK       number(6),
  TAGS        varchar2(200),
  UPDATE_TIME date,
  constraint PK_PRODUCTS primary key (ID, REGION)
);

insert into PRODUCTS (ID, REGION, SKU, PRICE, STOCK, TAGS, UPDATE_TIME)
select
  level,
  case mod(level, 4) when 0 then 'CN' when 1 then 'US' when 2 then 'DE' else 'JP' end,
  'SKU-' || lpad(level, 5, '0'),
  round(mod(level * 331, 100000) / 100, 2),
  mod(level * 17, 5000),
  '标签：新品,促销,第' || level || '批',
  trunc(sysdate) + floor(level / 10) / 86400
from dual
connect by level <= 600;

-- ----------------------------------------------------------------------------
-- 4) LEGACY —— 没有主键的表，300 行（靠 migration.id-column=ID 兜底）
-- ----------------------------------------------------------------------------
create table LEGACY (
  ID          number(10)   not null,
  NAME        varchar2(64),
  QTY         number(6),
  UPDATE_TIME date
);

insert into LEGACY (ID, NAME, QTY, UPDATE_TIME)
select
  level,
  '历史数据-' || lpad(level, 4, '0'),
  mod(level * 13, 900),
  trunc(sysdate) + floor(level / 10) / 86400
from dual
connect by level <= 300;

-- ----------------------------------------------------------------------------
-- 5) EMPTY_BATCH —— 空表，0 行（验证空表也会建目标表且写入 0 行）
-- ----------------------------------------------------------------------------
create table EMPTY_BATCH (
  ID          number(10)   not null,
  NAME        varchar2(64),
  UPDATE_TIME date,
  constraint PK_EMPTY_BATCH primary key (ID)
);

-- ----------------------------------------------------------------------------
-- 6) NOTS —— 没有任何时间戳列（全量模式下这是正常表，应该能搬）
-- 7) NOID —— 没有 ID 列也没有主键（游标列解析失败的报错路径）
-- ----------------------------------------------------------------------------
create table NOTS (
  ID     number(10)   not null,
  NAME   varchar2(64),
  constraint PK_NOTS primary key (ID)
);

insert into NOTS (ID, NAME)
select level, '没有时间戳列-' || level from dual connect by level <= 20;

create table NOID (
  NAME   varchar2(64),
  AMOUNT number(10,2),
  UPDATE_TIME date
);

insert into NOID (NAME, AMOUNT, UPDATE_TIME)
select '没有主键和 ID 列-' || level, level * 1.5, trunc(sysdate) from dual connect by level <= 20;

-- ----------------------------------------------------------------------------
-- 8) TSCOL —— 表里有一个 TIMESTAMP 普通列
--    预期：读得到行，但写 MySQL 时 oracle.sql.TIMESTAMP 直接序列化成了字节串，
--    每行都报 Incorrect datetime value，整张表 FAILED
-- ----------------------------------------------------------------------------
create table TSCOL (
  ID          number(10) not null,
  QTY         number(6),
  LAST_SEEN   timestamp(6),
  UPDATE_TIME date,
  constraint PK_TSCOL primary key (ID)
);

insert into TSCOL (ID, QTY, LAST_SEEN, UPDATE_TIME)
select level, level * 5, cast(trunc(sysdate) as timestamp) + level / 86400, trunc(sysdate) + level / 86400
from dual connect by level <= 20;

commit;

-- ----------------------------------------------------------------------------
-- 自检：各表行数（后面用来跟 MySQL 侧对账）
-- ----------------------------------------------------------------------------
select count(*) as orders_rows, sum(AMOUNT) as orders_amount from ORDERS;
select count(*) as customers_rows, sum(BALANCE) as customers_balance from CUSTOMERS;
select count(*) as products_rows, sum(PRICE) as products_price from PRODUCTS;
select count(*) as legacy_rows, sum(QTY) as legacy_qty from LEGACY;
select count(*) as empty_batch_rows from EMPTY_BATCH;
select count(*) as nots_rows from NOTS;
select count(*) as noid_rows from NOID;
select count(*) as tscol_rows from TSCOL;

exit
