-- Enable UUID extension
CREATE
EXTENSION IF NOT EXISTS "uuid-ossp";

-- Create users table
CREATE TABLE IF NOT EXISTS users
(
    id            UUID PRIMARY KEY         DEFAULT uuid_generate_v4(),
    user_id       VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255)       NOT NULL,
    roles         TEXT[] DEFAULT '{"USER"}',
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Create projects table
CREATE TABLE IF NOT EXISTS projects
(
    id         UUID PRIMARY KEY         DEFAULT uuid_generate_v4(),
    name       VARCHAR(255) NOT NULL,
    type       VARCHAR(10)  NOT NULL CHECK (type IN ('REST', 'SOAP')),
    meta       JSONB        NOT NULL,
    owner_id   VARCHAR(50)  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (owner_id) REFERENCES users (user_id) ON DELETE CASCADE
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_users_user_id ON users (user_id);
CREATE INDEX IF NOT EXISTS idx_projects_owner_id ON projects (owner_id);
CREATE INDEX IF NOT EXISTS idx_projects_type ON projects (type);
CREATE INDEX IF NOT EXISTS idx_projects_meta ON projects USING GIN (meta);

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