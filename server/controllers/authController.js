// ============================================================
// controllers/authController.js — Login, logout, refresh
// ============================================================
'use strict';

const bcrypt = require('bcryptjs');
const jwt    = require('jsonwebtoken');
const { v4: uuidv4 } = require('uuid');
const { body, validationResult } = require('express-validator');
const { query, transaction } = require('../config/database');
const { hashToken }          = require('../middlewares/auth');
const logger                 = require('../config/logger');

// ── Validators ────────────────────────────────────────────────
const loginValidators = [
    body('email')
        .isEmail().withMessage('Valid email required.')
        .normalizeEmail(),
    body('password')
        .isLength({ min: 6 }).withMessage('Password must be at least 6 characters.'),
];

// ── Helpers ───────────────────────────────────────────────────
function signAccessToken(user) {
    return jwt.sign(
        { id: user.id, email: user.email, role: user.role },
        process.env.JWT_SECRET,
        { expiresIn: process.env.JWT_EXPIRES_IN || '24h' }
    );
}

function signRefreshToken(userId) {
    return jwt.sign(
        { id: userId, type: 'refresh' },
        process.env.JWT_REFRESH_SECRET,
        { expiresIn: process.env.JWT_REFRESH_EXPIRES_IN || '7d' }
    );
}

// ── POST /auth/login ──────────────────────────────────────────
async function login(req, res) {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
        return res.status(400).json({ success: false, errors: errors.array() });
    }

    const { email, password } = req.body;

    try {
        const [rows] = await query('SELECT * FROM users WHERE email = ? AND is_active = 1', [email]);
        if (rows.length === 0) {
            return res.status(401).json({ success: false, message: 'Invalid credentials.' });
        }

        const user = rows[0];
        const valid = await bcrypt.compare(password, user.password_hash);
        if (!valid) {
            logger.warn('Failed login attempt', { email, ip: req.ip });
            return res.status(401).json({ success: false, message: 'Invalid credentials.' });
        }

        const accessToken  = signAccessToken(user);
        const refreshToken = signRefreshToken(user.id);

        // Persist session (store hash, not raw token)
        await transaction(async (conn) => {
            await conn.execute(
                `INSERT INTO sessions (user_id, token_hash, ip_address, user_agent, expires_at)
                 VALUES (?, ?, ?, ?, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 24 HOUR))`,
                [user.id, hashToken(accessToken), req.ip, req.headers['user-agent'] || '']
            );
            await conn.execute(
                'UPDATE users SET last_login = UTC_TIMESTAMP() WHERE id = ?',
                [user.id]
            );
        });

        logger.info('User logged in', { userId: user.id, email: user.email, ip: req.ip });

        return res.json({
            success: true,
            data: {
                accessToken,
                refreshToken,
                expiresIn: process.env.JWT_EXPIRES_IN || '24h',
                user: {
                    id:       user.id,
                    email:    user.email,
                    fullName: user.full_name,
                    role:     user.role,
                },
            },
        });
    } catch (err) {
        logger.error('Login error', { error: err.message });
        return res.status(500).json({ success: false, message: 'Server error.' });
    }
}

// ── POST /auth/logout ─────────────────────────────────────────
async function logout(req, res) {
    try {
        await query('DELETE FROM sessions WHERE token_hash = ?', [hashToken(req.token)]);
        logger.info('User logged out', { userId: req.user.id });
        return res.json({ success: true, message: 'Logged out.' });
    } catch (err) {
        logger.error('Logout error', { error: err.message });
        return res.status(500).json({ success: false, message: 'Server error.' });
    }
}

// ── POST /auth/refresh ────────────────────────────────────────
async function refresh(req, res) {
    const { refreshToken } = req.body;
    if (!refreshToken) {
        return res.status(400).json({ success: false, message: 'Refresh token required.' });
    }

    try {
        const decoded = jwt.verify(refreshToken, process.env.JWT_REFRESH_SECRET);
        if (decoded.type !== 'refresh') throw new Error('Not a refresh token.');

        const [rows] = await query(
            'SELECT * FROM users WHERE id = ? AND is_active = 1',
            [decoded.id]
        );
        if (rows.length === 0) {
            return res.status(401).json({ success: false, message: 'User not found.' });
        }

        const user         = rows[0];
        const newAccess    = signAccessToken(user);
        const newRefresh   = signRefreshToken(user.id);

        await query(
            `INSERT INTO sessions (user_id, token_hash, ip_address, user_agent, expires_at)
             VALUES (?, ?, ?, ?, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 24 HOUR))`,
            [user.id, hashToken(newAccess), req.ip, req.headers['user-agent'] || '']
        );

        return res.json({
            success: true,
            data: { accessToken: newAccess, refreshToken: newRefresh },
        });
    } catch (err) {
        return res.status(401).json({ success: false, message: 'Invalid refresh token.' });
    }
}

// ── POST /auth/change-password ────────────────────────────────
async function changePassword(req, res) {
    const { currentPassword, newPassword } = req.body;
    if (!currentPassword || !newPassword || newPassword.length < 8) {
        return res.status(400).json({ success: false, message: 'New password must be at least 8 characters.' });
    }

    try {
        const [rows] = await query('SELECT password_hash FROM users WHERE id = ?', [req.user.id]);
        const valid  = await bcrypt.compare(currentPassword, rows[0].password_hash);
        if (!valid) {
            return res.status(401).json({ success: false, message: 'Current password incorrect.' });
        }

        const hash = await bcrypt.hash(newPassword, 12);
        await query('UPDATE users SET password_hash = ? WHERE id = ?', [hash, req.user.id]);

        // Invalidate all existing sessions except current
        await query(
            'DELETE FROM sessions WHERE user_id = ? AND token_hash != ?',
            [req.user.id, hashToken(req.token)]
        );

        logger.info('Password changed', { userId: req.user.id });
        return res.json({ success: true, message: 'Password updated.' });
    } catch (err) {
        logger.error('Change password error', { error: err.message });
        return res.status(500).json({ success: false, message: 'Server error.' });
    }
}

module.exports = { login, logout, refresh, changePassword, loginValidators };
