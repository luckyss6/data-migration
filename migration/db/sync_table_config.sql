-- 配置表：一行一个需要同步的目标表。
--
-- 程序启动时会自动建这张表（CREATE TABLE IF NOT EXISTS，DDL 与本脚本一致），
-- 这个脚本用于提前手工建表，或者交给 DBA 走变更流程。

CREATE TABLE IF NOT EXISTS sync_table_config (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    target_table VARCHAR(100) NOT NULL COMMENT '目标表名，如 order；源表名 = 目标表名 + 运行日期，如 order20261014',
    enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT '0 = 暂停这张表',
    remark       VARCHAR(500) NULL COMMENT '备注',
    updated_at   TIMESTAMP    NULL COMMENT '最后修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sync_table_config_target_table (target_table)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Oracle -> MySQL 同步的目标表清单';

-- 示例：目标表 order 会在运行时读取源库的 order20261014 快照
INSERT INTO sync_table_config (target_table, remark) VALUES ('order', '订单');

-- 暂停一张表：
-- UPDATE sync_table_config SET enabled = 0 WHERE target_table = 'order';

-- 查看当前要同步哪些表：
-- SELECT target_table, enabled, remark FROM sync_table_config ORDER BY id;
