<?php

class DeviceHandler {
    private PDO $db;

    public function __construct(PDO $db) {
        $this->db = $db;
    }

    public function register(array $params): void {
        $body = json_decode(file_get_contents('php://input'), true);

        $userId    = trim($body['userId'] ?? '');
        $deviceId  = trim($body['deviceId'] ?? '');
        $publicKey = trim($body['publicKey'] ?? '');

        if (!$userId || !$deviceId || !$publicKey) {
            http_response_code(400);
            echo json_encode(['error' => 'missing_fields']);
            return;
        }

        // Check for existing device registration
        $stmt = $this->db->prepare("SELECT id FROM devices WHERE id = ?");
        $stmt->execute([$deviceId]);
        if ($stmt->fetch()) {
            http_response_code(409);
            echo json_encode(['error' => 'device_exists']);
            return;
        }

        $token     = Auth::generateToken();
        $tokenHash = hash('sha256', $token);

        $this->db->prepare(
            "INSERT INTO devices (id, user_id, public_key, token_hash, registered_at)
             VALUES (?, ?, ?, ?, ?)"
        )->execute([$deviceId, $userId, $publicKey, $tokenHash, time()]);

        http_response_code(201);
        echo json_encode([
            'token'    => $token,
            'deviceId' => $deviceId,
        ]);
    }
}
