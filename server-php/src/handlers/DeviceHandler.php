<?php

class DeviceHandler {
    private PDO $db;

    public function __construct(PDO $db) {
        $this->db = $db;
    }

    // POST /devices/register — first-time registration only
    public function register(array $params): void {
        $body = json_decode(file_get_contents('php://input'), true);

        $userId    = trim($body['user_id']    ?? $body['userId']    ?? '');
        $deviceId  = trim($body['device_id']  ?? $body['deviceId']  ?? '');
        $publicKey = trim($body['public_key'] ?? $body['publicKey'] ?? '');

        if (!$userId || !$deviceId || !$publicKey) {
            http_response_code(400);
            echo json_encode(['error' => 'missing_fields', 'detail' => 'user_id, device_id, public_key required']);
            return;
        }

        if (strlen($publicKey) > 512) {
            http_response_code(400);
            echo json_encode(['error' => 'invalid_public_key']);
            return;
        }

        // If device already exists: reject — must use PUT /devices/update with current token
        $stmt = $this->db->prepare("SELECT id FROM devices WHERE id = ?");
        $stmt->execute([$deviceId]);
        if ($stmt->fetch()) {
            http_response_code(409);
            echo json_encode([
                'error'  => 'device_exists',
                'detail' => 'Device already registered. Use PUT /devices/update with your current token to rotate keys.',
            ]);
            return;
        }

        $token     = Auth::generateToken();
        $tokenHash = hash('sha256', $token);
        $now       = time();

        $this->db->prepare(
            "INSERT INTO devices (id, user_id, public_key, token_hash, registered_at, last_active)
             VALUES (?, ?, ?, ?, ?, ?)"
        )->execute([$deviceId, $userId, $publicKey, $tokenHash, $now, $now]);

        http_response_code(201);
        echo json_encode([
            'token'     => $token,
            'device_id' => $deviceId,
        ]);
    }

    // PUT /devices/update — authenticated key rotation + new token
    public function update(array $params): void {
        $device = Auth::requireDevice($this->db);
        $body   = json_decode(file_get_contents('php://input'), true);

        $newPublicKey = trim($body['public_key'] ?? $body['publicKey'] ?? '');

        if (!$newPublicKey) {
            http_response_code(400);
            echo json_encode(['error' => 'missing_fields', 'detail' => 'public_key required']);
            return;
        }

        if (strlen($newPublicKey) > 512) {
            http_response_code(400);
            echo json_encode(['error' => 'invalid_public_key']);
            return;
        }

        $newToken     = Auth::generateToken();
        $newTokenHash = hash('sha256', $newToken);
        $now          = time();

        $this->db->prepare(
            "UPDATE devices SET public_key=?, token_hash=?, last_active=? WHERE id=?"
        )->execute([$newPublicKey, $newTokenHash, $now, $device['id']]);

        echo json_encode([
            'token'     => $newToken,
            'device_id' => $device['id'],
        ]);
    }

    // GET /devices/{userId}/pubkey — fetch public key for a user (to encrypt messages to them)
    // Requires auth — only registered devices can look up keys
    public function pubkey(array $params): void {
        Auth::requireDevice($this->db);

        $userId = $params['userId'] ?? '';
        if (!$userId) {
            http_response_code(400);
            echo json_encode(['error' => 'missing_user_id']);
            return;
        }

        // Return all active devices for this userId (a user may have multiple devices)
        $stmt = $this->db->prepare(
            "SELECT id AS device_id, public_key, last_active
             FROM devices WHERE user_id = ?
             ORDER BY last_active DESC"
        );
        $stmt->execute([$userId]);
        $rows = $stmt->fetchAll();

        if (empty($rows)) {
            http_response_code(404);
            echo json_encode(['error' => 'user_not_found']);
            return;
        }

        echo json_encode([
            'user_id' => $userId,
            'devices' => array_map(fn($r) => [
                'device_id'   => $r['device_id'],
                'public_key'  => $r['public_key'],
                'last_active' => (int)$r['last_active'],
            ], $rows),
        ]);
    }
}
