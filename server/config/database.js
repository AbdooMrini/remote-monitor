// ============================================================
// config/database.js — MySQL connection pool with mysql2
// ============================================================
'use strict';

const mysql = require('mysql2/promise');
const logger = require('./logger');

let pool;

/**
 * Initialize the MySQL connection pool.
 * Called once at server startup.
 */
async function initPool() {
    pool = mysql.createPool({
        host:               process.env.DB_HOST     || 'localhost',
        port:               parseInt(process.env.DB_PORT) || 3306,
        user:               process.env.DB_USER,
        password:           process.env.DB_PASSWORD,
        database:           process.env.DB_NAME,
        waitForConnections: true,
        connectionLimit:    parseInt(process.env.DB_POOL_SIZE) || 10,
        queueLimit:         0,
        timezone:           '+00:00',
        // Prevent SQL injection via prepared statements by default
        namedPlaceholders:  true,
    });

    // Verify the pool is healthy on startup
    try {
        const conn = await pool.getConnection();
        logger.info('✅  MySQL pool initialised');
        conn.release();
    } catch (err) {
        logger.error('❌  MySQL pool failed to initialise', { error: err.message });
        process.exit(1);
    }

    return pool;
}

/**
 * Returns the shared pool instance.
 * Throws if pool was never initialised.
 */
function getPool() {
    if (!pool) throw new Error('Database pool has not been initialised yet.');
    return pool;
}

/**
 * Convenience wrapper — execute a query with automatic connection management.
 * @param {string} sql
 * @param {Array|Object} params
 * @returns {Promise<[RowDataPacket[], FieldPacket[]]>}
 */
async function query(sql, params = []) {
    return getPool().execute(sql, params);
}

/**
 * Run multiple queries inside a single transaction.
 * @param {Function} callback  async fn that receives a connection
 */
async function transaction(callback) {
    const conn = await getPool().getConnection();
    await conn.beginTransaction();
    try {
        const result = await callback(conn);
        await conn.commit();
        return result;
    } catch (err) {
        await conn.rollback();
        throw err;
    } finally {
        conn.release();
    }
}

module.exports = { initPool, getPool, query, transaction };
