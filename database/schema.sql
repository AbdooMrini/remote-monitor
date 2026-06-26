-- ============================================================
-- Remote Monitor — PostgreSQL Schema
-- Compatible with Render's free PostgreSQL database
-- ============================================================

-- ── USERS TABLE ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id            SERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(100),
    role          VARCHAR(10)  NOT NULL DEFAULT 'admin' CHECK (role IN ('admin','viewer')),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_users_email  ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_active ON users(is_active);

-- ── DEVICES TABLE ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS devices (
    id              SERIAL PRIMARY KEY,
    user_id         INTEGER      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_token    VARCHAR(64)  NOT NULL UNIQUE,
    device_name     VARCHAR(100),
    device_model    VARCHAR(100),
    android_version VARCHAR(20),
    app_version     VARCHAR(20),
    fcm_token       VARCHAR(255),
    is_online       BOOLEAN      NOT NULL DEFAULT FALSE,
    last_seen       TIMESTAMPTZ,
    registered_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_devices_token  ON devices(device_token);
CREATE INDEX IF NOT EXISTS idx_devices_user   ON devices(user_id);
CREATE INDEX IF NOT EXISTS idx_devices_online ON devices(is_online);

-- ── DEVICE STATUS TABLE ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS device_status (
    id              SERIAL PRIMARY KEY,
    device_id       INTEGER     NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    battery_level   SMALLINT,
    is_charging     BOOLEAN     NOT NULL DEFAULT FALSE,
    network_type    VARCHAR(10) NOT NULL DEFAULT 'unknown' CHECK (network_type IN ('wifi','mobile','none','unknown')),
    wifi_ssid       VARCHAR(100),
    signal_strength SMALLINT,
    public_ip       VARCHAR(45),
    is_screen_on    BOOLEAN     NOT NULL DEFAULT FALSE,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_status_device   ON device_status(device_id);
CREATE INDEX IF NOT EXISTS idx_status_recorded ON device_status(recorded_at);

-- ── LOCATIONS TABLE ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS locations (
    id          SERIAL PRIMARY KEY,
    device_id   INTEGER      NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    latitude    DECIMAL(10,8) NOT NULL,
    longitude   DECIMAL(11,8) NOT NULL,
    accuracy    FLOAT,
    altitude    FLOAT,
    speed       FLOAT,
    provider    VARCHAR(30),
    recorded_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_locations_device   ON locations(device_id);
CREATE INDEX IF NOT EXISTS idx_locations_recorded ON locations(recorded_at);

-- ── SESSIONS TABLE ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sessions (
    id          SERIAL PRIMARY KEY,
    user_id     INTEGER      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    ip_address  VARCHAR(45),
    user_agent  TEXT,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sessions_token  ON sessions(token_hash);
CREATE INDEX IF NOT EXISTS idx_sessions_user   ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON sessions(expires_at);

-- ── LOGS TABLE ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS logs (
    id          SERIAL PRIMARY KEY,
    level       VARCHAR(5)   NOT NULL DEFAULT 'info' CHECK (level IN ('info','warn','error','debug')),
    source      VARCHAR(50),
    device_id   INTEGER      REFERENCES devices(id) ON DELETE SET NULL,
    user_id     INTEGER      REFERENCES users(id)   ON DELETE SET NULL,
    message     TEXT,
    metadata    JSONB,
    ip_address  VARCHAR(45),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_logs_level   ON logs(level);
CREATE INDEX IF NOT EXISTS idx_logs_device  ON logs(device_id);
CREATE INDEX IF NOT EXISTS idx_logs_created ON logs(created_at);

-- ── Auto-update updated_at trigger ───────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE OR REPLACE TRIGGER trg_devices_updated_at
    BEFORE UPDATE ON devices
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ── SEED — Default admin (password: Admin@123) ───────────────
-- Change this password immediately after first login!
INSERT INTO users (email, password_hash, full_name, role)
VALUES (
    'admin@monitor.local',
    '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCgZAQCN8r4CXlwMnXxKVmS',
    'Admin',
    'admin'
) ON CONFLICT (email) DO NOTHING;
