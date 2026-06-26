// ============================================================
// config/database.js — PostgreSQL connection pool with pg
// Compatible with Render's free PostgreSQL service
// ============================================================
'use strict';

const { Pool } = require('pg');
const logger   = require('./logger');

let pool;

/**
 * Initialize the PostgreSQL connection pool.
 * Called once at server startup.
 * Render provides DATABASE_URL automatically when you attach a PostgreSQL DB.
 */
async function initPool() {
    const connectionConfig = process.env.DATABASE_URL
        ? {
            // Render / Heroku style — single connection string
            connectionString: process.env.DATABASE_URL,
            ssl: { rejectUnauthorized: false }, // Required on Render
          }
        : {
            // Manual config (local dev or external DB)
            host:     process.env.DB_HOST     || 'localhost',
            port:     parseInt(process.env.DB_PORT) || 5432,
            user:     process.env.DB_USER,
            password: process.env.DB_PASSWORD,
            database: process.env.DB_NAME,
            ssl:      process.env.DB_SSL === 'true' ? { rejectUnauthorized: false } : false,
          };

    pool = new Pool({
        ...connectionConfig,
        max:              parseInt(process.env.DB_POOL_SIZE) || 5,
        idleTimeoutMillis: 30000,
        connectionTimeoutMillis: 5000,
    });

    // Verify connection on startup
    try {
        const client = await pool.connect();
        logger.info('✅  PostgreSQL pool initialised');
        client.release();
    } catch (err) {
        logger.error('❌  PostgreSQL pool failed to initialise', { error: err.message });
        process.exit(1);
    }

    // Log unexpected pool errors (don't crash server)
    pool.on('error', (err) => {
        logger.error('PostgreSQL pool error', { error: err.message });
    });

    return pool;
}

/**
 * Returns the shared pool instance.
 */
function getPool() {
    if (!pool) throw new Error('Database pool has not been initialised yet.');
    return pool;
}

/**
 * Execute a parameterised query.
 * PostgreSQL uses $1, $2, ... placeholders.
 *
 * @param {string} sql
 * @param {Array}  params
 * @returns {Promise<QueryResult>}
 */
async function query(sql, params = []) {
    return getPool().query(sql, params);
}

/**
 * Run multiple queries inside a single transaction.
 * @param {Function} callback  async fn that receives a pg client
 */
async function transaction(callback) {
    const client = await getPool().connect();
    try {
        await client.query('BEGIN');
        const result = await callback(client);
        await client.query('COMMIT');
        return result;
    } catch (err) {
        await client.query('ROLLBACK');
        throw err;
    } finally {
        client.release();
    }
}

module.exports = { initPool, getPool, query, transaction };
