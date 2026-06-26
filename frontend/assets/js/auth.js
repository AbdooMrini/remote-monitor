// ============================================================
// assets/js/auth.js — Login page logic
// ============================================================
'use strict';

const SERVER_KEY = 'rm_server_url';
const TOKEN_KEY  = 'rm_access_token';
const RTOKEN_KEY = 'rm_refresh_token';
const USER_KEY   = 'rm_user';

// Redirect if already logged in
if (localStorage.getItem(TOKEN_KEY)) {
    window.location.replace('dashboard.html');
}

const form         = document.getElementById('loginForm');
const emailInput   = document.getElementById('email');
const pwInput      = document.getElementById('password');
const emailErr     = document.getElementById('emailError');
const pwErr        = document.getElementById('passwordError');
const loginBtn     = document.getElementById('loginBtn');
const loginAlert   = document.getElementById('loginAlert');
const togglePwBtn  = document.getElementById('togglePassword');

// ── Toggle password visibility ────────────────────────────
togglePwBtn.addEventListener('click', () => {
    const isText = pwInput.type === 'text';
    pwInput.type = isText ? 'password' : 'text';
});

// ── Live validation ───────────────────────────────────────
emailInput.addEventListener('input', () => {
    emailErr.textContent = '';
    emailInput.classList.remove('is-invalid');
});
pwInput.addEventListener('input', () => {
    pwErr.textContent = '';
    pwInput.classList.remove('is-invalid');
});

// ── Submit ────────────────────────────────────────────────
form.addEventListener('submit', async (e) => {
    e.preventDefault();
    let valid = true;

    const email    = emailInput.value.trim();
    const password = pwInput.value;
    const server   = (localStorage.getItem(SERVER_KEY) || '').trim() || window._serverUrl || '';

    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        emailErr.textContent = 'Please enter a valid email address.';
        emailInput.classList.add('is-invalid');
        valid = false;
    }
    if (password.length < 6) {
        pwErr.textContent = 'Password must be at least 6 characters.';
        pwInput.classList.add('is-invalid');
        valid = false;
    }
    if (!server) {
        showAlert('error', 'Server URL is not configured. Check your deployment settings.');
        return;
    }
    if (!valid) return;

    setLoading(true);
    hideAlert();

    try {
        const res = await fetch(`${server}/api/auth/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password }),
        });

        const data = await res.json();

        if (!res.ok || !data.success) {
            showAlert('error', data.message || 'Login failed. Check your credentials.');
            return;
        }

        // Persist session
        localStorage.setItem(TOKEN_KEY,  data.data.accessToken);
        localStorage.setItem(RTOKEN_KEY, data.data.refreshToken);
        localStorage.setItem(USER_KEY,   JSON.stringify(data.data.user));
        localStorage.setItem(SERVER_KEY, server);

        window.location.replace('dashboard.html');
    } catch (err) {
        showAlert('error', `Connection error: ${err.message}`);
    } finally {
        setLoading(false);
    }
});

function setLoading(on) {
    loginBtn.disabled = on;
    loginBtn.querySelector('.btn-text').style.display    = on ? 'none' : '';
    loginBtn.querySelector('.btn-spinner').style.display = on ? '' : 'none';
}

function showAlert(type, msg) {
    loginAlert.className = `alert alert-${type}`;
    loginAlert.textContent = msg;
    loginAlert.style.display = 'block';
}

function hideAlert() {
    loginAlert.style.display = 'none';
}
