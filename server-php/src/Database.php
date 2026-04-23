<?php

class Database {
    private static ?PDO $pdo = null;

    public static function get(string $dbPath): PDO {
        if (self::$pdo === null) {
            self::$pdo = new PDO('sqlite:' . $dbPath);
            self::$pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
            self::$pdo->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
            self::migrate(self::$pdo);
        }
        return self::$pdo;
    }

    private static function migrate(PDO $db): void {
        // WAL mode avoided — shared hosting NFS may not support it
        $db->exec("PRAGMA secure_delete=ON");
        $db->exec("PRAGMA foreign_keys=ON");

        $db->exec("CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            public_key TEXT NOT NULL,
            token_hash TEXT NOT NULL,
            registered_at INTEGER NOT NULL,
            last_active INTEGER
        )");

        $db->exec("CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY,
            recipient_device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            sender_device TEXT NOT NULL,
            ciphertext TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL,
            acked INTEGER NOT NULL DEFAULT 0
        )");

        $db->exec("CREATE INDEX IF NOT EXISTS idx_msg_recipient ON messages(recipient_device, acked)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_msg_expires ON messages(expires_at)");

        $db->exec("CREATE TABLE IF NOT EXISTS receipts (
            message_id TEXT NOT NULL,
            sender_device TEXT NOT NULL,
            acked_at INTEGER NOT NULL,
            PRIMARY KEY (message_id, sender_device)
        )");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_receipts_sender ON receipts(sender_device)");

        // File transfer tables (migrated lazily — safe to run on existing DBs)
        FileHandler::migrate($db);
    }

    public static function cleanupExpired(PDO $db, string $storageDir = ''): void {
        $db->prepare("DELETE FROM messages WHERE expires_at < ?")->execute([time()]);
        if ($storageDir) {
            FileHandler::cleanupExpired($db, $storageDir);
        }
    }
}
