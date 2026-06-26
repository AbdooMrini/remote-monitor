// ============================================================
// controllers/deviceController.js — Device registration & status
// ============================================================
'use strict';

const { v4: uuidv4 }         = require('uuid');
const { query, transaction } = require('../config/database');
const logger                 = require('../config/logger');

// ── GET /devices — list all devices for authenticated user ─────
async function listDevices(req, res) {
    try {
        const [devices] = await query(
            `SELECT
                d.id, d.device_token, d.device_name, d.device_model,
                d.android_version, d.app_version, d.is_online, d.last_seen,
                d.registered_at,
                ds.battery_level, ds.is_charging, ds.network_type,
                ds.public_ip, ds.wifi_ssid,
                l.latitude, l.longitude, l.recorded_at AS location_updated
             FROM devices d
             LEFT JOIN device_status ds ON ds.id = (
                 SELECT id FROM device_status
                 WHERE device_id = d.id
                 ORDER BY recorded_at DESC LIMIT 1
             )
             LEFT JOIN locations l ON l.id = (
                 SELECT id FROM locations
                 WHERE device_id = d.id
                 ORDER BY recorded_at DESC LIMIT 1
             )
             WHERE d.user_id = ?
             ORDER BY d.is_online DESC, d.last_seen DESC`,
            [req.user.id]
        );
        return res.json({ success: true, data: devices });
    } catch (err) {
        logger.error('listDevices error', { error: err.message });
        return res.status(500).json({ success: false, message: 'Server error.' });
    }
}

// ── POST /devices/register — Android app registers itself ─────
async function registerDevice(req, res) {
    const { deviceName, deviceModel, androidVersion, appVersion } = req.body;
    if (!deviceName || !deviceModel) {
        return res.status(400).json({ success: false, message: 'deviceName and deviceModel required.' });
    }

    try {
        const token = uuidv4().replace(/-/g, '');

        await query(
            `INSERT INTO devices
                (user_id, device_token, device_name, device_model, android_version, app_version)
             VALUES (?, ?, ?, ?, ?, ?)`,
            [req.user.id, token, deviceName, deviceModel, androidVersion || '', appVersion || '']
        );

        logger.info('Device registered', { userId: req.user.id, deviceName });
        return res.status(201).json({
            success: true,
            data: { deviceToken: token },
        });
    } catch (err) {
        logger.error('registerDevice error', { error: err.message });
        return res.status(500).json({ success: false, message: 'Server error.' });
    }
}

// ── DELETE /devices/:id ───────────────────────────────────────
async function deleteDevice(req, res) {
    const { id } = req.params;
    try {
        const [result] = await query(
            'DELETE FROM devices WHERE id = ? AND user_id = ?',
            [id, req.user.id]
        );
        if (result.affectedRows === 0) {
            return res.status(404).json({ success: false, message: 'Device not found.' });
        }
        return res.json({ success: true, message: 'Device deleted.' });
    } catch (err) {
        logger.error('deleteDevice error', { error: err.message });
        return res.status(500).json({ success: false, message: 'Server error.' });
    }
}

// ── POST /devices/:id/status — update device status ──────────
async function updateDeviceStatus(req, res) {
    const {
        batteryLevel, isCharging, networkType,
        wifiSsid, signalStrength, publicIp, isScreenOn,
    } = req.body;

    try {
        await query(
            `INSERT INTO device_status
                (device_id, battery_level, is_charging, network_type, wifi_ssid, signal_strength, public_ip, is_screen_on)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
            [req.params.id, batteryLevel, isCharging, networkType, wifiSsid, signalStrength, publicIp, isScreenOn]
        );
        await query(
            'UPDATE devices SET last_seen = UTC_TIMESTAMP() WHERE id = ?',
            [req.params.id]
        );
        return res.json({ success: true });
    } catch (err) {
        logger.error('updateDeviceStatus error', { error: err.message });
        return res.status(500).json({ success: false, message: 'Server error.' });
    }
}

// ── POST /devices/:id/location — store GPS reading ───────────
async function updateLocation(req, res) {
    const { latitude, longitude, accuracy, altitude, speed, provider } = req.body;
    if (latitude == null || longitude == null) {
        return res.status(400).json({ success: false, message: 'latitude and longitude required.' });
    }

    try {
        await query(
            `INSERT INTO locations
                (device_id, latitude, longitude, accuracy, altitude, speed, provider)
             VALUES (?, ?, ?, ?, ?, ?, ?)`,
            [req.params.id, latitude, longitude, accuracy, altitude, speed, provider || 'gps']
        );
        return res.json({ success: true });
    } catch (err) {
        logger.error('updateLocation error', { error: err.message });
        return res.status(500).json({ success: false, message: 'Server error.' });
    }
}

// ── GET /devices/:id/locations — location history ─────────────
async function getLocationHistory(req, res) {
    const limit  = Math.min(parseInt(req.query.limit) || 100, 500);
    const offset = parseInt(req.query.offset) || 0;

    try {
        const [rows] = await query(
            `SELECT latitude, longitude, accuracy, altitude, speed, provider, recorded_at
             FROM locations
             WHERE device_id = ?
             ORDER BY recorded_at DESC
             LIMIT ? OFFSET ?`,
            [req.params.id, limit, offset]
        );
        return res.json({ success: true, data: rows });
    } catch (err) {
        logger.error('getLocationHistory error', { error: err.message });
        return res.status(500).json({ success: false, message: 'Server error.' });
    }
}

module.exports = {
    listDevices,
    registerDevice,
    deleteDevice,
    updateDeviceStatus,
    updateLocation,
    getLocationHistory,
};
