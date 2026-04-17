<?php

class DeviceHandler {
    private PDO $db;

    public function __construct(PDO $db) {
        $this->db = $db;
    }

    public function register(array $params): void {
        $body = json_decode(file_get_contents('php://input'), true);

        $userId    = trim($body['user_id']   ?? $body['userId']   ?? '');
        $deviceId  = trim($body['device_id'] ?? $body['deviceId'] ?? '');
        $publicKey = trim($body['public_key'] ?? $body['publicKey'] ?? '');

        if (!$userId || !$deviceId || !$publicKey) {
            http_response_code(400);
            echo json_encode(['error' => 'missing_fields']);
            return;
        }

        $token     = Auth::generateToken();
        $tokenHash = hash('sha256', $token);
        $now       = time();

        // If device already exists: update public_key and issue new token
        $stmt = $this->db->prepare("SELECT id FROM devices WHERE id = ?");
        $stmt->execute([$deviceId]);
        $existing = $stmt->fetch();

        if ($existing) {
            $this->db->prepare(
                "UPDATE devices SET public_key=?, token_hash=?, last_active=? WHERE id=?"
            )->execute([$publicKey, $tokenHash, $now, $deviceId]);
        } else {
            $this->db->prepare(
                "INSERT INTO devices (id, user_id, public_key, token_hash, registered_at, last_active)
                 VALUES (?, ?, ?, ?, ?, ?)"
            )->execute([$deviceId, $userId, $publicKey, $tokenHash, $now, $now]);
        }

        http_response_code(201);
        echo json_encode([
            'token'    => $token,
            'device_id' => $deviceId,
        ]);
    }
}
