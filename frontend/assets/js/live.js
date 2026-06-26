// ============================================================
// assets/js/live.js — WebRTC viewer + Socket.IO viewer namespace
// ============================================================
'use strict';

requireAuth();
initSidebar();
initToasts();

// ── State ─────────────────────────────────────────────────
let socket            = null;
let peerConnection    = null;
let selectedDevice    = null;
let isStreaming       = false;
let audioMuted        = false;
let remoteStream      = null;
let cameraIsMain      = false;

// ── DOM ───────────────────────────────────────────────────
const deviceSelect    = document.getElementById('deviceSelect');
const liveStatus      = document.getElementById('liveStatus');
const screenVideo     = document.getElementById('screenVideo');
const screenPlaceholder = document.getElementById('screenPlaceholder');
const cameraVideo     = document.getElementById('cameraVideo');
const cameraPlaceholder = document.getElementById('cameraPlaceholder');
const liveBadge       = document.getElementById('liveBadge');
const startStreamBtn  = document.getElementById('startStreamBtn');
const stopStreamBtn   = document.getElementById('stopStreamBtn');
const muteAudioBtn    = document.getElementById('muteAudioBtn');
const fullscreenBtn   = document.getElementById('fullscreenBtn');
const toggleCamBtn    = document.getElementById('toggleCamBtn');
const flipCamBtn      = document.getElementById('flipCamBtn');

// ── Load devices into select ──────────────────────────────
async function loadDeviceOptions() {
    const res = await apiFetch('/api/devices');
    if (!res) return;
    const data = await res.json();
    if (!data.success) return;

    deviceSelect.innerHTML = '<option value="">Select device…</option>';
    data.data.forEach(dev => {
        const opt = document.createElement('option');
        opt.value = dev.device_token;
        opt.textContent = `${dev.device_name} (${dev.is_online ? '🟢 Online' : '🔴 Offline'})`;
        deviceSelect.appendChild(opt);

        // Populate info panel
        if (dev.device_token === new URLSearchParams(location.search).get('device')) {
            deviceSelect.value = dev.device_token;
            updateInfoPanel(dev);
        }
    });
}

function updateInfoPanel(dev) {
    document.getElementById('infoName').textContent    = dev.device_name || '—';
    document.getElementById('infoBattery').textContent = `${formatBattery(dev.battery_level)} ${dev.is_charging ? '⚡' : ''}`;
    document.getElementById('infoNetwork').textContent = formatNetwork(dev.network_type);
    document.getElementById('infoAndroid').textContent = dev.android_version || '—';
    document.getElementById('infoIp').textContent      = dev.public_ip || '—';
    document.getElementById('infoLocation').textContent =
        dev.latitude != null ? `${dev.latitude.toFixed(5)}, ${dev.longitude.toFixed(5)}` : '—';
}

// ── Connect to signaling server ───────────────────────────
function connectSignaling() {
    if (socket) { socket.disconnect(); }

    const wsUrl = serverUrl().replace('https://', 'wss://').replace('http://', 'ws://');

    socket = io(`${wsUrl}/viewer`, {
        auth: { accessToken: localStorage.getItem('rm_access_token') },
        transports: ['websocket'],
    });

    socket.on('connect', () => {
        setStatus('Connected', 'chip-green');
        showToast('success', 'Signaling', 'Connected to server.');
    });

    socket.on('disconnect', () => {
        setStatus('Disconnected', 'chip-red');
        stopStream();
    });

    socket.on('connect_error', (err) => {
        setStatus('Error', 'chip-red');
        showToast('error', 'Connection', err.message);
    });

    // ── WebRTC signals ─────────────────────────────────────
    socket.on('webrtc:offer', async ({ sdp }) => {
        await ensurePeerConnection();
        await peerConnection.setRemoteDescription(new RTCSessionDescription(sdp));
        const answer = await peerConnection.createAnswer();
        await peerConnection.setLocalDescription(answer);
        socket.emit('webrtc:answer', {
            sdp:         peerConnection.localDescription,
            deviceToken: selectedDevice,
        });
    });

    socket.on('webrtc:ice', ({ candidate }) => {
        if (candidate && peerConnection) {
            peerConnection.addIceCandidate(new RTCIceCandidate(candidate)).catch(() => {});
        }
    });

    // ── Device status updates ──────────────────────────────
    socket.on('status:update', (data) => {
        if (data.deviceToken !== selectedDevice) return;
        updateInfoPanel(data);
    });

    socket.on('device:offline', ({ deviceToken }) => {
        if (deviceToken !== selectedDevice) return;
        showToast('error', 'Device Offline', 'The device disconnected.');
        stopStream();
    });
}

