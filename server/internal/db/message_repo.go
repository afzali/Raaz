package db

import (
	"database/sql"
	"time"

	"github.com/raaz-io/raazd/internal/model"
)

type MessageRepo struct{ db *sql.DB }

func NewMessageRepo(db *sql.DB) *MessageRepo { return &MessageRepo{db} }

func (r *MessageRepo) Create(m *model.Message) error {
	_, err := r.db.Exec(
		`INSERT INTO messages (id, recipient_device, sender_device, ciphertext, created_at, expires_at, acked) VALUES (?,?,?,?,?,?,0)`,
		m.ID, m.RecipientDevice, m.SenderDevice, m.Ciphertext, m.CreatedAt, m.ExpiresAt,
	)
	return err
}

func (r *MessageRepo) GetPending(recipientDeviceID string) ([]model.Message, error) {
	now := time.Now().Unix()
	rows, err := r.db.Query(
		`SELECT id, recipient_device, sender_device, ciphertext, created_at, expires_at FROM messages WHERE recipient_device=? AND acked=0 AND expires_at>? ORDER BY created_at ASC`,
		recipientDeviceID, now,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var msgs []model.Message
	for rows.Next() {
		var m model.Message
		if err := rows.Scan(&m.ID, &m.RecipientDevice, &m.SenderDevice, &m.Ciphertext, &m.CreatedAt, &m.ExpiresAt); err != nil {
			continue
		}
		msgs = append(msgs, m)
	}
	return msgs, nil
}

func (r *MessageRepo) Ack(id string, recipientDeviceID string) error {
	_, err := r.db.Exec(
		`UPDATE messages SET acked=1 WHERE id=? AND recipient_device=?`,
		id, recipientDeviceID,
	)
	return err
}

func (r *MessageRepo) DeleteAcked() (int64, error) {
	res, err := r.db.Exec(`DELETE FROM messages WHERE acked=1`)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

func (r *MessageRepo) DeleteExpired() (int64, error) {
	now := time.Now().Unix()
	res, err := r.db.Exec(`DELETE FROM messages WHERE expires_at<?`, now)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}
