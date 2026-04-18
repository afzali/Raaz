<?php
declare(strict_types=1);

// CORS
header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS');
header('Access-Control-Allow-Headers: Authorization, Content-Type');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

require_once __DIR__ . '/src/Database.php';
require_once __DIR__ . '/src/Router.php';
require_once __DIR__ . '/src/Auth.php';
require_once __DIR__ . '/src/handlers/HealthHandler.php';
require_once __DIR__ . '/src/handlers/DeviceHandler.php';
require_once __DIR__ . '/src/handlers/MessageHandler.php';

$config  = require __DIR__ . '/config.php';
$db      = Database::get($config['db_path']);

Database::cleanupExpired($db);

$deviceHandler  = new DeviceHandler($db);
$messageHandler = new MessageHandler($db, $config['message_ttl'], $config['max_payload_kb']);

$router = new Router($config['api_prefix']);

$router->add('GET',    '/health',         ['HealthHandler',  'handle']);
$router->add('POST',   '/devices/register', fn($p) => $deviceHandler->register($p));
$router->add('POST',   '/messages',          fn($p) => $messageHandler->send($p));
$router->add('GET',    '/messages',          fn($p) => $messageHandler->pull($p));
$router->add('DELETE', '/messages/{id}',     fn($p) => $messageHandler->ack($p));
$router->add('GET',    '/receipts',           fn($p) => $messageHandler->receipts($p));

$router->dispatch();
