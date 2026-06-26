# 📡 Remote Monitor

A complete, production-ready remote monitoring system for **your own Android device**.
Built with: Android (Kotlin) · Node.js · Socket.IO · WebRTC · MySQL · HTML/CSS/JS

---

## 🏗️ Architecture

```
Browser  ←── WSS / HTTPS ──→  Node.js Server  ←── WSS ──  Android App
              WebRTC Signaling      │
                                  MySQL
```

---

## 📁 Project Structure

```
remote-monitor/
├── android-app/              ← Android Studio project (Kotlin)
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       └── java/com/remotemonitor/app/
│           ├── RemoteMonitorApp.kt
│           ├── data/
│           │   ├── ApiService.kt
│           │   └── SessionManager.kt
│           ├── ui/
│           │   ├── LoginActivity.kt
│           │   └── MainActivity.kt
│           └── services/
│               ├── MonitorService.kt
│               ├── LocationHelper.kt
│               └── DeviceStatusHelper.kt
├── server/                   ← Node.js backend
│   ├── index.js
│   ├── config/
│   │   ├── database.js
│   │   └── logger.js
│   ├── middlewares/
│   │   ├── auth.js
│   │   └── security.js
│   ├── controllers/
│   │   ├── authController.js
│   │   └── deviceController.js
│   ├── routes/
│   │   ├── auth.js
│   │   └── devices.js
│   └── services/
│       └── signalingService.js
├── frontend/                 ← Web dashboard
│   ├── index.html            (login)
│   ├── dashboard.html
│   ├── live.html
│   ├── location.html
│   ├── settings.html
│   └── assets/
│       ├── css/style.css
│       └── js/
│           ├── app.js
│           ├── auth.js
│           ├── dashboard.js
│           ├── live.js
│           ├── location.js
│           └── settings.js
├── database/
│   └── schema.sql
├── deploy.sh
└── README.md
```

---

## 🚀 Quick Start

### 1. Database

```bash
mysql -u root -p < database/schema.sql
```

Create a dedicated user:
```sql
CREATE USER 'monitor_user'@'localhost' IDENTIFIED BY 'strong_password';
GRANT ALL PRIVILEGES ON remote_monitor.* TO 'monitor_user'@'localhost';
FLUSH PRIVILEGES;
```

Default admin login: `admin@monitor.local` / `Admin@123` — **change immediately**.

---

### 2. Node.js Server

```bash
cd server
cp .env.example .env
# Edit .env with your values
npm install
npm start
```

**Development mode** (HTTP):
```bash
NODE_ENV=development npm run dev
```

**Production mode** (HTTPS — requires SSL certificate):
```bash
NODE_ENV=production npm start
```

---

### 3. Frontend

Copy the `frontend/` folder to your web server (nginx/Apache) or serve via Node.js `public/` directory.

Edit `assets/js/app.js` or set `rm_server_url` in localStorage to point to your server.

---

### 4. Android App

1. Open `android-app/` in **Android Studio Hedgehog or newer**.
2. In `app/build.gradle`, set `SERVER_URL` and `WS_URL` to your server address.
3. Build and install on your device:
   ```bash
   ./gradlew installDebug
   ```
4. Open the app → log in → grant **all permissions when prompted**.
5. The device will auto-register and start streaming.

---

## 🔒 Security Checklist

- [ ] Change default admin password immediately after setup
- [ ] Use a valid SSL certificate (Let's Encrypt is free)
- [ ] Set strong `JWT_SECRET` (64+ random characters)
- [ ] Restrict MySQL user to `localhost`
- [ ] Configure `CORS_ORIGINS` to your exact domain
- [ ] Enable firewall — only ports 80/443 should be public
- [ ] Never commit `.env` to git

---

## 🌐 REST API Reference

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/auth/login` | ❌ | Login |
| POST | `/api/auth/logout` | ✅ JWT | Logout |
| POST | `/api/auth/refresh` | ❌ | Refresh access token |
| POST | `/api/auth/change-password` | ✅ JWT | Change password |
| GET | `/api/devices` | ✅ JWT | List devices |
| POST | `/api/devices/register` | ✅ JWT | Register device |
| DELETE | `/api/devices/:id` | ✅ JWT | Delete device |
| POST | `/api/devices/:id/status` | ✅ JWT | Update status |
| POST | `/api/devices/:id/location` | ✅ JWT | Update location |
| GET | `/api/devices/:id/locations` | ✅ JWT | Location history |
| GET | `/health` | ❌ | Server health |

---

## ⚡ WebSocket Events (Socket.IO)

### `/device` namespace (Android app)
| Event | Direction | Description |
|-------|-----------|-------------|
| `status:update` | → server | Push battery/network status |
| `location:update` | → server | Push GPS coordinates |
| `webrtc:offer` | → server | Send WebRTC offer |
| `webrtc:ice` | → server | Send ICE candidate |
| `webrtc:request-offer` | ← server | Server asks device to offer |
| `webrtc:answer` | ← server | Viewer's answer |
| `device:online` | broadcast | Device connected |
| `device:offline` | broadcast | Device disconnected |

### `/viewer` namespace (Browser)
| Event | Direction | Description |
|-------|-----------|-------------|
| `watch:device` | → server | Start watching a device |
| `webrtc:answer` | → server | Send answer to device |
| `webrtc:ice` | → server | Send ICE candidate |
| `webrtc:offer` | ← server | Receive device's offer |
| `status:update` | ← server | Real-time device status |
| `location:update` | ← server | Real-time GPS |

---

## 🛠️ Deployment with nginx

```nginx
server {
    listen 443 ssl http2;
    server_name yourdomain.com;

    ssl_certificate     /etc/letsencrypt/live/yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/yourdomain.com/privkey.pem;

    # API + WebSocket proxy
    location /api/ {
        proxy_pass http://localhost:3000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /socket.io/ {
        proxy_pass http://localhost:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
    }

    # Static frontend
    location / {
        root /var/www/remote-monitor/frontend;
        try_files $uri $uri/ /index.html;
    }
}

server {
    listen 80;
    server_name yourdomain.com;
    return 301 https://$host$request_uri;
}
```

---

## 📦 Android Dependencies

| Library | Purpose |
|---------|---------|
| `io.github.webrtc-sdk:android` | WebRTC peer connection |
| `io.socket:socket.io-client` | Signaling connection |
| `androidx.camera:camera-*` | CameraX front/back |
| `com.google.android.gms:play-services-location` | GPS (FusedLocation) |
| `androidx.datastore:datastore-preferences` | Secure token storage |
| `com.squareup.retrofit2:retrofit` | REST API client |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | Async operations |

---

## 📄 License

This project is for **personal use only** — monitoring your own device.
Do not use to monitor devices without explicit owner consent.
