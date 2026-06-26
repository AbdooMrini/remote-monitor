// ============================================================
// assets/js/dashboard.js — Dashboard page logic
// ============================================================
'use strict';

requireAuth();
initSidebar();
initToasts();

const grid         = document.getElementById('devicesGrid');
const statTotal    = document.getElementById('statTotal');
const statOnline   = document.getElementById('statOnline');
const statBattery  = document.getElementById('statBattery');
const statLocs     = document.getElementById('statLocations');
const statusChip   = document.getElementById('connectionStatus');
const onlineCount  = document.getElementById('onlineCount');

let devices = [];

// ── Load devices from API ─────────────────────────────────
async function loadDevices() {
    const res = await apiFetch('/api/devices');
    if (!res) return;
    const data = await res.json();
    if (!data.success) { showToast('error', 'Error', data.message); return; }

    devices = data.data;
    renderStats();
    renderGrid();
}

function renderStats() {
    const online  = devices.filter(d => d.is_online).length;
    const batteries = devices.filter(d => d.battery_level != null).map(d => d.battery_level);
    const avgBatt   = batteries.length ? Math.round(batteries.reduce((a,b)=>a+b,0)/batteries.length) : null;

    statTotal.textContent   = devices.length;
    statOnline.textContent  = online;
    statBattery.textContent = avgBatt != null ? `${avgBatt}%` : '—';
    statLocs.textContent    = devices.filter(d => d.latitude != null).length;
    onlineCount.textContent = online;

    statusChip.className     = `chip ${online > 0 ? 'chip-green' : 'chip-gray'}`;
    statusChip.innerHTML     = `<span class="status-dot ${online > 0 ? 'online' : 'offline'}"></span>${online > 0 ? `${online} Online` : 'All Offline'}`;
}

function renderGrid() {
    grid.innerHTML = '';

    if (devices.length === 0) {
        grid.innerHTML = `
            <div class="empty-state" style="grid-column:1/-1">
                <div class="empty-state-icon">
                    <svg viewBox="0 0 64 64" fill="none"><rect x="20" y="5" width="24" height="54" rx="6" stroke="currentColor" stroke-width="2.5"/><circle cx="32" cy="49" r="2.5" fill="currentColor" opacity=".4"/></svg>
                </div>
                <div class="empty-state-title">No devices registered</div>
                <div class="empty-state-text">Install the Android app and log in to register your device.</div>
            </div>`;
        return;
    }

    devices.forEach(dev => {
        const initial   = (dev.device_name || 'D').charAt(0).toUpperCase();
        const online    = dev.is_online;
        const battLvl   = dev.battery_level ?? 0;
        const battCls   = batteryClass(battLvl);

        const card = document.createElement('div');
        card.className = 'device-card';
        card.dataset.token = dev.device_token;
        card.innerHTML = `
            <div class="device-card-header">
                <div class="device-avatar">${escapeHtml(initial)}</div>
                <div class="device-meta">
                    <div class="device-name">${escapeHtml(dev.device_name || 'Unknown')}</div>
                    <div class="device-model">${escapeHtml(dev.device_model || '—')}</div>
                    <div class="device-status-row">
                        <span class="status-dot ${online ? 'online' : 'offline'}"></span>
                        <span style="font-size:.75rem;color:var(--clr-text-muted)">${online ? 'Online' : 'Offline'} · ${timeAgo(dev.last_seen)}</span>
                    </div>
                </div>
            </div>
            <div class="device-card-body">
                <div class="device-stat">
                    <div class="device-stat-label">Battery</div>
                    <div class="device-stat-value">${formatBattery(dev.battery_level)} ${dev.is_charging ? '⚡' : ''}</div>
                    <div class="battery-bar"><div class="battery-fill ${battCls}" style="width:${battLvl}%"></div></div>
                </div>
                <div class="device-stat">
                    <div class="device-stat-label">Network</div>
                    <div class="device-stat-value">${escapeHtml(formatNetwork(dev.network_type))}</div>
                </div>
                <div class="device-stat">
                    <div class="device-stat-label">Android</div>
                    <div class="device-stat-value">${escapeHtml(dev.android_version || '—')}</div>
                </div>
                <div class="device-stat">
                    <div class="device-stat-label">IP</div>
                    <div class="device-stat-value" style="font-size:.8rem">${escapeHtml(dev.public_ip || '—')}</div>
                </div>
            </div>
            <div class="device-card-footer">
                <a href="live.html?device=${encodeURIComponent(dev.device_token)}" class="btn btn-primary btn-sm">▶ Live View</a>
                <a href="location.html?device=${encodeURIComponent(dev.device_token)}" class="btn btn-ghost btn-sm">📍 Location</a>
            </div>`;

        grid.appendChild(card);
    });
}

// ── Refresh button ────────────────────────────────────────
document.getElementById('refreshBtn').addEventListener('click', () => {
    grid.innerHTML = '<div class="stat-card skeleton" style="height:180px"></div><div class="stat-card skeleton" style="height:180px"></div>';
    loadDevices();
});

// ── Initial load + poll ───────────────────────────────────
loadDevices();
setInterval(loadDevices, 15000);
