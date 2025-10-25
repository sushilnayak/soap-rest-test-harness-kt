-- Enable UUID extension
CREATE
EXTENSION IF NOT EXISTS "uuid-ossp";

-- Create users table
-- drop table users;
-- CREATE TABLE IF NOT EXISTS users
-- (
--     id            UUID PRIMARY KEY         DEFAULT uuid_generate_v4(),
--     racf_id       VARCHAR(50) UNIQUE NOT NULL,
--     password_hash VARCHAR(255)       NOT NULL,
--     roles         TEXT[] DEFAULT '{"ROLE_USER"}',
--     created_at    TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
--     updated_at    TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
-- );
-- ALTER TABLE users
--     ADD COLUMN IF NOT EXISTS is_enabled BOOLEAN DEFAULT true NOT NULL,
--     ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
--
-- -- Create index for better query performance
-- CREATE INDEX IF NOT EXISTS idx_users_is_enabled ON users(is_enabled);
-- CREATE INDEX IF NOT EXISTS idx_users_racf_id ON users(racf_id);
-- CREATE INDEX IF NOT EXISTS idx_users_roles ON users USING GIN(roles);
--
-- -- Add comments
-- COMMENT ON COLUMN users.is_enabled IS 'Whether the user account is enabled';
-- COMMENT ON COLUMN users.deleted_at IS 'Timestamp when user was soft deleted';
create table public.users
(
    id            uuid      default uuid_generate_v4() not null
        primary key,
    racf_id       varchar(50)                          not null
        constraint users_user_id_key
            unique,
    password_hash varchar(255)                         not null,
    roles         text[]    default '{USER}'::text[],
    created_at    timestamp default CURRENT_TIMESTAMP,
    updated_at    timestamp default CURRENT_TIMESTAMP,
    is_enabled    boolean   default true               not null
);

alter table public.users
    owner to postgres;

create index idx_users_user_id
    on public.users (racf_id);



-- Create projects table
-- CREATE TABLE IF NOT EXISTS projects
-- (
--     id         UUID PRIMARY KEY         DEFAULT uuid_generate_v4(),
--     name       VARCHAR(255) NOT NULL,
--     type       VARCHAR(10)  NOT NULL CHECK (type IN ('REST', 'SOAP')),
--     meta       JSONB        NOT NULL,
--     owner_id   VARCHAR(50)  NOT NULL,
--     created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
--     updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
--
-- );

-- Create indexes
-- CREATE INDEX IF NOT EXISTS idx_users_user_id ON users (user_id);
-- CREATE INDEX IF NOT EXISTS idx_projects_owner_id ON projects (owner_id);
-- CREATE INDEX IF NOT EXISTS idx_projects_type ON projects (type);
-- CREATE INDEX IF NOT EXISTS idx_projects_meta ON projects USING GIN (meta);


create table public.projects
(
    id                uuid                     default uuid_generate_v4() not null
        primary key,
    name              varchar(255)                                        not null,
    type              varchar(10)                                         not null
        constraint projects_type_check
            check ((type)::text = ANY ((ARRAY ['REST'::character varying, 'SOAP'::character varying])::text[])),
    meta              jsonb                                               not null,
    owner_id          varchar(50)                                         not null
        references public.users (racf_id)
            on delete cascade,
    created_at        timestamp with time zone default CURRENT_TIMESTAMP,
    updated_at        timestamp with time zone default CURRENT_TIMESTAMP,
    request_template  jsonb                                               not null,
    response_template jsonb                                               not null
);
CREATE INDEX idx_projects_meta_base_url ON projects USING GIN ((meta -> 'baseUrl'));
alter table public.projects
    owner to postgres;

create index idx_projects_owner_id
    on public.projects (owner_id);

create index idx_projects_type
    on public.projects (type);

create index idx_projects_meta
    on public.projects using gin (meta);



