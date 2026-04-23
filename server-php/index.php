<?php
declare(strict_types=1);

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS');
header('Access-Control-Allow-Headers: Authorization, Content-Type');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

require_once __DIR__ . '/src/Database.php';
require_once __DIR__ . '/src/Router.php';
require_once __DIR__ . '/src/Auth.php';
require_once __DIR__ . '/src/RateLimiter.php';
require_once __DIR__ . '/src/handlers/HealthHandler.php';
require_once __DIR__ . '/src/handlers/DeviceHandler.php';
require_once __DIR__ . '/src/handlers/MessageHandler.php';
require_once __DIR__ . '/src/handlers/FileHandler.php';

$config = require __DIR__ . '/config.php';
$db     = Database::get($config['db_path']);
$limits = $config['rate_limits'];

// Storage dir for files (same base as DB, under raaz_files/)
$storageDir = dirname($config['db_path']) . '/raaz_files';

RateLimiter::init($db);
Database::cleanupExpired($db, $storageDir);
RateLimiter::cleanup($db);

$deviceHandler  = new DeviceHandler($db);
$messageHandler = new MessageHandler($db, $config['message_ttl'], $config['max_payload_kb']);
$fileHandler    = new FileHandler($db, $config['message_ttl'], $config['max_file_mb']);

$router = new Router($config['api_prefix']);

// ── Health ──────────────────────────────────────────────────────────────────
$router->add('GET',    '/health',                        ['HealthHandler', 'handle']);

// ── Device ──────────────────────────────────────────────────────────────────
$router->add('POST',   '/devices/register',              function($p) use ($db, $limits, $deviceHandler) {
    RateLimiter::check($db, 'register', $limits);
    $deviceHandler->register($p);
});
$router->add('PUT',    '/devices/update',                fn($p) => $deviceHandler->update($p));
$router->add('GET',    '/devices/{userId}/pubkey',       function($p) use ($db, $limits, $deviceHandler) {
    RateLimiter::check($db, 'pubkey', $limits);
    $deviceHandler->pubkey($p);
});

// ── Messages ─────────────────────────────────────────────────────────────────
$router->add('POST',   '/messages',                      function($p) use ($db, $limits, $messageHandler) {
    RateLimiter::check($db, 'send', $limits);
    $messageHandler->send($p);
});
$router->add('GET',    '/messages',                      function($p) use ($db, $limits, $messageHandler) {
    RateLimiter::check($db, 'pull', $limits);
    $messageHandler->pull($p);
});
$router->add('DELETE', '/messages/{id}',                 fn($p) => $messageHandler->ack($p));
$router->add('GET',    '/receipts',                      fn($p) => $messageHandler->receipts($p));

// ── Files ────────────────────────────────────────────────────────────────────
$router->add('POST',   '/files/upload',                  function($p) use ($db, $limits, $fileHandler) {
    RateLimiter::check($db, 'upload', $limits);
    $fileHandler->upload($p);
});
$router->add('GET',    '/files',                         fn($p) => $fileHandler->listPending($p));
$router->add('GET',    '/files/{id}/chunk/{index}',      fn($p) => $fileHandler->downloadChunk($p));
$router->add('DELETE', '/files/{id}',                    fn($p) => $fileHandler->ack($p));

$router->dispatch();
