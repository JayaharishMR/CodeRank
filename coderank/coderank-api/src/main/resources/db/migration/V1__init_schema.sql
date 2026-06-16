-- Postgres-native ENUMs enforce valid values at the database level, so invalid
-- states can never be written -- even by direct SQL or other services bypassing
-- the application layer.

CREATE TYPE submission_status AS ENUM (
    'QUEUED', 'COMPILING', 'RUNNING', 'COMPLETED', 'FAILED'
);

CREATE TYPE verdict AS ENUM (
    'ACCEPTED', 'WRONG_ANSWER', 'TIME_LIMIT_EXCEEDED',
    'MEMORY_LIMIT_EXCEEDED', 'RUNTIME_ERROR', 'COMPILATION_ERROR', 'PENDING'
);

CREATE TYPE language AS ENUM ('JAVA');

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_id VARCHAR(255) NOT NULL UNIQUE,  -- links to the Keycloak identity; UNIQUE so one SSO account maps to exactly one local user
    username VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE submissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),         -- nullable so guest (unauthenticated) submissions are allowed
    language language NOT NULL DEFAULT 'JAVA',
    source_code TEXT NOT NULL,
    stdin TEXT,
    status submission_status NOT NULL DEFAULT 'QUEUED',
    verdict verdict NOT NULL DEFAULT 'PENDING',
    stdout TEXT,
    stderr TEXT,
    execution_time_ms BIGINT,
    memory_used_kb BIGINT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Speed up "my submissions" queries for authenticated users
CREATE INDEX idx_submissions_user_id ON submissions(user_id);
-- The worker polls by status (e.g. QUEUED) to pick up the next job
CREATE INDEX idx_submissions_status ON submissions(status);
-- Most-recent-first listing on dashboards and history pages
CREATE INDEX idx_submissions_created_at ON submissions(created_at DESC);
