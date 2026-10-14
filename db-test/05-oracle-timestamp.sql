-- ============================================================================
-- 时间戳专项模拟数据（源库 Oracle），第五轮测试用
--
-- 目的：把「含时间戳列的表」拆成 5 张，一张只测一个维度，失败时能直接定位到"是哪种时间戳"：
--
--   TS_COLS     8 行：DATE + TIMESTAMP(0)/(3)/(6)/(9) + TIMESTAMP(默认精度) 放在同一张表里，
--                     行之间只变值（普通值 / 整秒 / 9 位小数秒 / 小数秒进位 / 纪元 / 纪元前 / 全 NULL / 月末）
--   TS_RANGE    3 行：年份超出 MySQL DATETIME 支持范围（0001 年、0999 年、9999 年末）
--   TS_DATEKEY 20 行：主键（也就是分页游标列）是 DATE 类型，行数刻意 > 批大小，用来验证
--                     "游标列是时间/日期类型时的分页"
--   TS_IDKEY   20 行：主键是 TIMESTAMP(6)，同上，但游标列是 TIMESTAMP
--   TSTZ_COL    5 行：TIMESTAMP WITH TIME ZONE（另一种驱动返回类型）
--
-- 用法（先跑 02 恢复完整迁移清单，再跑本脚本追加这 5 张表）：
--   Get-Content .\db-test\02-oracle-config.sql -Raw | docker exec -i oracle-test sqlplus -S source_user/source123@localhost:1521/ORCL
--   Get-Content .\db-test\05-oracle-timestamp.sql -Raw | docker exec -i oracle-test sqlplus -S source_user/source123@localhost:1521/ORCL
-- ============================================================================

set echo off
set feedback on
set linesize 200
set pagesize 200
set numwidth 22

declare
  v_sql varchar2(200);
begin
  for t in (select table_name from user_tables
             where table_name in ('TS_COLS','TS_RANGE','TS_DATEKEY','TS_IDKEY','TSTZ_COL')) loop
    v_sql := 'drop table "' || t.table_name || '" purge';
    execute immediate v_sql;
  end loop;
end;
/

-- ---------------------------------------------------------------------------
-- 1) TS_COLS：类型/精度矩阵，每行只换一个值维度
-- ---------------------------------------------------------------------------
create table TS_COLS (
  ID       number(10)  not null,
  D_DATE   date,
  TS0      timestamp(0),
  TS3      timestamp(3),
  TS6      timestamp(6),
  TS9      timestamp(9),
  TS_DEF   timestamp,
  LABEL    varchar2(120),
  constraint PK_TS_COLS primary key (ID)
);

insert into TS_COLS values (1, timestamp '2026-10-01 08:00:00',
  timestamp '2026-10-01 08:00:00.123456',
  timestamp '2026-10-01 08:00:00.123456',
  timestamp '2026-10-01 08:00:00.123456',
  timestamp '2026-10-01 08:00:00.123456789',
  timestamp '2026-10-01 08:00:00.123456', '普通值：各列精度不同');

insert into TS_COLS values (2, timestamp '2026-10-01 09:30:15',
  timestamp '2026-10-01 09:30:15',
  timestamp '2026-10-01 09:30:15',
  timestamp '2026-10-01 09:30:15',
  timestamp '2026-10-01 09:30:15',
  timestamp '2026-10-01 09:30:15', '整秒：无小数秒');

insert into TS_COLS values (3, timestamp '2026-10-01 10:00:00',
  timestamp '2026-10-01 10:00:00',
  timestamp '2026-10-01 10:00:00.123',
  timestamp '2026-10-01 10:00:00.123456',
  timestamp '2026-10-01 10:00:00.123456789',
  timestamp '2026-10-01 10:00:00.123456', '小数秒 9 位：测 9 -> 6 的取舍');

insert into TS_COLS values (4, timestamp '2026-10-01 11:00:00',
  timestamp '2026-10-01 11:00:00',
  timestamp '2026-10-01 11:00:00.999',
  timestamp '2026-10-01 11:00:00.999999',
  timestamp '2026-10-01 11:00:00.999999499',
  timestamp '2026-10-01 11:00:00.999999', '小数秒进位：999999499 截/舍到 6 位');

insert into TS_COLS values (5, timestamp '1970-01-01 00:00:00',
  timestamp '1970-01-01 00:00:00',
  timestamp '1970-01-01 00:00:00',
  timestamp '1970-01-01 00:00:00',
  timestamp '1970-01-01 00:00:00',
  timestamp '1970-01-01 00:00:00', '纪元 epoch 整点');

insert into TS_COLS values (6, timestamp '1969-12-31 23:59:59',
  timestamp '1969-12-31 23:59:59',
  timestamp '1969-12-31 23:59:59',
  timestamp '1969-12-31 23:59:59',
  timestamp '1969-12-31 23:59:59',
  timestamp '1969-12-31 23:59:59', '纪元之前（MySQL TIMESTAMP 会自动截断，DATETIME 不会）');

