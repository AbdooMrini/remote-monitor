// ============================================================
// middlewares/auth.js — JWT authentication middleware (PostgreSQL)
// ============================================================
'use strict';

const jwt    = require('jsonwebtoken');
const crypto = require('crypto');
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

        // Check token isn't revoked
        const result = await query(
            'SELECT id FROM sessions WHERE token_hash = $1 AND expires_at > NOW()',
            [hashToken(token)]
        );
        if (result.rows.length === 0) {
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
 * Admin role guard — must follow authenticateToken.
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
    const result = await query(
        'SELECT d.*, u.is_active FROM devices d JOIN users u ON d.user_id = u.id WHERE d.device_token = $1',
        [deviceToken]
    );
    if (result.rows.length === 0 || !result.rows[0].is_active) return null;
    return result.rows[0];
}

/**
 * SHA-256 hash of token for DB storage.
 */
function hashToken(token) {
    return crypto.createHash('sha256').update(token).digest('hex');
}

module.exports = { authenticateToken, requireAdmin, authenticateDevice, hashToken };
