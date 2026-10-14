-- ============================================================================
-- 保留字用例：源表叫 ORDER / EMPTY（在 Oracle 里这两个名字合法）
--
-- 目标表名由程序按「源表名 + 运行日期」生成，所以落到 MySQL 里是 order20261014 /
-- empty20261014，带上日期后缀后不再撞 MySQL 保留字（第一轮测试时目标表直接叫 order/empty，
-- 是建不出来的）。但要注意：ORDER 同时也是 **Oracle** 的保留字，程序拼 SELECT 时不给标识符
-- 加引号，所以 `SELECT ... FROM order` 会报 ORA-00903，这张表目前仍然搬不动；
-- EMPTY 只在 MySQL 里是保留字，能正常搬运。这个用例正好把两者区分开。
-- ============================================================================

set echo off
set feedback on
set linesize 200

declare
  v_sql varchar2(200);
begin
  for t in (select table_name from user_tables where table_name in ('ORDER','EMPTY')) loop
    v_sql := 'drop table "' || t.table_name || '" purge';
    execute immediate v_sql;
  end loop;
end;
/

create table "ORDER" (
  ID          number(10)   not null,
  ORDER_NO    varchar2(32) not null,
  AMOUNT      number(12,2),
  UPDATE_TIME date,
  constraint PK_ORDER primary key (ID)
);

insert into "ORDER" (ID, ORDER_NO, AMOUNT, UPDATE_TIME)
select level, 'SO-RESERVED-' || lpad(level, 4, '0'), level * 3.14 / 2, trunc(sysdate) + level / 86400
from dual connect by level <= 10;

create table EMPTY (
  ID          number(10)   not null,
  NAME        varchar2(64),
  UPDATE_TIME date,
  constraint PK_EMPTY primary key (ID)
);

insert into EMPTY (ID, NAME, UPDATE_TIME)
select level, '保留字 empty 的源数据-' || level, trunc(sysdate) + level / 86400
from dual connect by level <= 10;

commit;

select count(*) as order_rows from "ORDER";
select count(*) as empty_rows from EMPTY;

exit
