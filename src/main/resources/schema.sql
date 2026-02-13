CREATE TABLE IF NOT EXISTS user_account (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'ADMIN',
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_token (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    token TEXT NOT NULL UNIQUE,
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user_account(id)
);

CREATE TABLE IF NOT EXISTS gb_device (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    device_id TEXT NOT NULL UNIQUE,
    ip TEXT NOT NULL,
    port INTEGER NOT NULL,
    transport TEXT NOT NULL DEFAULT 'UDP',
    username TEXT,
    password TEXT,
    manufacturer TEXT NOT NULL,
    channel_count INTEGER NOT NULL DEFAULT 1,
    preferred_codec TEXT NOT NULL DEFAULT 'H264',
    online INTEGER NOT NULL DEFAULT 0,
    last_seen_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS gb_channel (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_pk INTEGER NOT NULL,
    channel_no INTEGER NOT NULL,
    channel_id TEXT NOT NULL,
    name TEXT NOT NULL,
    codec TEXT NOT NULL DEFAULT 'H264',
    status TEXT NOT NULL DEFAULT 'OFFLINE',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (device_pk, channel_no),
    UNIQUE (channel_id),
    FOREIGN KEY (device_pk) REFERENCES gb_device(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS storage_policy (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    retention_days INTEGER NOT NULL DEFAULT 7,
    max_storage_gb INTEGER NOT NULL DEFAULT 100,
    auto_overwrite INTEGER NOT NULL DEFAULT 1,
    record_enabled INTEGER NOT NULL DEFAULT 1,
    record_path TEXT NOT NULL DEFAULT './data/records',
    updated_at TEXT NOT NULL
);

INSERT OR IGNORE INTO storage_policy (id, retention_days, max_storage_gb, auto_overwrite, record_enabled, record_path, updated_at)
VALUES (1, 7, 100, 1, 1, './data/records', datetime('now'));

CREATE TABLE IF NOT EXISTS record_file (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT,
    channel_id TEXT,
    file_path TEXT NOT NULL UNIQUE,
    file_size_bytes INTEGER NOT NULL,
    start_time TEXT,
    end_time TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_auth_token_token ON auth_token(token);
CREATE INDEX IF NOT EXISTS idx_auth_token_expires ON auth_token(expires_at);
CREATE INDEX IF NOT EXISTS idx_gb_device_device_id ON gb_device(device_id);
CREATE INDEX IF NOT EXISTS idx_gb_channel_device_pk ON gb_channel(device_pk);
CREATE INDEX IF NOT EXISTS idx_record_file_created_at ON record_file(created_at);
