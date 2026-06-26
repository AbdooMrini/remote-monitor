// ============================================================
// config/logger.js — Winston structured logger
// ============================================================
'use strict';

const { createLogger, format, transports } = require('winston');
const path = require('path');
const fs   = require('fs');

const logger = createLogger({
    level: process.env.LOG_LEVEL || 'info',
    format: format.combine(
        format.timestamp({ format: 'YYYY-MM-DD HH:mm:ss' }),
        format.errors({ stack: true }),
        format.json()
    ),
    transports: [
        new transports.Console({
            format: process.env.NODE_ENV === 'production' 
                ? format.json() // structured JSON for Render logs
                : format.combine(
                    format.colorize(),
                    format.printf(({ timestamp, level, message, ...meta }) => {
                        const extra = Object.keys(meta).length ? JSON.stringify(meta) : '';
                        return `[${timestamp}] ${level}: ${message} ${extra}`;
                    })
                )
        })
    ],
});

module.exports = logger;
