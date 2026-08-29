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

-- 主项目表（仓库层面：目录 / git / clean）
CREATE TABLE IF NOT EXISTS projects (
    id              VARCHAR(36)   NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    root_directory  VARCHAR(1000) NOT NULL,
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

-- 启动项表（运行层面：一个项目可有多个启动脚本）
CREATE TABLE IF NOT EXISTS launches (
    id              VARCHAR(36)   NOT NULL,
    project_id      VARCHAR(36)   NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    start_command   VARCHAR(2000) NOT NULL,
    stop_command    VARCHAR(2000),
    sort_order      INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL,
    CONSTRAINT pk_launches PRIMARY KEY (id),
    CONSTRAINT fk_launches_project
        FOREIGN KEY (project_id) REFERENCES projects (id)
);

-- 启动项监听端口（一对多）
CREATE TABLE IF NOT EXISTS launch_ports (
    launch_id   VARCHAR(36) NOT NULL,
    port        INT         NOT NULL,
    CONSTRAINT fk_launch_ports_launch
        FOREIGN KEY (launch_id) REFERENCES launches (id)
);

-- 自定义维护指令表（clean / build 前端 / build 后端 ...）
CREATE TABLE IF NOT EXISTS project_commands (
    id              VARCHAR(36)   NOT NULL,
    project_id      VARCHAR(36)   NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    command         VARCHAR(2000) NOT NULL,
    require_stopped BOOLEAN       NOT NULL DEFAULT FALSE,
    script          BOOLEAN       NOT NULL DEFAULT FALSE,
    timeout_seconds INT,
    sort_order      INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_project_commands PRIMARY KEY (id),
    CONSTRAINT fk_project_commands_project
        FOREIGN KEY (project_id) REFERENCES projects (id)
);

-- 运行时状态表（记录已启动进程的 PID，project_id 列现存放 launch id）
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
