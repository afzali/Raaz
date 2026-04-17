Update Todos

Phase 0: Create project foundation

Phase 1: Security core (AuthDatabase, PasswordManager, KeyManager, RaazDatabase, MessageCrypto, CryptoManager)

Phase 2: Data models, DAOs, repositories, Lock+Setup screens, nav_graph, MainActivity

Phase 3: Network layer (RaazApiService, RaazPreferences, NetworkMonitor)

Phase 4: Go relay server (main.go, db layer, handlers, cleanup service, Dockerfile)

Phase 5: Chat UI (ChatsFragment, ChatFragment, SyncWorker, SendWorker, NotificationManager)

Phase 6: Contact exchange (QrCodeHelper, AddContactFragment, CameraX+ZXing QR scan, invite code)

Phase 7: Settings + Log viewer (SettingsFragment, LogViewerFragment, DateFormatter, CleanupWorker)

Phase 8: Hardening (network_security_config, backup_rules, proguard-rules, tests)


✅ آنچه ساخته شد
Android (android/)
بخش	فایل‌ها
Build	build.gradle, settings.gradle, gradle.properties, proguard-rules.pro
Security Core	CryptoManager, MessageCrypto, KeyManager, PasswordManager, QrCodeHelper
Databases	RaazDatabase (SQLCipher main) + AuthDatabase (brute-force/logs, device-bound key)
Data Layer	4 DAOs, 3 models, 3 repositories, RaazPreferences, RaazApiService
UI Screens	Lock, Setup, Chats, Chat, AddContact, Settings, LogViewer (همه Fragment + ViewModel)
Workers	SyncWorker (polling 15min) + CleanupWorker
Resources	فونت Dana (13 وزن), Feather Icons, strings FA+EN, themes light+dark, layouts
Go Server (server/)
بخش	فایل‌ها
Entry point	cmd/raazd/main.go
API	router.go, handler_device.go, handler_message.go, middleware_auth.go
DB	db.go, schema.go, device_repo.go, message_repo.go
Service	cleanup_service.go (پاک‌سازی هر ۱۵ دقیقه)
Infra	Dockerfile, Makefile
قدم بعدی
برای build اول باید:

در server/ اجرا کنید: go mod tidy
در android/ یک gradle wrapper JAR دانلود کنید و در gradle/wrapper/ بگذارید
سپس: ./gradlew assembleDebug





اول php  هم می خواهم از اول یعنی go و php داشته باشیم

I'll build the PHP relay server now.

Agent:Build PHP relay server



Build a complete PHP relay server for the Raaz E2EE messenger at `d:\Projects\raaz\server-php\`. 

The PHP server must implement identical API contract as the Go server:
- Base path: `/api/v1`
- Auth: `Authorization: Bearer <token>` — server stores only SHA-256(token), never the token itself
- Endpoints:
  - GET `/api/v1/health` — public, no auth
  - POST `/api/v1/devices/register` — public, register device, return one-time bearer token
  - POST `/api/v1/messages` — auth, push encrypted message to recipient device
  - GET `/api/v1/messages` — auth, pull pending messages for this device
  - DELETE `/api/v1/messages/{id}` — auth, ACK receipt + delete from server

SQLite schema (file: `raaz_server.db`):
```sql
PRAGMA journal_mode=WAL;
PRAGMA secure_delete=ON;
PRAGMA foreign_keys=ON;

CREATE TABLE devices (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    public_key TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    registered_at INTEGER NOT NULL,
    last_active INTEGER
);

CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    recipient_device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    sender_device TEXT NOT NULL,
    ciphertext TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    acked INTEGER NOT NULL DEFAULT 0
);
```

File structure to create:
- `server-php/index.php` — entry point (all routing)
- `server-php/src/Database.php` — PDO SQLite singleton + migration
- `server-php/src/Router.php` — simple request router
- `server-php/src/Auth.php` — Bearer token middleware
- `server-php/src/handlers/HealthHandler.php`
- `server-php/src/handlers/DeviceHandler.php`
- `server-php/src/handlers/MessageHandler.php`
- `server-php/.htaccess` — Apache rewrite rules for shared hosting
- `server-php/nginx.conf` — Nginx config sample
- `server-php/config.php` — TTL and other settings

Requirements:
- PHP 7.4+ compatible (shared hosting safe)
- PDO SQLite (no MySQL dependency)
- JSON request/response only
- Bearer token: generate random 32 bytes, return as hex string to caller, store SHA-256(token) in DB
- Device registration: request body `{userId, deviceId, publicKey}`, response `{token, deviceId}`
- Send message: body `{recipientDeviceId, ciphertext}`, TTL = 86400 seconds default
- Pull messages: returns array of `{id, senderId, ciphertext, createdAt}`, updates last_active
- ACK/delete: DELETE /api/v1/messages/{id} — only deletes if it belongs to authenticated device
- Cleanup expired messages on every request (lightweight: DELETE WHERE expires_at < now AND acked=1, or expires_at < now-3600)
- Proper HTTP status codes: 200, 201, 400, 401, 404, 405, 500
- CORS headers for API access
- No framework dependencies — vanilla PHP only

Write all files to disk. Start with `d:\Projects\raaz\server-php\` as the root directory.

Bash Check existing project structure
IN
ls d:/Projects/raaz/ 2>/dev/null && echo "---" && ls d:/Projects/raaz/server-php/ 2>/dev/null || echo "server-php dir does not exist yet"

Raaz-Plan.md
Raaz.md
android
phase01.md
server
---
server-php dir does not exist yet