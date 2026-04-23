<?php

/**
 * Handles encrypted file uploads and downloads.
 *
 * Design:
 *  - Files are stored as encrypted blobs (client encrypts before upload, just like messages).
 *  - Server never sees plaintext — stores opaque binary chunks.
 *  - Upload: chunked (client sends fixed-size pieces) or single-shot for small files.
 *  - Download: supports HTTP Range requests so client can show real progress.
 *  - Files expire same as messages (TTL from config).
 *  - A file upload creates a "file message" that can be ACK'd like a regular message.
 */
class FileHandler {
    private PDO $db;
    private int $ttl;
    private int $maxFileBytes;
    private string $storageDir;

    public function __construct(PDO $db, int $ttl, int $maxFileMb) {
        $this->db           = $db;
        $this->ttl          = $ttl;
        $this->maxFileBytes = $maxFileMb * 1024 * 1024;

        // Files stored outside web root: one level above index.php
        $this->storageDir = dirname(__DIR__, 2) . '/raaz_files';

        if (!is_dir($this->storageDir)) {
            mkdir($this->storageDir, 0750, true);
        }
    }

    // ── Schema ────────────────────────────────────────────────────────────────
    public static function migrate(PDO $db): void {
        $db->exec("CREATE TABLE IF NOT EXISTS files (
            id                TEXT PRIMARY KEY,
            recipient_device  TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            sender_device     TEXT NOT NULL,
            file_name_enc     TEXT NOT NULL,     -- encrypted original filename
            mime_type_enc     TEXT NOT NULL,     -- encrypted MIME type
            size_bytes        INTEGER NOT NULL,
            chunk_count       INTEGER NOT NULL DEFAULT 1,
            chunks_received   INTEGER NOT NULL DEFAULT 0,
            upload_complete   INTEGER NOT NULL DEFAULT 0,
            created_at        INTEGER NOT NULL,
            expires_at        INTEGER NOT NULL,
            acked             INTEGER NOT NULL DEFAULT 0
        )");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_files_recipient ON files(recipient_device, acked)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_files_expires   ON files(expires_at)");
    }

    // POST /files/upload
    // Body: { recipient_device_id, file_id (optional), file_name_enc, mime_type_enc,
    //         size_bytes, chunk_index, chunk_count, chunk_data (base64) }
    public function upload(array $params): void {
        $device = Auth::requireDevice($this->db);
        $body   = json_decode(file_get_contents('php://input'), true);

        $recipientId  = trim($body['recipient_device_id'] ?? '');
        $fileNameEnc  = trim($body['file_name_enc']        ?? '');
        $mimeTypeEnc  = trim($body['mime_type_enc']         ?? '');
        $sizeBytes    = (int)($body['size_bytes']           ?? 0);
        $chunkIndex   = (int)($body['chunk_index']          ?? 0);   // 0-based
        $chunkCount   = (int)($body['chunk_count']          ?? 1);
        $chunkData    = $body['chunk_data']                 ?? '';    // base64-encoded encrypted chunk
        $clientFileId = trim($body['file_id']               ?? '');

        if (!$recipientId || !$fileNameEnc || !$mimeTypeEnc || !$chunkData) {
            http_response_code(400);
            echo json_encode(['error' => 'missing_fields']);
            return;
        }

        if ($this->maxFileBytes > 0 && $sizeBytes > $this->maxFileBytes) {
            http_response_code(413);
            echo json_encode([
                'error'    => 'file_too_large',
                'max_mb'   => $this->maxFileBytes / 1024 / 1024,
                'given_mb' => round($sizeBytes / 1024 / 1024, 2),
            ]);
            return;
        }

        // Validate recipient exists
        $stmt = $this->db->prepare("SELECT id FROM devices WHERE id = ?");
        $stmt->execute([$recipientId]);
        if (!$stmt->fetch()) {
            http_response_code(404);
            echo json_encode(['error' => 'recipient_not_found']);
            return;
        }

        // Use or create file record
        $fileId = ($clientFileId && strlen($clientFileId) <= 64) ? $clientFileId : bin2hex(random_bytes(16));
        $now    = time();

        $stmt = $this->db->prepare("SELECT id, chunks_received, chunk_count FROM files WHERE id = ?");
        $stmt->execute([$fileId]);
        $existing = $stmt->fetch();

        if (!$existing) {
            if ($chunkIndex !== 0) {
                http_response_code(400);
                echo json_encode(['error' => 'must_start_from_chunk_0']);
                return;
            }
            $this->db->prepare(
                "INSERT INTO files (id, recipient_device, sender_device, file_name_enc, mime_type_enc,
                 size_bytes, chunk_count, chunks_received, upload_complete, created_at, expires_at)
                 VALUES (?,?,?,?,?,?,?,0,0,?,?)"
            )->execute([$fileId, $recipientId, $device['id'], $fileNameEnc, $mimeTypeEnc,
                        $sizeBytes, $chunkCount, $now, $now + $this->ttl]);
        }

        // Write chunk to disk: raaz_files/{fileId}/{chunkIndex}.bin
        $fileDir   = $this->storageDir . '/' . $fileId;
        if (!is_dir($fileDir)) mkdir($fileDir, 0750, true);

        $chunkPath = $fileDir . '/' . $chunkIndex . '.bin';
        $decoded   = base64_decode($chunkData, true);
        if ($decoded === false) {
            http_response_code(400);
            echo json_encode(['error' => 'invalid_chunk_data']);
            return;
        }
        file_put_contents($chunkPath, $decoded);

        // Update progress
        $this->db->prepare(
            "UPDATE files SET chunks_received = chunks_received + 1,
             upload_complete = CASE WHEN chunks_received + 1 >= chunk_count THEN 1 ELSE 0 END
             WHERE id = ?"
        )->execute([$fileId]);

        // Return upload progress
        $stmt = $this->db->prepare("SELECT chunks_received, chunk_count, upload_complete FROM files WHERE id = ?");
        $stmt->execute([$fileId]);
        $row = $stmt->fetch();

        $progress = $row['chunk_count'] > 0
            ? round(($row['chunks_received'] / $row['chunk_count']) * 100, 1)
            : 100;

        http_response_code($row['upload_complete'] ? 201 : 202);
        echo json_encode([
            'file_id'         => $fileId,
            'chunk_index'     => $chunkIndex,
            'chunks_received' => (int)$row['chunks_received'],
            'chunk_count'     => (int)$row['chunk_count'],
            'progress_pct'    => $progress,
            'upload_complete' => (bool)$row['upload_complete'],
        ]);
    }

    // GET /files — list pending files for this device (like GET /messages)
    public function listPending(array $params): void {
        $device = Auth::requireDevice($this->db);

        $stmt = $this->db->prepare(
            "SELECT id AS file_id, sender_device AS sender_device_id,
                    file_name_enc, mime_type_enc, size_bytes,
                    chunk_count, created_at, expires_at
             FROM files
             WHERE recipient_device = ? AND acked = 0 AND upload_complete = 1
             ORDER BY created_at ASC LIMIT 50"
        );
        $stmt->execute([$device['id']]);
        $rows = $stmt->fetchAll();

        echo json_encode(['files' => array_map(fn($r) => [
            'file_id'         => $r['file_id'],
            'sender_device_id'=> $r['sender_device_id'],
            'file_name_enc'   => $r['file_name_enc'],
            'mime_type_enc'   => $r['mime_type_enc'],
            'size_bytes'      => (int)$r['size_bytes'],
            'chunk_count'     => (int)$r['chunk_count'],
            'created_at'      => (int)$r['created_at'],
            'expires_at'      => (int)$r['expires_at'],
        ], $rows)]);
    }

    // GET /files/{id}/chunk/{index} — download a single chunk with Range support
    // Returns raw binary (encrypted chunk). Client decrypts on their side.
    public function downloadChunk(array $params): void {
        $device  = Auth::requireDevice($this->db);
        $fileId  = $params['id']    ?? '';
        $chunkIdx= (int)($params['index'] ?? 0);

        if (!$fileId) { http_response_code(400); echo json_encode(['error' => 'missing_id']); return; }

        $stmt = $this->db->prepare(
            "SELECT id, recipient_device, size_bytes, chunk_count, upload_complete
             FROM files WHERE id = ? AND recipient_device = ?"
        );
        $stmt->execute([$fileId, $device['id']]);
        $file = $stmt->fetch();

        if (!$file) { http_response_code(404); echo json_encode(['error' => 'file_not_found']); return; }
        if (!$file['upload_complete']) { http_response_code(202); echo json_encode(['error' => 'upload_in_progress']); return; }

        $chunkPath = $this->storageDir . '/' . $fileId . '/' . $chunkIdx . '.bin';
        if (!file_exists($chunkPath)) {
            http_response_code(404);
            echo json_encode(['error' => 'chunk_not_found']);
            return;
        }

        $chunkSize = filesize($chunkPath);
        $totalChunks = (int)$file['chunk_count'];

        // Progress headers for client UI
        header('Content-Type: application/octet-stream');
        header('Content-Length: ' . $chunkSize);
        header('X-Chunk-Index: ' . $chunkIdx);
        header('X-Chunk-Count: ' . $totalChunks);
        header('X-Progress-Pct: ' . round((($chunkIdx + 1) / $totalChunks) * 100, 1));
        header('Cache-Control: no-store');

        // Support HTTP Range for resumable downloads
        $start = 0;
        $end   = $chunkSize - 1;
        if (isset($_SERVER['HTTP_RANGE'])) {
            preg_match('/bytes=(\d+)-(\d*)/', $_SERVER['HTTP_RANGE'], $m);
            $start = (int)($m[1] ?? 0);
            $end   = isset($m[2]) && $m[2] !== '' ? (int)$m[2] : $chunkSize - 1;
            header('HTTP/1.1 206 Partial Content');
            header('Content-Range: bytes ' . $start . '-' . $end . '/' . $chunkSize);
            header('Content-Length: ' . ($end - $start + 1));
        }

        $fp = fopen($chunkPath, 'rb');
        fseek($fp, $start);
        $remaining = $end - $start + 1;
        while ($remaining > 0 && !feof($fp)) {
            $read = fread($fp, min(65536, $remaining));
            echo $read;
            $remaining -= strlen($read);
        }
        fclose($fp);
    }

    // DELETE /files/{id} — ACK file receipt, triggers deletion
    public function ack(array $params): void {
        $device = Auth::requireDevice($this->db);
        $fileId = $params['id'] ?? '';

        if (!$fileId) { http_response_code(400); echo json_encode(['error' => 'missing_id']); return; }

        $stmt = $this->db->prepare("SELECT sender_device FROM files WHERE id = ? AND recipient_device = ?");
        $stmt->execute([$fileId, $device['id']]);
        $file = $stmt->fetch();

        if (!$file) { http_response_code(404); echo json_encode(['error' => 'file_not_found']); return; }

        // Write receipt (same receipts table as messages)
        $this->db->prepare(
            "INSERT OR IGNORE INTO receipts (message_id, sender_device, acked_at) VALUES (?,?,?)"
        )->execute([$fileId, $file['sender_device'], time()]);

        // Delete DB record
        $this->db->prepare("DELETE FROM files WHERE id = ? AND recipient_device = ?")->execute([$fileId, $device['id']]);

        // Delete chunks from disk
        $this->deleteFileChunks($fileId);

        echo json_encode(['ok' => true]);
    }

    // ── Cleanup expired files (called from index.php alongside message cleanup) ──
    public static function cleanupExpired(PDO $db, string $storageDir): void {
        $stmt = $db->prepare("SELECT id FROM files WHERE expires_at < ?");
        $stmt->execute([time()]);
        $rows = $stmt->fetchAll();
        foreach ($rows as $row) {
            self::deleteDir($storageDir . '/' . $row['id']);
        }
        $db->prepare("DELETE FROM files WHERE expires_at < ?")->execute([time()]);
    }

    private function deleteFileChunks(string $fileId): void {
        self::deleteDir($this->storageDir . '/' . $fileId);
    }

    private static function deleteDir(string $dir): void {
        if (!is_dir($dir)) return;
        foreach (glob($dir . '/*') as $f) { unlink($f); }
        rmdir($dir);
    }
}
