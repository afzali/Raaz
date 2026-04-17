<?php
// DB path: one level above public_html for security
// On cPanel: /home/username/raaz_server.db
// Change this to match your actual home directory path
$homeDir = dirname($_SERVER['DOCUMENT_ROOT']);

return [
    'db_path'        => $homeDir . '/raaz_server.db',
    'message_ttl'    => 86400,   // 24h
    'max_payload_kb' => 64,
    'api_prefix'     => '/api/v1',
];
