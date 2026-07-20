-- =============================================================
-- Project Management — 初始建库脚本
--
-- 用途：在新电脑上首次克隆项目时使用。
--   1. 将当前的 schema.sql 备份（如重命名为 schema.sql.bak）
--   2. 将本文件重命名为 schema.sql
--   3. 启动应用，Spring Boot 会自动执行本脚本建立所有表
--   4. 启动完成后，再将 schema.sql 改回原来的增量脚本
--      （或直接保留本脚本，IF NOT EXISTS 保证重复执行无副作用）
--
-- 数据库：H2 (file-based)，见 application.yml
--   url: jdbc:h2:file:./data/pm
-- =============================================================

-- 主项目表
CREATE TABLE IF NOT EXISTS projects (
    id              VARCHAR(36)   NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    root_directory  VARCHAR(1000) NOT NULL,
    start_command   VARCHAR(2000) NOT NULL,
    stop_command    VARCHAR(2000),
    clean_command   VARCHAR(2000),
    description     VARCHAR(2000),
    category        VARCHAR(32)   NOT NULL DEFAULT 'APPLICATION',
    sort_order      INT           NOT NULL DEFAULT 0,
    push_enabled    BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL,
    CONSTRAINT pk_projects         PRIMARY KEY (id),
    CONSTRAINT uq_projects_name    UNIQUE (name)
);

-- 项目监听端口（一对多）
CREATE TABLE IF NOT EXISTS project_ports (
    project_id  VARCHAR(36) NOT NULL,
    port        INT         NOT NULL,
    CONSTRAINT fk_project_ports_project
        FOREIGN KEY (project_id) REFERENCES projects (id)
);

-- 运行时状态表（记录已启动进程的 PID）
CREATE TABLE IF NOT EXISTS runtime_state (
    project_id  VARCHAR(36) NOT NULL,
    pid         BIGINT      NOT NULL,
    started_at  TIMESTAMP   NOT NULL,
    CONSTRAINT pk_runtime_state PRIMARY KEY (project_id)
);

-- 运行时状态对应端口（一对多）
CREATE TABLE IF NOT EXISTS runtime_state_ports (
    project_id  VARCHAR(36) NOT NULL,
    port        INT         NOT NULL,
    CONSTRAINT fk_runtime_state_ports_state
        FOREIGN KEY (project_id) REFERENCES runtime_state (project_id)
);
