-- 一次性建库
--
-- 这一步不能放进 Flyway 迁移：Flyway 连的是一个已经存在的库（JDBC URL 里
-- 就写着库名），它既没有权限也没有语义去创建库。
-- 执行顺序是：1. 本文件（建库） 2. 启动应用 —— Flyway 自动跑 V1 / V2
--
-- 执行（路径 A 本机 MySQL）：
--   mysql -uroot -p < sql/00_create_database.sql
-- 路径 B docker compose：
--   docker exec -i chargeops-mysql mysql -uroot -p123456 < sql/00_create_database.sql

CREATE DATABASE IF NOT EXISTS `chargeops`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
