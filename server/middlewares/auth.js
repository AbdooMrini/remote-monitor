// ============================================================
// middlewares/auth.js — JWT authentication middleware
// ============================================================
'use strict';

const jwt    = require('jsonwebtoken');
const logger = require('../config/logger');
const { query } = require('../config/database');

/**
 * Verifies the Bearer JWT in Authorization header.
 * Attaches decoded payload to req.user on success.
 */
async function authenticateToken(req, res, next) {
    const authHeader = req.headers['authorization'];
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return res.status(401).json({ success: false, message: 'Missing or malformed Authorization header.' });
    }

    const token = authHeader.split(' ')[1];

    try {
        const decoded = jwt.verify(token, process.env.JWT_SECRET);

        // Check token isn't revoked (not in sessions table anymore)
        const [rows] = await query(
            'SELECT id FROM sessions WHERE token_hash = ? AND expires_at > UTC_TIMESTAMP()',
            [hashToken(token)]
        );
        if (rows.length === 0) {
            return res.status(401).json({ success: false, message: 'Token has been revoked or expired.' });
        }

        req.user  = decoded;
        req.token = token;
        next();
    } catch (err) {
        logger.warn('JWT verification failed', { error: err.message, ip: req.ip });
        if (err.name === 'TokenExpiredError') {
            return res.status(401).json({ success: false, message: 'Token expired.' });
        }
        return res.status(403).json({ success: false, message: 'Invalid token.' });
    }
}

/**
 * Simple admin role guard — must follow authenticateToken.
 */
function requireAdmin(req, res, next) {
    if (req.user?.role !== 'admin') {
        return res.status(403).json({ success: false, message: 'Admin access required.' });
    }
    next();
}

/**
 * Device-token authenticator for Android app socket connections.
 */
async function authenticateDevice(deviceToken) {
    const [rows] = await query(
        'SELECT d.*, u.is_active FROM devices d JOIN users u ON d.user_id = u.id WHERE d.device_token = ?',
        [deviceToken]
    );
    if (rows.length === 0 || !rows[0].is_active) return null;
    return rows[0];
}

/**
 * Deterministic hash for token storage (sha256 in hex via Node crypto).
 */
function hashToken(token) {
    const crypto = require('crypto');
    return crypto.createHash('sha256').update(token).digest('hex');
}

module.exports = { authenticateToken, requireAdmin, authenticateDevice, hashToken };
