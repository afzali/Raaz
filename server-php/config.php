<?php

// ═══════════════════════════════════════════════════════════════════════════
// █  دستی: حالت محیط را تنظیم کن  █
// ═══════════════════════════════════════════════════════════════════════════
// 'xampp'     = توسعه محلی (Windows/XAMPP، زیرپوشه htdocs)
// 'cpanel'    = هاست اشتراکی (cPanel، خارج از public_html)
// 'subdomain' = ساب‌دامین مستقل (مثل api.raaz.app)
// ───────────────────────────────────────────────────────────────────────────
$RAAZ_ENV = 'subdomain';  // ←←← اینجا را دستی تغییر بده

// ─── Environment detection ─────────────────────────────────────────────────
// XAMPP (Windows): db lives inside htdocs/raaz/  — writable, for dev only
// cPanel/Linux:    db lives one level above public_html — outside web root
if (!function_exists('raazDbPath')) {
    function raazDbPath(string $env): string {
        $docRoot = $_SERVER['DOCUMENT_ROOT'] ?? '';
        // XAMPP on Windows: C:\xampp\htdocs  or  D:\xampp\htdocs
        if ($env === 'xampp' || PHP_OS_FAMILY === 'Windows' || str_contains(strtolower($docRoot), 'xampp')) {
            return __DIR__ . '/raaz_server.db';   // same folder as index.php (dev only)
        }
        // Production (cPanel/Linux): one level above public_html
        return dirname($docRoot) . '/raaz_server.db';
    }
}

return [
    // ── Environment ───────────────────────────────────────────────────────
    'env'               => $RAAZ_ENV,

    // ── Database ──────────────────────────────────────────────────────────
    'db_path'           => raazDbPath($RAAZ_ENV),

    // ── Message TTL ───────────────────────────────────────────────────────
    'message_ttl'       => 86400,       // seconds — 24 h default

    // ── Payload limits ────────────────────────────────────────────────────
    // Text messages: 64 KB  |  Voice/file: up to 50 MB (set to 0 to disable file upload)
    'max_payload_kb'    => 64,          // for inline ciphertext (text messages)
    'max_file_mb'       => 50,          // for chunked file uploads (0 = disabled)

    // ── Rate limits (per IP per window) ───────────────────────────────────
    // Format: [max_requests, window_seconds]
    // Per-day limits are expressed as [N, 86400]
    'rate_limits'       => [
        'register'  => [10,    3600],   // 10 registrations / IP / hour
        'send'      => [200,   86400],  // 200 messages / IP / day  (anti-flood)
        'send_burst'=> [30,    60],     // 30 messages / IP / minute (burst guard)
        'pull'      => [500,   86400],  // 500 pulls / IP / day
        'pull_burst'=> [60,    60],     // 60 pulls / IP / minute
        'pubkey'    => [100,   3600],   // 100 pubkey lookups / IP / hour
        'upload'    => [50,    86400],  // 50 file uploads / IP / day
        'default'   => [300,   86400],
    ],

    // ── API prefix ────────────────────────────────────────────────────────
    'api_prefix'        => '/api/v1',
];
