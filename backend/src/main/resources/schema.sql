ALTER TABLE projects ADD COLUMN IF NOT EXISTS sort_order INT DEFAULT 0 NOT NULL;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS category VARCHAR(32) DEFAULT 'APPLICATION' NOT NULL;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS push_enabled BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS clean_command VARCHAR(2000);

CREATE TABLE IF NOT EXISTS project_commands (
    id              VARCHAR(36)   NOT NULL,
    project_id      VARCHAR(36)   NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    command         VARCHAR(2000) NOT NULL,
    require_stopped BOOLEAN       NOT NULL DEFAULT FALSE,
    timeout_seconds INT,
    sort_order      INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_project_commands PRIMARY KEY (id),
    CONSTRAINT fk_project_commands_project
        FOREIGN KEY (project_id) REFERENCES projects (id)
);

ALTER TABLE project_commands ADD COLUMN IF NOT EXISTS script BOOLEAN DEFAULT FALSE NOT NULL;

-- Build tracking: which launch a command builds, and the history of those builds.
ALTER TABLE project_commands ADD COLUMN IF NOT EXISTS build_for_launch_id VARCHAR(36);

CREATE TABLE IF NOT EXISTS build_records (
    id             VARCHAR(36)  NOT NULL,
    project_id     VARCHAR(36)  NOT NULL,
    launch_id      VARCHAR(36)  NOT NULL,
    command_id     VARCHAR(36)  NOT NULL,
    command_name   VARCHAR(200) NOT NULL,
    commit_sha     VARCHAR(40),
    commit_subject VARCHAR(500),
    branch         VARCHAR(200),
    status         VARCHAR(16)  NOT NULL,
    exit_code      INT,
    started_at     TIMESTAMP    NOT NULL,
    finished_at    TIMESTAMP,
    CONSTRAINT pk_build_records PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_build_records_launch ON build_records (launch_id, started_at);

-- Rung 1: named <alias>.localhost address per launch.
ALTER TABLE launches ADD COLUMN IF NOT EXISTS alias VARCHAR(200);

-- Rung 2/3: how far the named address reaches (LOCAL / WIFI / INTERNET).
ALTER TABLE launches ADD COLUMN IF NOT EXISTS reach VARCHAR(16) DEFAULT 'LOCAL' NOT NULL;

-- Rung 3: when an INTERNET share link auto-expires (NULL = no expiry).
ALTER TABLE launches ADD COLUMN IF NOT EXISTS share_expires_at TIMESTAMP;
