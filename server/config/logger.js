// ============================================================
// config/logger.js — Winston structured logger
// ============================================================
'use strict';

const { createLogger, format, transports } = require('winston');
const path = require('path');
const fs   = require('fs');

// Ensure logs directory exists
const logDir = path.join(__dirname, '..', 'logs');
if (!fs.existsSync(logDir)) fs.mkdirSync(logDir, { recursive: true });

const logger = createLogger({
    level: process.env.LOG_LEVEL || 'info',
    format: format.combine(
        format.timestamp({ format: 'YYYY-MM-DD HH:mm:ss' }),
        format.errors({ stack: true }),
        format.json()
    ),
    transports: [
        // Rotating file transport for persistent logs
        new transports.File({
            filename: path.join(logDir, 'error.log'),
            level:    'error',
            maxsize:  10 * 1024 * 1024,  // 10 MB
            maxFiles: 5,
        }),
        new transports.File({
            filename: path.join(logDir, 'server.log'),
            maxsize:  20 * 1024 * 1024,  // 20 MB
            maxFiles: 10,
        }),
    ],
});

// Pretty-print to console in development
if (process.env.NODE_ENV !== 'production') {
    logger.add(new transports.Console({
        format: format.combine(
            format.colorize(),
            format.printf(({ timestamp, level, message, ...meta }) => {
                const extra = Object.keys(meta).length ? JSON.stringify(meta) : '';
                return `[${timestamp}] ${level}: ${message} ${extra}`;
            })
        ),
    }));
}

module.exports = logger;
