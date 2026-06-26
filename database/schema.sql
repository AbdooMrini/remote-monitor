-- ============================================
-- Remote Monitor — MySQL Database Schema
-- ============================================

CREATE DATABASE IF NOT EXISTS remote_monitor CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE remote_monitor;

-- ============================================
-- USERS TABLE
-- ============================================
CREATE TABLE IF NOT EXISTS users (
    id            INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(100),
    role          ENUM('admin','viewer') DEFAULT 'admin',
    is_active     TINYINT(1) DEFAULT 1,
    last_login    DATETIME,
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_email (email),
    INDEX idx_active (is_active)
) ENGINE=InnoDB;

-- ============================================
-- DEVICES TABLE
-- ============================================
CREATE TABLE IF NOT EXISTS devices (
    id              INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id         INT UNSIGNED NOT NULL,
    device_token    VARCHAR(64) NOT NULL UNIQUE,
    device_name     VARCHAR(100),
    device_model    VARCHAR(100),
    android_version VARCHAR(20),
    app_version     VARCHAR(20),
    fcm_token       VARCHAR(255),
    is_online       TINYINT(1) DEFAULT 0,
    last_seen       DATETIME,
    registered_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_token  (device_token),
    INDEX idx_user   (user_id),
    INDEX idx_online (is_online)
) ENGINE=InnoDB;

-- ============================================
-- DEVICE STATUS TABLE
-- ============================================
CREATE TABLE IF NOT EXISTS device_status (
    id              INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    device_id       INT UNSIGNED NOT NULL,
    battery_level   TINYINT UNSIGNED,
    is_charging     TINYINT(1) DEFAULT 0,
    network_type    ENUM('wifi','mobile','none','unknown') DEFAULT 'unknown',
    wifi_ssid       VARCHAR(100),
    signal_strength TINYINT,
    public_ip       VARCHAR(45),
    is_screen_on    TINYINT(1) DEFAULT 0,
    recorded_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE,
    INDEX idx_device   (device_id),
    INDEX idx_recorded (recorded_at)
) ENGINE=InnoDB;

-- ============================================
-- LOCATIONS TABLE
-- ============================================
CREATE TABLE IF NOT EXISTS locations (
    id          INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    device_id   INT UNSIGNED NOT NULL,
    latitude    DECIMAL(10, 8) NOT NULL,
    longitude   DECIMAL(11, 8) NOT NULL,
    accuracy    FLOAT,
    altitude    FLOAT,
    speed       FLOAT,
    provider    VARCHAR(30),
    recorded_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE,
    INDEX idx_device   (device_id),
    INDEX idx_recorded (recorded_at)
) ENGINE=InnoDB;

-- ============================================
-- SESSIONS TABLE
-- ============================================
CREATE TABLE IF NOT EXISTS sessions (
    id          INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id     INT UNSIGNED NOT NULL,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    ip_address  VARCHAR(45),
    user_agent  TEXT,
    expires_at  DATETIME NOT NULL,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_token  (token_hash),
    INDEX idx_user   (user_id),
    INDEX idx_expiry (expires_at)
) ENGINE=InnoDB;

-- ============================================
-- LOGS TABLE
-- ============================================
CREATE TABLE IF NOT EXISTS logs (
    id          INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    level       ENUM('info','warn','error','debug') DEFAULT 'info',
    source      VARCHAR(50),
    device_id   INT UNSIGNED,
    user_id     INT UNSIGNED,
    message     TEXT,
    metadata    JSON,
    ip_address  VARCHAR(45),
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE SET NULL,
    FOREIGN KEY (user_id)   REFERENCES users(id)   ON DELETE SET NULL,
    INDEX idx_level   (level),
    INDEX idx_device  (device_id),
    INDEX idx_created (created_at)
) ENGINE=InnoDB;

-- ============================================
-- SEED — Default admin (password: Admin@123)
-- Change this immediately after first login!
-- ============================================
INSERT INTO users (email, password_hash, full_name, role)
VALUES (
    'admin@monitor.local',
    '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQyCgZAQCN8r4CXlwMnXxKVmS',
    'Admin',
    'admin'
) ON DUPLICATE KEY UPDATE id = id;