-- Create updated_at trigger function
CREATE
OR
REPLACE
FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Create triggers for updated_at
DROP TRIGGER IF EXISTS update_users_updated_at ON users;
CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE
    ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_projects_updated_at ON projects;
CREATE TRIGGER update_projects_updated_at
    BEFORE UPDATE
    ON projects
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();



CREATE TRIGGER update_projects_updated_at
    BEFORE UPDATE
    ON projects
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Create bulk_executions table
CREATE TABLE IF NOT EXISTS bulk_executions
(
    id              UUID PRIMARY KEY         DEFAULT uuid_generate_v4(),
    project_id      UUID        NOT NULL,
    owner_id        VARCHAR(50) NOT NULL,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    total_rows      INTEGER     NOT NULL     DEFAULT 0,
    processed_rows  INTEGER     NOT NULL     DEFAULT 0,
    successful_rows INTEGER     NOT NULL     DEFAULT 0,
    failed_rows     INTEGER     NOT NULL     DEFAULT 0,
    results         JSONB,
    error_details   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id) REFERENCES users (user_id) ON DELETE CASCADE
);

-- Create indexes for bulk_executions
CREATE INDEX IF NOT EXISTS idx_bulk_executions_project_id ON bulk_executions (project_id);
CREATE INDEX IF NOT EXISTS idx_bulk_executions_owner_id ON bulk_executions (owner_id);
CREATE INDEX IF NOT EXISTS idx_bulk_executions_status ON bulk_executions (status);
CREATE INDEX IF NOT EXISTS idx_bulk_executions_created_at ON bulk_executions (created_at);

-- Create trigger for bulk_executions updated_at
DROP TRIGGER IF EXISTS update_bulk_executions_updated_at ON bulk_executions;
CREATE TRIGGER update_bulk_executions_updated_at
    BEFORE UPDATE
    ON bulk_executions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();


-- ##############

 DROP TRIGGER IF EXISTS update_projects_updated_at ON projects;
CREATE TRIGGER update_projects_updated_at
    BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

 -- Create job_executions table for persistent job queue
 CREATE TABLE IF NOT EXISTS job_executions (
        id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
        job_type VARCHAR(50) NOT NULL CHECK (job_type IN ('BULK_EXECUTION', 'TEST_GENERATION', 'TEMPLATE_PROCESSING')),
        execution_id VARCHAR(50) UNIQUE NOT NULL,
        status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'RETRY_SCHEDULED')),
        owner_id VARCHAR(50) NOT NULL,
        job_payload JSONB NOT NULL,
        retry_count INTEGER NOT NULL DEFAULT 0,
        max_retries INTEGER NOT NULL DEFAULT 3,
        next_retry_at TIMESTAMP WITH TIME ZONE,
        started_at TIMESTAMP WITH TIME ZONE,
        completed_at TIMESTAMP WITH TIME ZONE,
        error_message TEXT,
        error_details JSONB,
        progress_info JSONB,
        created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (owner_id) REFERENCES users(user_id) ON DELETE CASCADE
    );

-- Create indexes for job_executions
CREATE INDEX IF NOT EXISTS idx_job_executions_execution_id ON job_executions(execution_id);
CREATE INDEX IF NOT EXISTS idx_job_executions_owner_id ON job_executions(owner_id);
CREATE INDEX IF NOT EXISTS idx_job_executions_status ON job_executions(status);
CREATE INDEX IF NOT EXISTS idx_job_executions_job_type ON job_executions(job_type);
CREATE INDEX IF NOT EXISTS idx_job_executions_retry_schedule ON job_executions(status, next_retry_at) WHERE status = 'RETRY_SCHEDULED';
CREATE INDEX IF NOT EXISTS idx_job_executions_created_at ON job_executions(created_at);

-- Create trigger for job_executions updated_at
DROP TRIGGER IF EXISTS update_job_executions_updated_at ON job_executions;
CREATE TRIGGER update_job_executions_updated_at
        BEFORE UPDATE ON job_executions
                     FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();