package db

import (
	"database/sql"
	"log"
)

const schema = `
CREATE TABLE IF NOT EXISTS devices (
    id            TEXT PRIMARY KEY,
    user_id       TEXT NOT NULL,
    public_key    TEXT NOT NULL,
    token_hash    TEXT NOT NULL,
    registered_at INTEGER NOT NULL,
    last_active   INTEGER
);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);

CREATE TABLE IF NOT EXISTS messages (
    id               TEXT PRIMARY KEY,
    recipient_device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    sender_device    TEXT NOT NULL,
    ciphertext       TEXT NOT NULL,
    created_at       INTEGER NOT NULL,
    expires_at       INTEGER NOT NULL,
    acked            INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_messages_recipient ON messages(recipient_device, acked, expires_at);
CREATE INDEX IF NOT EXISTS idx_messages_expires   ON messages(expires_at);
`

func Migrate(db *sql.DB) {
	if _, err := db.Exec(schema); err != nil {
		log.Fatalf("Failed to run schema migration: %v", err)
	}
	log.Println("Database schema ready")
}
