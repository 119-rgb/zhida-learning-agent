CREATE TABLE IF NOT EXISTS conversation (
    id VARCHAR(100) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    title VARCHAR(200) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE IF NOT EXISTS research_task (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(100) NOT NULL,
    question TEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    error_code VARCHAR(100),
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6),
    CONSTRAINT fk_task_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id)
);
CREATE TABLE IF NOT EXISTS research_task_event (
    task_id VARCHAR(36) NOT NULL,
    event_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    data_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (task_id, event_id),
    CONSTRAINT fk_event_task FOREIGN KEY (task_id) REFERENCES research_task(id)
);
CREATE TABLE IF NOT EXISTS chat_message (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id)
);
CREATE TABLE IF NOT EXISTS conversation_summary (
    conversation_id VARCHAR(100) PRIMARY KEY,
    content LONGTEXT NOT NULL,
    covered_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_summary_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS user_memory (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    content VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE IF NOT EXISTS task_memory_usage (
    task_id VARCHAR(36) NOT NULL,
    memory_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (task_id,memory_id),
    CONSTRAINT fk_memory_usage_task FOREIGN KEY (task_id) REFERENCES research_task(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS user_account (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    is_guest BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE IF NOT EXISTS guest_origin (
    user_id VARCHAR(36) PRIMARY KEY,
    origin_hash VARCHAR(64) NOT NULL
);
CREATE TABLE IF NOT EXISTS memory_settings (
    user_id VARCHAR(100) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE IF NOT EXISTS daily_usage (
    user_id VARCHAR(100) NOT NULL,
    usage_date DATE NOT NULL,
    used_count INT NOT NULL,
    PRIMARY KEY (user_id,usage_date)
);
CREATE TABLE IF NOT EXISTS knowledge_base (
    id VARCHAR(50) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY(user_id,id)
);
CREATE TABLE IF NOT EXISTS model_usage (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    outcome VARCHAR(30) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    FOREIGN KEY (task_id) REFERENCES research_task(id) ON DELETE CASCADE
);
