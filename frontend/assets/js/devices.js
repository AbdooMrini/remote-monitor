// ============================================================
// assets/js/devices.js — Device management logic
// ============================================================
'use strict';

document.addEventListener('DOMContentLoaded', () => {
    requireAuth();

    // Sidebar toggle
    const sidebarBtn = document.querySelector('.sidebarBtn');
    const sidebar = document.querySelector('.sidebar');
    if (sidebarBtn) {
        sidebarBtn.addEventListener('click', () => {
            sidebar.classList.toggle('active');
        });
    }

    // Set user name
    const user = JSON.parse(localStorage.getItem('rm_user') || '{}');
    if (user.fullName) {
        document.getElementById('navUserName').innerText = user.fullName;
    }

    // Logout handler
    document.getElementById('btnLogout').addEventListener('click', async (e) => {
        e.preventDefault();
        try {
            await apiFetch('/auth/logout', { method: 'POST' });
        } catch (e) {
            console.warn('Logout failed', e);
        }
        localStorage.clear();
        window.location.replace('index.html');
    });

    loadDevices();
});

async function loadDevices() {
    try {
        const response = await apiFetch('/api/devices');
        const data = await response.json();

        if (data.success) {
            renderDevices(data.data);
        } else {
            alert('Failed to load devices: ' + data.message);
        }
    } catch (err) {
        console.error('Error fetching devices', err);
        alert('Failed to connect to server.');
    }
}

function renderDevices(devices) {
    const tbody = document.getElementById('devicesTableBody');
    tbody.innerHTML = '';

    const statTotal = document.getElementById('statTotalDevices');
    const statOnline = document.getElementById('statOnlineDevices');

    let onlineCount = 0;

    if (devices.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align: center; padding: 20px;">No devices registered yet. Open the Android app to register a device.</td></tr>';
        statTotal.innerText = '0';
        statOnline.innerText = '0';
        return;
    }

    devices.forEach(d => {
        if (d.is_online) onlineCount++;

        const statusClass = d.is_online ? 'online' : 'offline';
        const statusText = d.is_online ? 'Online' : 'Offline';
        const lastSeen = d.last_seen ? new Date(d.last_seen).toLocaleString() : 'Never';

        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td><span class="status-badge ${statusClass}">${statusText}</span></td>
            <td><strong>${d.device_name || 'Unknown'}</strong><br><small class="text-muted">${d.device_token}</small></td>
            <td>${d.device_model || 'N/A'}</td>
            <td>Android ${d.android_version || '?'}</td>
            <td>v${d.app_version || '?'}</td>
            <td>${lastSeen}</td>
            <td>
                <button class="btn btn-danger btn-sm" onclick="deleteDevice('${d.id}')">
                    <i class='bx bx-trash'></i> Delete
                </button>
            </td>
        `;
        tbody.appendChild(tr);
    });

    statTotal.innerText = devices.length;
    statOnline.innerText = onlineCount;
}

async function deleteDevice(id) {
    if (!confirm('Are you sure you want to delete this device? It will be disconnected immediately.')) {
        return;
    }

    try {
        const response = await apiFetch(`/api/devices/${id}`, { method: 'DELETE' });
        const data = await response.json();

        if (data.success) {
            loadDevices(); // reload list
        } else {
            alert('Failed to delete device: ' + data.message);
        }
    } catch (err) {
        console.error('Delete error', err);
        alert('Server error while deleting device.');
    }
}
