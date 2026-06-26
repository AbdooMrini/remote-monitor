// ============================================================
// assets/js/settings.js
// ============================================================
'use strict';

requireAuth();
initSidebar();
initToasts();

// ── Populate account info ─────────────────────────────────
const user = currentUser();
if (user) {
    document.getElementById('settingsEmail').textContent = user.email;
    document.getElementById('settingsRole').textContent  = user.role;
}

// ── Server URL ────────────────────────────────────────────
document.getElementById('serverUrlInput').value = localStorage.getItem('rm_server_url') || '';

document.getElementById('saveServerBtn').addEventListener('click', () => {
    const url = document.getElementById('serverUrlInput').value.trim();
    if (!url.startsWith('http')) {
        showToast('error', 'Invalid URL', 'URL must start with https://');
        return;
    }
    localStorage.setItem('rm_server_url', url);
    showToast('success', 'Saved', 'Server URL updated. Reload pages to apply.');
});

// ── Change password ───────────────────────────────────────
document.getElementById('changePasswordForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const curr    = document.getElementById('currentPw').value;
    const newPw   = document.getElementById('newPw').value;
    const confirm = document.getElementById('confirmPw').value;
    const alert   = document.getElementById('pwAlert');

    if (newPw !== confirm) {
        alert.className = 'alert alert-error';
        alert.textContent = 'New passwords do not match.';
        alert.style.display = 'block';
        return;
    }
    if (newPw.length < 8) {
        alert.className = 'alert alert-error';
        alert.textContent = 'Password must be at least 8 characters.';
        alert.style.display = 'block';
        return;
    }

    try {
        const res = await apiFetch('/api/auth/change-password', {
            method: 'POST',
            body: JSON.stringify({ currentPassword: curr, newPassword: newPw }),
        });
        const data = await res.json();
        if (data.success) {
            alert.className = 'alert alert-success';
            alert.textContent = 'Password updated successfully.';
            alert.style.display = 'block';
            document.getElementById('changePasswordForm').reset();
            showToast('success', 'Password Changed', 'You may need to log in again.');
        } else {
            alert.className = 'alert alert-error';
            alert.textContent = data.message || 'Update failed.';
            alert.style.display = 'block';
        }
    } catch (err) {
        showToast('error', 'Error', err.message);
    }
});

// ── Logout all sessions ───────────────────────────────────
document.getElementById('logoutAllBtn').addEventListener('click', () => {
    if (confirm('This will log you out from all sessions. Continue?')) {
        logout();
    }
});
