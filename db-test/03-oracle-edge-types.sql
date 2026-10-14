-- ============================================================================
-- 边界类型用例（Oracle 源库），每张表 20 行
--
-- OracleToMySqlTypeMapper 声称支持这几种类型：
--   INTERVAL DAY TO SECOND / INTERVAL YEAR TO MONTH -> VARCHAR(64)
--   TIMESTAMP WITH TIME ZONE                        -> DATETIME(n)
--   XMLTYPE                                         -> LONGTEXT
-- 这几张表用来验证"类型映射"和"取值转换"(ValueConverter)是否真的对得上。
-- 三张表分开建，是为了让失败结果能定位到具体是哪种类型。
-- ============================================================================

set echo off
set feedback on
set linesize 200
set pagesize 100

declare
  v_sql varchar2(200);
begin
  for t in (select table_name from user_tables where table_name in ('INTV','TSTZ','XMLT')) loop
    v_sql := 'drop table "' || t.table_name || '" purge';
    execute immediate v_sql;
  end loop;
end;
/

-- 1) INTERVAL 类型
create table INTV (
  ID          number(10) not null,
  SPAN_DS     interval day to second,
  SINCE_YM    interval year to month,
  UPDATE_TIME date,
  constraint PK_INTV primary key (ID)
);

insert into INTV (ID, SPAN_DS, SINCE_YM, UPDATE_TIME)
select level,
       numtodsinterval(level, 'MINUTE'),
       numtoyminterval(level, 'MONTH'),
       trunc(sysdate) + level / 86400
from dual connect by level <= 20;

-- 2) TIMESTAMP WITH TIME ZONE
create table TSTZ (
  ID          number(10) not null,
  TS_TZ       timestamp with time zone,
  UPDATE_TIME date,
  constraint PK_TSTZ primary key (ID)
);

insert into TSTZ (ID, TS_TZ, UPDATE_TIME)
select level,
       systimestamp - numtodsinterval(level, 'MINUTE'),
       trunc(sysdate) + level / 86400
from dual connect by level <= 20;

-- 3) XMLTYPE
create table XMLT (
  ID          number(10) not null,
  PAYLOAD     xmltype,
  UPDATE_TIME date,
  constraint PK_XMLT primary key (ID)
);

insert into XMLT (ID, PAYLOAD, UPDATE_TIME)
select level,
       xmltype('<order><id>' || level || '</id><name>中文名称</name></order>'),
       trunc(sysdate) + level / 86400
from dual connect by level <= 20;

commit;

select count(*) as intv_rows from INTV;
select count(*) as tstz_rows from TSTZ;
select count(*) as xmlt_rows from XMLT;

exit
