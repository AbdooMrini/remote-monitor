// ============================================================
// assets/js/app.js — Shared utilities for all dashboard pages
// ============================================================
'use strict';

// ── Constants ─────────────────────────────────────────────
const TOKEN_KEY  = 'rm_access_token';
const RTOKEN_KEY = 'rm_refresh_token';
const USER_KEY   = 'rm_user';
const SERVER_KEY = 'rm_server_url';

// ── Auth guard — redirect to login if no token ────────────
function requireAuth() {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) { window.location.replace('index.html'); return null; }
    return token;
}

// ── Get server URL ────────────────────────────────────────
function serverUrl() {
    // Hardcoded production URL for Netlify deployment
    const PROD_URL = 'https://remote-monitor-1p87.onrender.com';
    return (localStorage.getItem(SERVER_KEY) || PROD_URL).trim().replace(/\/$/, '');
}

// ── Authenticated fetch with auto-refresh ─────────────────
async function apiFetch(path, options = {}) {
    const token = localStorage.getItem(TOKEN_KEY);
    const opts  = {
        ...options,
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`,
            ...(options.headers || {}),
        },
    };

    let res = await fetch(`${serverUrl()}${path}`, opts);

    // Auto-refresh on 401
    if (res.status === 401) {
        const refreshed = await tryRefresh();
        if (!refreshed) { logout(); return null; }
        opts.headers['Authorization'] = `Bearer ${localStorage.getItem(TOKEN_KEY)}`;
        res = await fetch(`${serverUrl()}${path}`, opts);
    }

    return res;
}

async function tryRefresh() {
    const rToken = localStorage.getItem(RTOKEN_KEY);
    if (!rToken) return false;
    try {
        const res = await fetch(`${serverUrl()}/api/auth/refresh`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken: rToken }),
        });
        if (!res.ok) return false;
        const data = await res.json();
        if (!data.success) return false;
        localStorage.setItem(TOKEN_KEY,  data.data.accessToken);
        localStorage.setItem(RTOKEN_KEY, data.data.refreshToken);
        return true;
    } catch { return false; }
}

function logout() {
    apiFetch('/api/auth/logout', { method: 'POST' }).catch(() => {});
    localStorage.clear();
    window.location.replace('index.html');
}

// ── Current user ──────────────────────────────────────────
function currentUser() {
    try { return JSON.parse(localStorage.getItem(USER_KEY)); } catch { return null; }
}

// ── Toast system ──────────────────────────────────────────
let toastContainer;

function initToasts() {
    toastContainer = document.createElement('div');
    toastContainer.className = 'toast-container';
    document.body.appendChild(toastContainer);
}

function showToast(type = 'info', title = '', message = '', duration = 4000) {
    if (!toastContainer) initToasts();

    const icons = {
        success: `<svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="9" stroke="currentColor" stroke-width="1.5"/><path d="M6 10l3 3 5-5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>`,
        error:   `<svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="9" stroke="currentColor" stroke-width="1.5"/><path d="M13 7L7 13M7 7l6 6" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>`,
        info:    `<svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="9" stroke="currentColor" stroke-width="1.5"/><path d="M10 9v5M10 7v.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>`,
    };

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.innerHTML = `
        <span class="toast-icon">${icons[type] || icons.info}</span>
        <div class="toast-content">
            ${title ? `<div class="toast-title">${escapeHtml(title)}</div>` : ''}
            ${message ? `<div class="toast-message">${escapeHtml(message)}</div>` : ''}
        </div>
    `;

    toastContainer.appendChild(toast);
    setTimeout(() => {
        toast.classList.add('leaving');
        toast.addEventListener('animationend', () => toast.remove(), { once: true });
    }, duration);
}

// ── Sidebar active state ──────────────────────────────────
function initSidebar() {
    const path  = window.location.pathname.split('/').pop() || 'index.html';
    const links = document.querySelectorAll('.nav-item[data-page]');
    links.forEach(link => {
        if (link.dataset.page === path) link.classList.add('active');
    });

    // Mobile toggle
    const toggle = document.getElementById('sidebarToggle');
    const sidebar = document.querySelector('.sidebar');
    if (toggle && sidebar) {
        toggle.addEventListener('click', () => sidebar.classList.toggle('open'));
        document.addEventListener('click', (e) => {
            if (!sidebar.contains(e.target) && !toggle.contains(e.target)) {
                sidebar.classList.remove('open');
            }
        });
    }

    // Logout button
    document.getElementById('logoutBtn')?.addEventListener('click', logout);

    // Render user info
    const user = currentUser();
    if (user) {
        const nameEl = document.getElementById('sidebarUserName');
        const emailEl = document.getElementById('sidebarUserEmail');
        if (nameEl)  nameEl.textContent  = user.fullName || user.email;
        if (emailEl) emailEl.textContent = user.email;
    }
}

// ── XSS safe escape ───────────────────────────────────────
function escapeHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

// ── Format helpers ────────────────────────────────────────
function formatBattery(level) {
    if (level == null) return '—';
    return `${level}%`;
}

function formatNetwork(type) {
    const map = { wifi: '📶 Wi-Fi', mobile: '📡 Mobile', none: '❌ No Signal', unknown: '? Unknown' };
    return map[type] || type || '—';
}

function formatTimestamp(ts) {
    if (!ts) return '—';
    const d = new Date(ts);
    return d.toLocaleString();
}

function timeAgo(ts) {
    if (!ts) return '—';
    const sec = Math.floor((Date.now() - new Date(ts).getTime()) / 1000);
    if (sec < 60)   return `${sec}s ago`;
    if (sec < 3600) return `${Math.floor(sec/60)}m ago`;
    if (sec < 86400) return `${Math.floor(sec/3600)}h ago`;
    return `${Math.floor(sec/86400)}d ago`;
}

function batteryClass(level) {
    if (level >= 50) return 'high';
    if (level >= 20) return 'medium';
    return 'low';
}
