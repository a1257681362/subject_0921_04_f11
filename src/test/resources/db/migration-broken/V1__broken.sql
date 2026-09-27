-- 故意写错的迁移脚本：验证迁移失败时报错可定位到脚本与行号（第 5 行）。
CREATE TABLE t_flyway_broken_ok (
    id INT PRIMARY KEY
);
THIS IS NOT VALID SQL;
