#!/bin/bash
# ==========================================================
# deploy.sh — One-shot production deployment helper
# Run as: chmod +x deploy.sh && sudo ./deploy.sh
# ==========================================================
set -e

echo "========================================"
echo "  Remote Monitor — Deployment Script"
echo "========================================"

# ── Node.js server ────────────────────────────────────────
echo "[1/4] Installing server dependencies..."
cd server
npm ci --omit=dev
cd ..

# ── Frontend ──────────────────────────────────────────────
echo "[2/4] Copying frontend to /var/www/remote-monitor..."
mkdir -p /var/www/remote-monitor
cp -r frontend/ /var/www/remote-monitor/

# ── PM2 process manager ───────────────────────────────────
echo "[3/4] Starting server with PM2..."
if ! command -v pm2 &> /dev/null; then
    npm install -g pm2
fi
pm2 delete remote-monitor 2>/dev/null || true
pm2 start server/index.js --name remote-monitor --env production
pm2 save
pm2 startup

# ── Firewall ──────────────────────────────────────────────
echo "[4/4] Configuring UFW firewall..."
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo ""
echo "✅  Deployment complete!"
echo "   Frontend: /var/www/remote-monitor/frontend"
echo "   Server:   PM2 process 'remote-monitor'"
echo ""
echo "⚠️  Remember to:"
echo "   1. Edit server/.env with your real values"
echo "   2. Configure nginx with the config in README.md"
echo "   3. Set up Let's Encrypt SSL: certbot --nginx -d yourdomain.com"
echo "   4. Change the default admin password"
