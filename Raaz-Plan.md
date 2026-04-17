Ready for review
Select text to add comments on the plan
Raaz (راز) — Implementation Plan
Context
Build "Raaz", a secure E2EE messenger from scratch in d:\Projects\raaz\. The project directory is empty. The app uses a Client-Encrypted + Minimal Relay Server model: all encryption happens on-device, the server only stores/routes encrypted blobs temporarily. Two components:

Android client (Kotlin, MVVM, min SDK 24, target SDK 34)
Go relay server (minimal, self-hostable single binary)
User choices confirmed:

Build both components together from the start
Lazysodium for E2EE crypto (X25519 + XChaCha20-Poly1305 + Argon2id)
No FCM — WorkManager polling (every 15 min, plus on-open sync)
System-default theme (light + dark, follows Android system setting)
Directory Structure
d:\Projects\raaz\
├── .gitignore
├── android/                        # Android project
│   ├── build.gradle                # Top-level: plugin declarations only
│   ├── settings.gradle             # JitPack, Google Maven, MavenCentral
│   ├── gradle.properties
│   ├── gradle/wrapper/
│   └── app/
│       ├── build.gradle            # All dependencies
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/io/raaz/messenger/
│           │   ├── RaazApplication.kt
│           │   ├── ui/
│           │   │   ├── MainActivity.kt          # Single-Activity host
│           │   │   ├── lock/LockFragment.kt + LockViewModel.kt
│           │   │   ├── setup/SetupFragment.kt + SetupViewModel.kt
│           │   │   ├── chats/ChatsFragment.kt + ChatsViewModel.kt + ChatListAdapter.kt
│           │   │   ├── chat/ChatFragment.kt + ChatViewModel.kt + MessageAdapter.kt
│           │   │   ├── addcontact/AddContactFragment.kt + AddContactViewModel.kt
│           │   │   ├── settings/SettingsFragment.kt + SettingsViewModel.kt
│           │   │   └── logs/LogViewerFragment.kt + LogViewerViewModel.kt + LogEntryAdapter.kt
│           │   ├── data/
│           │   │   ├── db/RaazDatabase.kt       # SQLCipher main DB
│           │   │   ├── db/AuthDatabase.kt       # SQLCipher auth/brute-force DB
│           │   │   ├── db/dao/*.kt              # MessageDao, ContactDao, SessionDao, LoginAttemptDao
│           │   │   ├── model/*.kt               # Contact, Message, Session, LoginAttempt
│           │   │   ├── repository/*.kt          # MessageRepository, ContactRepository, etc.
│           │   │   ├── network/RaazApiService.kt + ApiRequest.kt + ApiResponse.kt
│           │   │   └── preferences/RaazPreferences.kt  # EncryptedSharedPreferences wrapper
│           │   ├── crypto/
│           │   │   ├── CryptoManager.kt         # Main façade
│           │   │   ├── KeyManager.kt            # X25519 keygen + storage
│           │   │   ├── PasswordManager.kt       # Argon2id derivation + lock state
│           │   │   ├── MessageCrypto.kt         # Per-message ephemeral ECDH encrypt/decrypt
│           │   │   └── QrCodeHelper.kt          # Encode/decode public key for QR/invite
│           │   ├── worker/
│           │   │   ├── SyncWorker.kt            # Pull messages (periodic + on-open)
│           │   │   ├── SendWorker.kt            # Push queued outgoing messages
│           │   │   └── CleanupWorker.kt         # Expire local messages past TTL
│           │   ├── notification/RaazNotificationManager.kt
│           │   └── util/
│           │       ├── LocaleManager.kt         # RTL/LTR switching
│           │       ├── FontProvider.kt          # Dana for fa, system for en
│           │       ├── DateFormatter.kt         # Jalali (fa) / Gregorian (en)
│           │       ├── AppLogger.kt             # In-app log system → auth DB
│           │       ├── SessionLockManager.kt    # Inactivity timer + auto-lock
│           │       └── Extensions.kt
│           └── res/
│               ├── font/dana*.ttf + dana.xml   # Copied from ilog project
│               ├── drawable/ic_feather_*.xml   # Feather icon vector drawables
│               ├── layout/                     # All fragment + item layouts
│               ├── navigation/nav_graph.xml
│               ├── values/ + values-fa/        # EN + FA strings, colors, themes
│               └── values-night/               # Dark theme overrides
└── server/
    ├── go.mod
    ├── Makefile + Dockerfile
    ├── cmd/raazd/main.go
    └── internal/
        ├── api/router.go + handler_*.go + middleware_auth.go
        ├── db/db.go + schema.go + device_repo.go + message_repo.go
        ├── model/device.go + message.go
        ├── service/device_service.go + message_service.go + cleanup_service.go
        └── config/config.go
Critical Files (to modify / create)
File	Purpose
android/app/build.gradle	All dependencies — SQLCipher, Lazysodium, Navigation, WorkManager, ZXing, CameraX, Retrofit
android/app/src/main/java/io/raaz/messenger/crypto/CryptoManager.kt	Central crypto façade — correctness here = entire security guarantee
android/app/src/main/java/io/raaz/messenger/data/db/RaazDatabase.kt	SQLCipher main DB + schema
android/app/src/main/java/io/raaz/messenger/data/db/AuthDatabase.kt	Auth DB (brute-force, logs) — device-bound key, always accessible
android/app/src/main/res/navigation/nav_graph.xml	Screen flow + lock intercept pattern
server/internal/api/router.go	chi router wiring all endpoints
Reuse from ilog project
Dana font TTF files → copy from d:\Projects\ilog\CascadeProjects\windsurf-project\app\src\main\res\font\
dana.xml font family definition → copy and adapt
Pattern for LocaleManager (RTL/LTR) → adapt from ilog's locale switching
Pattern for PersianCalendarUtil → adapt for DateFormatter.kt
Build config pattern (minSdk 24, targetSdk 34, viewBinding) → match ilog's approach
Key Architectural Decisions
Two separate SQLCipher databases
raaz_main.db: messages, contacts, sessions — opened ONLY after correct password (key = Argon2id(password, salt))
raaz_auth.db: login attempts, lock state, in-app logs — uses device-bound key (Android Keystore + app secret), always accessible regardless of user password
Why: brute-force protection cannot be bypassed even before main DB is unlocked
Ephemeral key per message (ECIES-style)
Fresh X25519 keypair per message → no long-term shared symmetric key
Payload = eph_pub(32) ++ nonce(24) ++ ciphertext
Forward secrecy without Double Ratchet complexity (Phase 2 can add full ratchet)
Server stores no readable metadata
Server schema: deviceId, encrypted blob, TTL only
No conversation graph, no contact lists, no readable content
Server deletes messages after ACK or TTL expiry (24h default)
Password never stored
Argon2id(password, salt) → K_db to open SQLCipher
Verification = SQLCipher open succeeds or fails
Salt stored in raaz_auth.db (salt is not secret)
Database Schema
raaz_main.db (SQLCipher, key = Argon2id(password, salt))
CREATE TABLE contacts (
    id TEXT PRIMARY KEY,             -- userId (UUID v4)
    display_name TEXT NOT NULL,
    public_key TEXT NOT NULL,        -- Base64 X25519 pubkey
    device_id TEXT NOT NULL,
    server_url TEXT NOT NULL,
    added_at INTEGER NOT NULL,
    last_seen INTEGER,
    is_verified INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE sessions (
    id TEXT PRIMARY KEY,
    contact_id TEXT NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    last_message_at INTEGER,
    message_ttl_ms INTEGER NOT NULL DEFAULT 86400000,
    sensitivity INTEGER NOT NULL DEFAULT 0,
    notif_behavior INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    direction INTEGER NOT NULL,      -- 0=outgoing, 1=incoming
    ciphertext TEXT NOT NULL,        -- Base64(eph_pub ++ nonce ++ ct)
    plaintext_cache TEXT,            -- NULL for sensitivity=2
    status INTEGER NOT NULL DEFAULT 0,  -- 0=queued,1=sent,2=delivered,3=confirmed,4=expired
    created_at INTEGER NOT NULL,
    expires_at INTEGER,
    server_msg_id TEXT,
    nonce TEXT NOT NULL
);

CREATE TABLE app_settings (
    id INTEGER PRIMARY KEY DEFAULT 1,
    lock_timeout_ms INTEGER NOT NULL DEFAULT 300000,
    language TEXT NOT NULL DEFAULT 'fa',
    theme INTEGER NOT NULL DEFAULT 0,
    server_url TEXT NOT NULL DEFAULT 'https://relay.raaz.io',
    notif_enabled INTEGER NOT NULL DEFAULT 1,
    setup_complete INTEGER NOT NULL DEFAULT 0
);
raaz_auth.db (SQLCipher, device-bound key)
CREATE TABLE login_attempts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    attempted_at INTEGER NOT NULL,
    success INTEGER NOT NULL DEFAULT 0,
    attempt_hash TEXT NOT NULL       -- HMAC-obfuscated counter
);

CREATE TABLE lock_state (
    id INTEGER PRIMARY KEY DEFAULT 1,
    is_locked INTEGER NOT NULL DEFAULT 1,
    locked_at INTEGER,
    lockout_until INTEGER,           -- NULL = no lockout
    fail_streak INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE app_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp INTEGER NOT NULL,
    level TEXT NOT NULL,             -- DEBUG, INFO, WARN, ERROR
    tag TEXT NOT NULL,
    message TEXT NOT NULL,
    stack_trace TEXT
);
Go Server raaz_server.db
PRAGMA journal_mode=WAL;
PRAGMA secure_delete=ON;
PRAGMA foreign_keys=ON;

CREATE TABLE devices (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    public_key TEXT NOT NULL,
    token_hash TEXT NOT NULL,        -- SHA-256(bearer token), token never stored
    registered_at INTEGER NOT NULL,
    last_active INTEGER
);

CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    recipient_device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    sender_device TEXT NOT NULL,
    ciphertext TEXT NOT NULL,        -- Opaque blob, server never inspects
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    acked INTEGER NOT NULL DEFAULT 0
);
API Contract (Go Server)
Base: https://<host>/api/v1
Auth: Authorization: Bearer <token>

Method	Path	Description
POST	/devices/register	Register device, returns one-time token
POST	/messages	Push encrypted message
GET	/messages	Pull pending messages for this device
DELETE	/messages/:id	ACK receipt, triggers server-side deletion
GET	/health	No auth, server status
Encryption Flow
Setup
password P → Argon2id(P, salt, m=65536, t=3, p=1) → K_db
Generate X25519 keypair → store privateKey encrypted under K_db
Register device with server → receive bearer token → EncryptedSharedPreferences
Brute-force gate (checked BEFORE Argon2id derivation)
fail_streak 1-2:  allow immediately
fail_streak 3-8:  lockout_until = now + 30 min
fail_streak 9+:   WIPE raaz_main.db, reset auth DB
Send message
PT → fresh X25519 ephemeral keypair (eph_pub, eph_priv)
ECDH: shared = X25519(eph_priv, recipient_pubkey)
K_msg = HKDF-SHA256(shared, "raaz-msg-v1", 32)
nonce = random 24 bytes
CT = XChaCha20-Poly1305(K_msg, nonce, PT)
payload = Base64(eph_pub ++ nonce ++ CT)
→ POST /messages {ciphertext: payload}
Receive message
GET /messages → for each blob:
  decode payload: eph_pub=[:32], nonce=[32:56], CT=[56:]
  shared = X25519(myPrivateKey, eph_pub)
  K_msg = HKDF-SHA256(shared, "raaz-msg-v1", 32)
  PT = XChaCha20-Poly1305-Decrypt(K_msg, nonce, CT)
  → store in DB, DELETE /messages/:id (ACK)
Key Dependencies
Android (app/build.gradle)
SQLCipher:         net.zetetic:sqlcipher-android:4.5.6
Lazysodium:        com.goterl:lazysodium-android:5.1.0@aar
JNA:               net.java.dev.jna:jna:5.14.0@aar
Navigation:        androidx.navigation:navigation-fragment-ktx:2.7.7
WorkManager:       androidx.work:work-runtime-ktx:2.9.0
CameraX:           androidx.camera:camera-*:1.3.4
ZXing:             com.journeyapps:zxing-android-embedded:4.3.0
PersianDate:       com.github.samanzamani:PersianDate:1.7.1
EncryptedPrefs:    androidx.security:security-crypto:1.1.0-alpha06
OkHttp:            com.squareup.okhttp3:okhttp:4.12.0
Retrofit:          com.squareup.retrofit2:retrofit:2.11.0
Material3:         com.google.android.material:material:1.12.0
AGP:               8.3.2, Kotlin: 2.0.0, minSdk: 24, targetSdk: 34
Go Server (go.mod)
github.com/go-chi/chi/v5 v5.0.12
github.com/mattn/go-sqlite3 v1.14.22
github.com/google/uuid v1.6.0
Implementation Order (Phase 0 → 8)
Phase	What	Files
0	Foundation	build.gradle, settings.gradle, gradle.properties, AndroidManifest.xml, RaazApplication.kt, LocaleManager.kt, FontProvider.kt, themes (light+dark+fa), Dana fonts
1	Security core	AuthDatabase.kt, PasswordManager.kt, KeyManager.kt, RaazDatabase.kt, MessageCrypto.kt, CryptoManager.kt
2	Lock + Setup screens	LockFragment+VM, SetupFragment+VM, nav_graph.xml, MainActivity.kt, SessionLockManager.kt
3	Data + Network	All DAOs, RaazPreferences.kt, RaazApiService.kt, all Repositories, NetworkMonitor.kt
4	Go Server	main.go, db layer, all handlers, cleanup_service.go, Dockerfile, Makefile
5	Chat UI	ChatsFragment+VM+Adapter, ChatFragment+VM+Adapter, SyncWorker, SendWorker, RaazNotificationManager
6	Contact Exchange	QrCodeHelper.kt, AddContactFragment+VM, CameraX+ZXing QR scan, invite code (Base58)
7	Settings + Logs	SettingsFragment+VM, LogViewerFragment+VM+Adapter, DateFormatter.kt, CleanupWorker.kt
8	Hardening	network_security_config.xml, backup_rules.xml, proguard-rules.pro, unit + instrumented tests
Verification Plan
Crypto round-trip test: Unit test in CryptoManagerTest.kt — encrypt a message with party A's keys, decrypt with party B's keys, verify plaintext matches
Password/brute-force test: Instrumented test — 9 wrong passwords → verify DB wiped; correct password → verify DB opens
Server API test: curl or Postman — register device, push message, pull message, ACK → verify message deleted from server
E2E flow test: Two emulators/devices → add contact via QR → send message → verify delivery and decryption
Lock test: Set lock timeout to 1 min → wait → verify LockFragment appears; verify notification shows no message content
RTL test: Switch language to Persian → verify layout is RTL, Dana font active, Jalali dates shown
Build: ./gradlew assembleDebug must succeed cleanly; go build ./cmd/raazd/ must produce a binary
ProGuard Rules (critical)
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class net.sqlcipher.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-keepattributes Signature
-keepclassmembers class io.raaz.messenger.data.network.** { *; }