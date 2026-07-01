// ============================================================
// services/signalingService.js — WebRTC Signaling via Socket.IO
// ============================================================
'use strict';

const logger = require('../config/logger');
const { authenticateDevice } = require('../middlewares/auth');
const { query }              = require('../config/database');
const jwt                    = require('jsonwebtoken');

/**
 * Attached to an already-created https.Server.
 * Manages two namespaces:
 *   /device  — Android app connects here (device token auth)
 *   /viewer  — Web browser connects here (JWT auth)
 */
function attachSignaling(io) {

    // ── Track connected rooms ──────────────────────────────────
    // deviceToken → socketId (device side)
    const deviceSockets = new Map();
    // deviceToken → Set<socketId> (viewer side)
    const viewerSockets = new Map();
    // deviceToken → viewer count
    const deviceViewerCount = new Map();

    // ─────────────────────────────────────────────────────────
    // DEVICE NAMESPACE — Android app
    // ─────────────────────────────────────────────────────────
    const deviceNS = io.of('/device');

    deviceNS.use(async (socket, next) => {
        const token = socket.handshake.auth.deviceToken;
        if (!token) return next(new Error('Device token missing'));

        const device = await authenticateDevice(token);
        if (!device) return next(new Error('Invalid or unauthorised device token'));

        socket.device = device;
        next();
    });

    deviceNS.on('connection', async (socket) => {
        const { device } = socket;
        logger.info('Device connected', { deviceId: device.id, name: device.device_name });

        // Register socket
        deviceSockets.set(device.device_token, socket.id);

        // Mark device online in DB
        await query('UPDATE devices SET is_online = TRUE, last_seen = NOW() WHERE id = $1', [device.id]);
        await query(
            `INSERT INTO logs (level, source, device_id, message)
             VALUES ('info', 'signaling', $1, 'Device came online')`,
            [device.id]
        );

        // Notify all viewers watching this device
        notifyViewers(device.device_token, 'device:online', {
            deviceToken: device.device_token,
            deviceName:  device.device_name,
        });

        // ── WebRTC Signaling passthrough ──────────────────────
        socket.on('webrtc:offer', (data) => {
            if (data.viewerSocketId) {
                viewerNS.to(data.viewerSocketId).emit('webrtc:offer', data);
            } else {
                relayToViewers(device.device_token, 'webrtc:offer', data);
            }
        });
        socket.on('webrtc:answer', (data) => relayToDevice(data.deviceToken, 'webrtc:answer', data));
        socket.on('webrtc:ice', (data) => {
             if (data.viewerSocketId) {
                  viewerNS.to(data.viewerSocketId).emit('webrtc:ice', data);
             } else {
                  relayToViewers(device.device_token, 'webrtc:ice', data);
             }
        });

        // ── Command relay: device ACK → viewers ─────────────
        socket.on('viewer:device:command:ack', (data) => {
            if (!data || !data.deviceToken) return;
            relayToViewers(data.deviceToken, 'viewer:device:command:ack', data);
        });

        // ── Command relay: device result → viewers ──────────
        socket.on('viewer:device:command:result', (data) => {
            if (!data || !data.deviceToken) return;
            relayToViewers(data.deviceToken, 'viewer:device:command:result', data);
        });

        // ── Device status push ────────────────────────────────
        socket.on('status:update', async (data) => {
            try {
                await query(
                    `INSERT INTO device_status
                         (device_id, battery_level, is_charging, network_type, wifi_ssid, signal_strength, public_ip, is_screen_on)
                     VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
                    [device.id, data.batteryLevel, data.isCharging, data.networkType,
                     data.wifiSsid, data.signalStrength, data.publicIp, data.isScreenOn]
                );
                await query('UPDATE devices SET last_seen = NOW() WHERE id = $1', [device.id]);
                
                const mappedData = {
                    deviceToken: device.device_token,
                    battery_level: data.batteryLevel,
                    is_charging: data.isCharging,
                    network_type: data.networkType,
                    wifi_ssid: data.wifiSsid,
                    signal_strength: data.signalStrength,
                    public_ip: data.publicIp,
                    is_screen_on: data.isScreenOn
                };
                notifyViewers(device.device_token, 'status:update', mappedData);
            } catch (e) {
                logger.error('status:update DB error', { error: e.message });
            }
        });

        // ── GPS location push ─────────────────────────────────
        socket.on('location:update', async (data) => {
            try {
                await query(
                    `INSERT INTO locations (device_id, latitude, longitude, accuracy, altitude, speed, provider)
                     VALUES ($1, $2, $3, $4, $5, $6, $7)`,
                    [device.id, data.latitude, data.longitude, data.accuracy, data.altitude, data.speed, data.provider || 'gps']
                );
                notifyViewers(device.device_token, 'location:update', { deviceToken: device.device_token, ...data });
            } catch (e) {
                logger.error('location:update DB error', { error: e.message });
            }
        });

        // ── Disconnect ────────────────────────────────────────
        socket.on('disconnect', async (reason) => {
            logger.info('Device disconnected', { deviceId: device.id, reason });
            deviceSockets.delete(device.device_token);
            await query('UPDATE devices SET is_online = FALSE WHERE id = $1', [device.id]);
            await query(
                `INSERT INTO logs (level, source, device_id, message, metadata)
                 VALUES ('info', 'signaling', $1, 'Device went offline', $2)`,
                [device.id, JSON.stringify({ reason })]
            );
            notifyViewers(device.device_token, 'device:offline', { deviceToken: device.device_token });
        });
    });

    // ─────────────────────────────────────────────────────────
    // VIEWER NAMESPACE — Web browser
    // ─────────────────────────────────────────────────────────
    const viewerNS = io.of('/viewer');

    viewerNS.use((socket, next) => {
        const token = socket.handshake.auth.accessToken;
        if (!token) return next(new Error('Access token missing'));
        try {
            const decoded  = jwt.verify(token, process.env.JWT_SECRET);
            socket.viewer  = decoded;
            next();
        } catch {
            next(new Error('Invalid access token'));
        }
    });

    viewerNS.on('connection', (socket) => {
        const { viewer } = socket;
        logger.info('Viewer connected', { userId: viewer.id });

        // ── Subscribe to a device stream ──────────────────────
        socket.on('watch:device', (deviceToken) => {
            if (!viewerSockets.has(deviceToken)) viewerSockets.set(deviceToken, new Set());
            viewerSockets.get(deviceToken).add(socket.id);
            socket.join(`room:${deviceToken}`);
            logger.info('Viewer watching device', { userId: viewer.id, deviceToken });

            // Track viewer count
            const count = viewerSockets.get(deviceToken)?.size || 0;
            deviceViewerCount.set(deviceToken, count);

            // Notify device of viewer count change
            const deviceSocketId = deviceSockets.get(deviceToken);
            if (deviceSocketId) {
                deviceNS.to(deviceSocketId).emit('viewer:count', { count });
            }

            // Request device to initiate WebRTC offer
            if (deviceSocketId) {
                deviceNS.to(deviceSocketId).emit('webrtc:request-offer', { viewerSocketId: socket.id });
            }
        });

        // ── Relay ICE candidates from viewer → device ─────────
        socket.on('webrtc:ice', (data) => relayToDevice(data.deviceToken, 'webrtc:ice', data));

        // ── Relay answer from viewer → device ─────────────────
        socket.on('webrtc:answer', (data) => relayToDevice(data.deviceToken, 'webrtc:answer', data));

        // ── Relay switch/toggle camera from viewer → device ──────────
        socket.on('webrtc:switch-camera', (data) => relayToDevice(data.deviceToken, 'webrtc:switch-camera', data));
        socket.on('webrtc:toggle-camera', (data) => relayToDevice(data.deviceToken, 'webrtc:toggle-camera', data));

        // ── Command relay: viewer → device ────────────────────
        socket.on('viewer:device:command', (data) => {
            const { deviceToken, commandId } = data;
            if (!deviceToken || !commandId) return;

            logger.info('Command relay', { command: data.command, deviceToken, viewerId: viewer.id });
            relayCommand(deviceToken, data, socket);
        });

        socket.on('disconnect', () => {
            logger.info('Viewer disconnected', { userId: viewer.id });
            viewerSockets.forEach((set, dt) => {
                if (set.delete(socket.id)) {
                    const count = set.size;
                    deviceViewerCount.set(dt, count);
                    const deviceSocketId = deviceSockets.get(dt);
                    if (deviceSocketId) {
                        deviceNS.to(deviceSocketId).emit('viewer:count', { count });
                    }
                }
                if (set.size === 0) viewerSockets.delete(dt);
            });
        });
    });

    // ── Internal relay helpers ─────────────────────────────────
    function relayToViewers(deviceToken, event, data) {
        const viewers = viewerSockets.get(deviceToken);
        if (!viewers || viewers.size === 0) return;
        viewers.forEach((sid) => viewerNS.to(sid).emit(event, data));
    }

    function relayToDevice(deviceToken, event, data) {
        const deviceSocketId = deviceSockets.get(deviceToken);
        if (!deviceSocketId) return;
        deviceNS.to(deviceSocketId).emit(event, data);
    }

    function relayCommand(deviceToken, commandData, socket) {
        const deviceSocketId = deviceSockets.get(deviceToken);
        if (!deviceSocketId) {
            socket.emit('viewer:device:command:result', {
                commandId: commandData.commandId,
                deviceToken,
                status: 'error',
                error: 'Device offline'
            });
            return false;
        }
        deviceNS.to(deviceSocketId).emit('viewer:device:command', commandData);
        return true;
    }

    function notifyViewers(deviceToken, event, data) {
        relayToViewers(deviceToken, event, data);
    }

    logger.info('WebRTC signaling service attached');
}

module.exports = { attachSignaling };
