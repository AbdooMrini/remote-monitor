// ============================================================
// assets/js/location.js — GPS map with Leaflet + live Socket.IO updates
// ============================================================
'use strict';

requireAuth();
initSidebar();
initToasts();

// ── Leaflet map ───────────────────────────────────────────
const map = L.map('map', {
    center: [20, 0],
    zoom: 3,
    zoomControl: true,
});

// Dark-themed tile layer (CartoDB Dark Matter)
L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
    attribution: '© OpenStreetMap contributors © CARTO',
    subdomains: 'abcd',
    maxZoom: 19,
}).addTo(map);

// Custom green marker
const greenIcon = L.divIcon({
    className: '',
    html: `<div style="
        width:24px;height:24px;
        background:#2E7D32;
        border:3px solid #43A047;
        border-radius:50% 50% 50% 0;
        transform:rotate(-45deg);
        box-shadow:0 2px 8px rgba(46,125,50,.6);
    "></div>`,
    iconSize: [24, 24],
    iconAnchor: [12, 24],
});

let marker   = null;
let polyline = L.polyline([], { color: '#2E7D32', weight: 3, opacity: .7 }).addTo(map);
let pathCoords = [];

// ── UI references ─────────────────────────────────────────
const deviceSelect = document.getElementById('deviceSelect');
const statLat      = document.getElementById('statLat');
const statLng      = document.getElementById('statLng');
const statUpdated  = document.getElementById('statUpdated');
const statSpeed    = document.getElementById('statSpeed');

let selectedDevice = new URLSearchParams(location.search).get('device') || '';
let socket         = null;

// ── Populate device list ──────────────────────────────────
async function loadDevices() {
    const res = await apiFetch('/api/devices');
    if (!res) return;
    const data = await res.json();
    if (!data.success) return;

    deviceSelect.innerHTML = '<option value="">Select device…</option>';
    data.data.forEach(dev => {
        const opt = document.createElement('option');
        opt.value = dev.device_token;
        opt.textContent = `${dev.device_name} (${dev.is_online ? '🟢' : '🔴'})`;
        if (dev.device_token === selectedDevice) opt.selected = true;
        deviceSelect.appendChild(opt);
    });

    if (selectedDevice) await loadHistory();
}

// ── Load location history from REST ──────────────────────
async function loadHistory() {
    const token = deviceSelect.value || selectedDevice;
    if (!token) return;

    // Resolve device ID from devices list
    const res = await apiFetch('/api/devices');
    if (!res) return;
    const data = await res.json();
    const dev  = data.data?.find(d => d.device_token === token);
    if (!dev)  return;

    const histRes = await apiFetch(`/api/devices/${dev.id}/locations?limit=200`);
    if (!histRes) return;
    const histData = await histRes.json();
    if (!histData.success) return;

    const locs = histData.data;
    if (!locs.length) return;

    pathCoords = locs.map(l => [l.latitude, l.longitude]);
    polyline.setLatLngs(pathCoords);

    // Latest point
    const latest = locs[0];
    placeMarker(latest.latitude, latest.longitude, dev.device_name, latest.recorded_at);
    updateStats(latest);
    map.fitBounds(polyline.getBounds(), { padding: [40, 40] });
}

// ── Marker ────────────────────────────────────────────────
function placeMarker(lat, lng, name, ts) {
    if (marker) marker.setLatLng([lat, lng]);
    else {
        marker = L.marker([lat, lng], { icon: greenIcon }).addTo(map);
    }
    marker.bindPopup(`<strong>${escapeHtml(name)}</strong><br>${lat.toFixed(6)}, ${lng.toFixed(6)}<br><small>${formatTimestamp(ts)}</small>`);
}

// ── Stats strip ───────────────────────────────────────────
function updateStats(loc) {
    statLat.textContent     = loc.latitude.toFixed(6);
    statLng.textContent     = loc.longitude.toFixed(6);
    statUpdated.textContent = timeAgo(loc.recorded_at);
    statSpeed.textContent   = loc.speed != null ? loc.speed.toFixed(1) : '—';
}

// ── Live Socket.IO updates ────────────────────────────────
function connectLive() {
    const wsUrl = serverUrl().replace('https://', 'wss://').replace('http://', 'ws://');
    socket = io(`${wsUrl}/viewer`, {
        auth: { accessToken: localStorage.getItem('rm_access_token') },
        transports: ['websocket'],
    });

    socket.on('connect', () => {
        if (selectedDevice) socket.emit('watch:device', selectedDevice);
    });

    socket.on('location:update', (data) => {
        if (data.deviceToken !== selectedDevice) return;
        const { latitude: lat, longitude: lng } = data;
        pathCoords.push([lat, lng]);
        polyline.setLatLngs(pathCoords);

        const devName = deviceSelect.options[deviceSelect.selectedIndex]?.text || 'Device';
        placeMarker(lat, lng, devName, new Date().toISOString());
        updateStats({ ...data, recorded_at: new Date().toISOString() });
        map.panTo([lat, lng]);
    });
}

// ── Events ────────────────────────────────────────────────
deviceSelect.addEventListener('change', () => {
    selectedDevice = deviceSelect.value;
    pathCoords     = [];
    polyline.setLatLngs([]);
    if (marker) { marker.remove(); marker = null; }
    if (socket) { socket.emit('watch:device', selectedDevice); }
    loadHistory();
});

document.getElementById('refreshLocBtn').addEventListener('click', loadHistory);

// ── Init ──────────────────────────────────────────────────
loadDevices();
connectLive();