// ── WebRTC PeerConnection ─────────────────────────────────
async function ensurePeerConnection() {
    if (peerConnection) { peerConnection.close(); }

    peerConnection = new RTCPeerConnection({
        iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
        sdpSemantics: 'unified-plan',
    });

    peerConnection.onicecandidate = ({ candidate }) => {
        if (candidate) {
            socket.emit('webrtc:ice', { candidate, deviceToken: selectedDevice });
        }
    };

    peerConnection.ontrack = (event) => {
        const [stream] = event.streams;
        if (!stream) return;
        remoteStream = stream;

        updateVideoLayout();

        const videoTracks = stream.getVideoTracks();
        if (videoTracks.length > 0) {
            screenVideo.style.display = '';
            screenPlaceholder.style.display = 'none';
            liveBadge.style.display = '';
            screenVideo.play().catch(e => console.error("Screen play failed", e));
        }

        if (videoTracks.length > 1) {
            cameraVideo.style.display = '';
            cameraPlaceholder.style.display = 'none';
            cameraVideo.play().catch(e => console.error("Camera play failed", e));
        }

        // Bind audio
        const audioTracks = stream.getAudioTracks();
        if (audioTracks.length > 0 && !audioMuted) {
            screenVideo.muted = false;
        }
    };

    peerConnection.onconnectionstatechange = () => {
        const state = peerConnection.connectionState;
        setStatus(capitalise(state), stateChipClass(state));
        if (state === 'failed' || state === 'disconnected') stopStream();
    };
}

// ── Start / Stop stream ───────────────────────────────────
startStreamBtn.addEventListener('click', () => {
    const token = deviceSelect.value;
    if (!token) { showToast('error', 'No Device', 'Please select a device first.'); return; }
    selectedDevice = token;

    if (!socket || !socket.connected) {
        connectSignaling();
        setTimeout(startWatching, 1500);
    } else {
        startWatching();
    }
});

function startWatching() {
    socket.emit('watch:device', selectedDevice);
    isStreaming = true;
    startStreamBtn.style.display = 'none';
    stopStreamBtn.style.display  = '';
    setStatus('Requesting stream…', 'chip-yellow');
}

stopStreamBtn.addEventListener('click', stopStream);

function stopStream() {
    if (peerConnection) { peerConnection.close(); peerConnection = null; }
    screenVideo.srcObject = null;
    screenVideo.style.display = 'none';
    screenPlaceholder.style.display = '';
    liveBadge.style.display = 'none';
    startStreamBtn.style.display = '';
    stopStreamBtn.style.display  = 'none';
    isStreaming = false;
    setStatus('Disconnected', 'chip-gray');
}

// ── Controls ──────────────────────────────────────────────
if (toggleCamBtn) {
    toggleCamBtn.addEventListener('click', () => {
        cameraIsMain = !cameraIsMain;
        updateVideoLayout();
    });
}

if (flipCamBtn) {
    flipCamBtn.addEventListener('click', () => {
        if (!selectedDevice || !socket) return;
        socket.emit('webrtc:switch-camera', { deviceToken: selectedDevice });
    });
}

muteAudioBtn.addEventListener('click', () => {
    audioMuted = !audioMuted;
    if (screenVideo.srcObject) screenVideo.muted = audioMuted;
    muteAudioBtn.textContent = audioMuted ? '🔈 Unmute' : '🔇 Mute';
});

fullscreenBtn.addEventListener('click', () => {
    const container = document.getElementById('screenContainer');
    if (document.fullscreenElement) document.exitFullscreen();
    else container.requestFullscreen?.();
});

// ── Helpers ───────────────────────────────────────────────
function updateVideoLayout() {
    if (!remoteStream) return;
    const videoTracks = remoteStream.getVideoTracks();
    if (videoTracks.length === 0) return;

    if (cameraIsMain && videoTracks.length > 1) {
        screenVideo.srcObject = new MediaStream([videoTracks[1]]);
        cameraVideo.srcObject = new MediaStream([videoTracks[0]]);
    } else {
        screenVideo.srcObject = new MediaStream([videoTracks[0]]);
        if (videoTracks.length > 1) {
            cameraVideo.srcObject = new MediaStream([videoTracks[1]]);
        }
    }
}
function setStatus(text, cls) {
    liveStatus.className  = `chip ${cls}`;
    liveStatus.textContent = text;
}

function stateChipClass(state) {
    const map = { connected: 'chip-green', connecting: 'chip-yellow', failed: 'chip-red', disconnected: 'chip-red' };
    return map[state] || 'chip-gray';
}

function capitalise(str) { return str.charAt(0).toUpperCase() + str.slice(1); }

// ── Init ──────────────────────────────────────────────────
loadDeviceOptions();
connectSignaling();

// Auto-start if URL has ?device=
const preselect = new URLSearchParams(location.search).get('device');
if (preselect) {
    deviceSelect.value = preselect;
    selectedDevice = preselect;
    setTimeout(startWatching, 2000);
}
