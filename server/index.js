// ============================================================
// index.js — Remote Monitor Server Entry Point
// Railway-compatible: HTTP only (Railway proxy handles HTTPS/WSS)
// Self-hosted: set USE_HTTPS=true + SSL_KEY_PATH + SSL_CERT_PATH
// ============================================================
'use strict';

require('dotenv').config();

const fs          = require('fs');
const http        = require('http');
const https       = require('https');
const express     = require('express');
const { Server }  = require('socket.io');
const morgan      = require('morgan');
const compression = require('compression');
const path        = require('path');

const logger   = require('./config/logger');
const db       = require('./config/database');
const { cors, corsOptions, limiter, sanitiseBody, helmetConfig } = require('./middlewares/security');
const { attachSignaling } = require('./services/signalingService');

const authRoutes   = require('./routes/auth');
const deviceRoutes = require('./routes/devices');

const app = express();

// ── Trust Railway / Render / Heroku proxy ─────────────────────
app.set('trust proxy', 1);

// ── Global Middleware ─────────────────────────────────────────
app.set('trust proxy', 1);
app.use(helmetConfig);
app.use(cors(corsOptions));
app.use(compression());
app.use(express.json({ limit: '1mb' }));
app.use(express.urlencoded({ extended: false, limit: '1mb' }));
app.use(sanitiseBody);
app.use(limiter);

// HTTP request logging (combined format → log file in production)
if (process.env.NODE_ENV === 'production') {
    const accessStream = fs.createWriteStream(
        path.join(__dirname, 'logs', 'access.log'),
        { flags: 'a' }
    );
    app.use(morgan('combined', { stream: accessStream }));
} else {
    app.use(morgan('dev'));
}

// ── Static files (web frontend) ───────────────────────────────
app.use(express.static(path.join(__dirname, 'public')));

// ── API Routes ────────────────────────────────────────────────
app.use('/api/auth',    authRoutes);
app.use('/api/devices', deviceRoutes);

// ── Health check (also used by Render's healthCheckPath) ──────
app.get('/health', (_req, res) => res.json({
    status:  'ok',
    ts:      new Date().toISOString(),
    uptime:  process.uptime(),
    env:     process.env.NODE_ENV,
}));

// ── Ping endpoint (for UptimeRobot) ──────────────────────────
app.get('/ping', (_req, res) => res.send('pong'));

// ── 404 catch-all ────────────────────────────────────────────
app.use((_req, res) => res.status(404).json({ success: false, message: 'Not found.' }));

// ── Global error handler ──────────────────────────────────────
app.use((err, _req, res, _next) => {
    logger.error('Unhandled error', { error: err.message, stack: err.stack });
    res.status(500).json({ success: false, message: 'Internal server error.' });
});

// ── Keep-alive self-ping (prevents Render free tier sleep) ────
// Pings /health every 14 minutes — Render sleeps after 15min idle
function startKeepAlive() {
    const selfUrl = process.env.KEEPALIVE_URL;
    if (!selfUrl) {
        logger.info('Keep-alive: KEEPALIVE_URL not set, skipping self-ping. Set it to your Render URL.');
        return;
    }

    const INTERVAL_MS = 14 * 60 * 1000; // 14 minutes

    setInterval(async () => {
        try {
            const https = selfUrl.startsWith('https') ? require('https') : require('http');
            const req = https.get(`${selfUrl}/ping`, (res) => {
                logger.info(`Keep-alive ping → ${res.statusCode}`);
            });
            req.on('error', (err) => logger.warn('Keep-alive ping failed', { error: err.message }));
            req.end();
        } catch (err) {
            logger.warn('Keep-alive error', { error: err.message });
        }
    }, INTERVAL_MS);

    logger.info(`Keep-alive: pinging ${selfUrl}/ping every 14 minutes`);
}


// ── Start Server ──────────────────────────────────────────────
async function start() {
    // Initialise DB pool first
    await db.initPool();

    let server;

    if (process.env.USE_HTTPS === 'true') {
        // ── Self-hosted HTTPS mode ─────────────────────────────
        // Set USE_HTTPS=true + SSL_KEY_PATH + SSL_CERT_PATH in .env
        const sslOptions = {
            key:  fs.readFileSync(process.env.SSL_KEY_PATH),
            cert: fs.readFileSync(process.env.SSL_CERT_PATH),
        };
        server = https.createServer(sslOptions, app);

        // HTTP → HTTPS redirect
        const httpApp = express();
        httpApp.use((req, res) => {
            res.redirect(301, `https://${req.headers.host}${req.url}`);
        });
        http.createServer(httpApp).listen(
            parseInt(process.env.HTTP_PORT) || 80,
            () => logger.info(`HTTP→HTTPS redirect on :${process.env.HTTP_PORT || 80}`)
        );
        logger.info('🔒  HTTPS mode (self-hosted)');
    } else {
        // ── HTTP mode ──────────────────────────────────────────
        // Railway / Render / Heroku manage TLS at their edge.
        // Traffic arrives here as plain HTTP — perfectly secure.
        server = http.createServer(app);
        logger.info('🌐  HTTP mode (platform proxy handles TLS)');
    }

    // ── Socket.IO ─────────────────────────────────────────────
    const io = new Server(server, {
        cors: corsOptions,
        transports: ['websocket', 'polling'],
        pingTimeout:  20000,
        pingInterval: 25000,
    });

    attachSignaling(io);

    // Render / Railway inject PORT automatically
    const PORT = parseInt(process.env.PORT) || 3000;
    server.listen(PORT, '0.0.0.0', () => {
        logger.info(`🚀  Server listening on port ${PORT}`);

        // Start keep-alive after server is up (prevents Render free tier sleep)
        startKeepAlive();
    });

    // ── Graceful shutdown ─────────────────────────────────────
    const shutdown = (signal) => {
        logger.info(`${signal} received — shutting down gracefully`);
        server.close(() => {
            logger.info('HTTP server closed');
            process.exit(0);
        });
        setTimeout(() => process.exit(1), 10000);
    };
    process.on('SIGTERM', () => shutdown('SIGTERM'));
    process.on('SIGINT',  () => shutdown('SIGINT'));
}

start().catch((err) => {
    logger.error('Fatal startup error', { error: err.message });
    process.exit(1);
});