insert into TS_COLS values (7, timestamp '2026-02-28 23:59:59',
  timestamp '2026-02-28 23:59:59',
  timestamp '2026-02-28 23:59:59.999',
  timestamp '2026-02-28 23:59:59.999999',
  timestamp '2026-02-28 23:59:59.999999999',
  timestamp '2026-02-28 23:59:59.999999', '月末 23:59:59.999999(+9)');

insert into TS_COLS values (8, null, null, null, null, null, null, '全 NULL：时间戳列都为 NULL');

-- ---------------------------------------------------------------------------
-- 2) TS_RANGE：年份越界（MySQL DATETIME 只支持 1000-01-01 ~ 9999-12-31）
-- ---------------------------------------------------------------------------
create table TS_RANGE (
  ID       number(10)  not null,
  TS6      timestamp(6),
  D_DATE   date,
  LABEL    varchar2(120),
  constraint PK_TS_RANGE primary key (ID)
);

insert into TS_RANGE values (1, timestamp '0001-01-01 00:00:00', date '0001-01-01', '公元 1 年：低于 MySQL 下界');
insert into TS_RANGE values (2, timestamp '0999-12-31 23:59:59', date '0999-12-31', '999 年：低于 MySQL 下界');
insert into TS_RANGE values (3, timestamp '9999-12-31 23:59:59.999999', date '9999-12-31', '9999 年末：正好是 MySQL 上界');

-- ---------------------------------------------------------------------------
-- 3) TS_DATEKEY：主键/游标列是 DATE，20 行（> 批大小 5，跨 4 页）
-- ---------------------------------------------------------------------------
create table TS_DATEKEY (
  ID       date         not null,
  LABEL    varchar2(40),
  AMOUNT   number(12,2),
  constraint PK_TS_DATEKEY primary key (ID)
);

insert into TS_DATEKEY (ID, LABEL, AMOUNT)
select trunc(sysdate) - (21 - level) / 24, 'date-key ' || level, level * 1.5
from dual connect by level <= 20;

-- ---------------------------------------------------------------------------
-- 4) TS_IDKEY：主键/游标列是 TIMESTAMP(6)，20 行
-- ---------------------------------------------------------------------------
create table TS_IDKEY (
  ID       timestamp(6) not null,
  LABEL    varchar2(40),
  AMOUNT   number(12,2),
  constraint PK_TS_IDKEY primary key (ID)
);

insert into TS_IDKEY (ID, LABEL, AMOUNT)
select cast(trunc(sysdate) as timestamp) + (level - 1) / 86400, 'ts-key ' || level, level * 2.5
from dual connect by level <= 20;

-- ---------------------------------------------------------------------------
-- 5) TSTZ_COL：TIMESTAMP WITH TIME ZONE
-- ---------------------------------------------------------------------------
create table TSTZ_COL (
  ID       number(10) not null,
  TS_TZ    timestamp with time zone,
  LABEL    varchar2(40),
  constraint PK_TSTZ_COL primary key (ID)
);

insert into TSTZ_COL (ID, TS_TZ, LABEL)
select level, systimestamp - numtodsinterval(level, 'MINUTE'), 'tstz ' || level
from dual connect by level <= 5;

commit;

-- ---------------------------------------------------------------------------
-- 迁移清单：把这 5 张表追加进去（可重复执行）
-- ---------------------------------------------------------------------------
delete from sync_table_config where source_table in ('ts_cols','ts_range','ts_datekey','ts_idkey','tstz_col');

insert into sync_table_config (source_table, remark) values ('ts_cols',    '时间戳专项：DATE + TIMESTAMP(0/3/6/9) 精度矩阵，8 行');
insert into sync_table_config (source_table, remark) values ('ts_range',   '时间戳专项：年份越界 0001/0999/9999，3 行');
insert into sync_table_config (source_table, remark) values ('ts_datekey', '时间戳专项：主键是 DATE，20 行，跨多页');
insert into sync_table_config (source_table, remark) values ('ts_idkey',   '时间戳专项：主键是 TIMESTAMP(6)，20 行，跨多页');
insert into sync_table_config (source_table, remark) values ('tstz_col',   '时间戳专项：TIMESTAMP WITH TIME ZONE，5 行');

commit;

select source_table, enabled, remark from sync_table_config order by id;

select 'TS_COLS' as src, count(*) as rows_ from TS_COLS
union all select 'TS_RANGE', count(*) from TS_RANGE
union all select 'TS_DATEKEY', count(*) from TS_DATEKEY
union all select 'TS_IDKEY', count(*) from TS_IDKEY
union all select 'TSTZ_COL', count(*) from TSTZ_COL;

select ID, to_char(D_DATE, 'YYYY-MM-DD HH24:MI:SS') as d_date,
       to_char(TS0, 'YYYY-MM-DD HH24:MI:SS.FF9') as ts0,
       to_char(TS9, 'YYYY-MM-DD HH24:MI:SS.FF9') as ts9,
       LABEL
from TS_COLS order by ID;

exit
