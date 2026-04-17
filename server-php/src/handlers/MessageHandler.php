<?php

class MessageHandler {
    private PDO $db;
    private int $ttl;
    private int $maxPayloadBytes;

    public function __construct(PDO $db, int $ttl, int $maxPayloadKb) {
        $this->db              = $db;
        $this->ttl             = $ttl;
        $this->maxPayloadBytes = $maxPayloadKb * 1024;
    }

    public function send(array $params): void {
        $device = Auth::requireDevice($this->db);
        $body   = json_decode(file_get_contents('php://input'), true);

        $recipientId = trim($body['recipientDeviceId'] ?? '');
        $ciphertext  = trim($body['ciphertext'] ?? '');

        if (!$recipientId || !$ciphertext) {
            http_response_code(400);
            echo json_encode(['error' => 'missing_fields']);
            return;
        }

        if (strlen($ciphertext) > $this->maxPayloadBytes) {
            http_response_code(413);
            echo json_encode(['error' => 'payload_too_large']);
            return;
        }

        $stmt = $this->db->prepare("SELECT id FROM devices WHERE id = ?");
        $stmt->execute([$recipientId]);
        if (!$stmt->fetch()) {
            http_response_code(404);
            echo json_encode(['error' => 'recipient_not_found']);
            return;
        }

        $id  = bin2hex(random_bytes(16));
        $now = time();

        $this->db->prepare(
            "INSERT INTO messages (id, recipient_device, sender_device, ciphertext, created_at, expires_at)
             VALUES (?, ?, ?, ?, ?, ?)"
        )->execute([$id, $recipientId, $device['id'], $ciphertext, $now, $now + $this->ttl]);

        http_response_code(201);
        echo json_encode(['id' => $id]);
    }

    public function pull(array $params): void {
        $device = Auth::requireDevice($this->db);

        $stmt = $this->db->prepare(
            "SELECT id,
                    sender_device   AS sender_device_id,
                    ciphertext,
                    created_at      AS createdAt
             FROM messages
             WHERE recipient_device = ? AND acked = 0
             ORDER BY created_at ASC
             LIMIT 100"
        );
        $stmt->execute([$device['id']]);
        $messages = $stmt->fetchAll();

        echo json_encode(['messages' => $messages]);
    }

    public function ack(array $params): void {
        $device = Auth::requireDevice($this->db);
        $id     = $params['id'] ?? '';

        if (!$id) {
            http_response_code(400);
            echo json_encode(['error' => 'missing_id']);
            return;
        }

        $stmt = $this->db->prepare(
            "DELETE FROM messages WHERE id = ? AND recipient_device = ?"
        );
        $stmt->execute([$id, $device['id']]);

        if ($stmt->rowCount() === 0) {
            http_response_code(404);
            echo json_encode(['error' => 'message_not_found']);
            return;
        }

        echo json_encode(['ok' => true]);
    }
}
