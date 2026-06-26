// ============================================================
// middlewares/security.js — Helmet, CORS, Rate limiting, XSS
// ============================================================
'use strict';

const helmet      = require('helmet');
const cors        = require('cors');
const rateLimit   = require('express-rate-limit');
const xss         = require('xss');
const logger      = require('../config/logger');

// ── CORS ─────────────────────────────────────────────────────
const allowedOrigins = (process.env.CORS_ORIGINS || '')
    .split(',')
    .map(s => s.trim())
    .filter(Boolean);

const corsOptions = {
    origin(origin, callback) {
        if (!origin || allowedOrigins.includes(origin)) {
            callback(null, true);
        } else {
            callback(new Error(`CORS blocked: ${origin}`));
        }
    },
    methods:     ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
    allowedHeaders: ['Content-Type', 'Authorization'],
    credentials: true,
    maxAge:      86400,
};

// ── Rate Limiter ──────────────────────────────────────────────
const limiter = rateLimit({
    windowMs: parseInt(process.env.RATE_LIMIT_WINDOW_MS) || 15 * 60 * 1000,
    max:      parseInt(process.env.RATE_LIMIT_MAX)        || 100,
    standardHeaders: true,
    legacyHeaders:   false,
    handler(req, res) {
        logger.warn('Rate limit exceeded', { ip: req.ip, path: req.path });
        res.status(429).json({ success: false, message: 'Too many requests. Slow down.' });
    },
});

// Stricter limiter for auth endpoints
const authLimiter = rateLimit({
    windowMs: 15 * 60 * 1000, // 15 minutes
    max:      10,
    handler(req, res) {
        logger.warn('Auth rate limit exceeded', { ip: req.ip });
        res.status(429).json({ success: false, message: 'Too many login attempts.' });
    },
});

// ── XSS Sanitiser middleware ──────────────────────────────────
function sanitiseBody(req, _res, next) {
    if (req.body && typeof req.body === 'object') {
        req.body = deepSanitise(req.body);
    }
    next();
}

function deepSanitise(obj) {
    if (typeof obj === 'string') return xss(obj);
    if (Array.isArray(obj)) return obj.map(deepSanitise);
    if (obj !== null && typeof obj === 'object') {
        return Object.fromEntries(
            Object.entries(obj).map(([k, v]) => [k, deepSanitise(v)])
        );
    }
    return obj;
}

// ── Helmet config ─────────────────────────────────────────────
const helmetConfig = helmet({
    contentSecurityPolicy: {
        directives: {
            defaultSrc:  ["'self'"],
            scriptSrc:   ["'self'"],
            styleSrc:    ["'self'", "'unsafe-inline'"],
            imgSrc:      ["'self'", 'data:', 'blob:'],
            connectSrc:  ["'self'", 'wss:', 'https:'],
            mediaSrc:    ["'self'", 'blob:'],
        },
    },
    hsts: { maxAge: 31536000, includeSubDomains: true, preload: true },
    frameguard:    { action: 'deny' },
    xssFilter:     true,
    noSniff:       true,
    hidePoweredBy: true,
});

module.exports = { corsOptions, cors, limiter, authLimiter, sanitiseBody, helmetConfig };
