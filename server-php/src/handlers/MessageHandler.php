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

        $recipientId = trim($body['recipient_device_id'] ?? $body['recipientDeviceId'] ?? '');
        $ciphertext  = trim($body['ciphertext'] ?? '');
        $messageId   = trim($body['message_id'] ?? $body['messageId'] ?? '');

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

        // Use client-provided messageId if valid, otherwise generate one
        $id  = ($messageId && strlen($messageId) <= 64) ? $messageId : bin2hex(random_bytes(16));
        $now = time();
        $expiresAt = $now + $this->ttl;

        $this->db->prepare(
            "INSERT OR IGNORE INTO messages (id, recipient_device, sender_device, ciphertext, created_at, expires_at)
             VALUES (?, ?, ?, ?, ?, ?)"
        )->execute([$id, $recipientId, $device['id'], $ciphertext, $now, $expiresAt]);

        http_response_code(201);
        // server_message_id — matches Android ApiModels.PushMessageResponse
        echo json_encode([
            'server_message_id' => $id,
            'expires_at'        => $expiresAt,
        ]);
    }

    public function pull(array $params): void {
        $device = Auth::requireDevice($this->db);

        $stmt = $this->db->prepare(
            "SELECT id          AS server_message_id,
                    sender_device AS sender_device_id,
                    ciphertext,
                    created_at,
                    expires_at
             FROM messages
             WHERE recipient_device = ? AND acked = 0
             ORDER BY created_at ASC
             LIMIT 100"
        );
        $stmt->execute([$device['id']]);
        $rows = $stmt->fetchAll();

        // Cast timestamps to int
        $messages = array_map(function($row) {
            $row['created_at'] = (int)$row['created_at'];
            $row['expires_at'] = (int)$row['expires_at'];
            return $row;
        }, $rows);

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

        // Get sender before deleting so we can write a receipt
        $stmt = $this->db->prepare("SELECT sender_device FROM messages WHERE id = ? AND recipient_device = ?");
        $stmt->execute([$id, $device['id']]);
        $msg = $stmt->fetch();

        $this->db->prepare("DELETE FROM messages WHERE id = ? AND recipient_device = ?")->execute([$id, $device['id']]);

        // Store receipt so sender can poll and mark message as confirmed
        if ($msg) {
            $this->db->prepare(
                "INSERT OR IGNORE INTO receipts (message_id, sender_device, acked_at) VALUES (?, ?, ?)"
            )->execute([$id, $msg['sender_device'], time()]);
        }

        echo json_encode(['ok' => true]);
    }

    public function receipts(array $params): void {
        $device = Auth::requireDevice($this->db);

        $stmt = $this->db->prepare(
            "SELECT message_id, acked_at FROM receipts WHERE sender_device = ? ORDER BY acked_at ASC LIMIT 200"
        );
        $stmt->execute([$device['id']]);
        $rows = $stmt->fetchAll();

        // Delete returned receipts — they've been delivered to sender
        if (!empty($rows)) {
            $ids = array_map(fn($r) => $r['message_id'], $rows);
            $placeholders = implode(',', array_fill(0, count($ids), '?'));
            $this->db->prepare("DELETE FROM receipts WHERE message_id IN ($placeholders) AND sender_device = ?")
                ->execute([...$ids, $device['id']]);
        }

        echo json_encode(['receipts' => array_map(fn($r) => [
            'message_id' => $r['message_id'],
            'acked_at'   => (int)$r['acked_at'],
        ], $rows)]);
    }
}
