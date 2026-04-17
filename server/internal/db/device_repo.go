package db

import (
	"database/sql"
	"time"

	"github.com/raaz-io/raazd/internal/model"
)

type DeviceRepo struct{ db *sql.DB }

func NewDeviceRepo(db *sql.DB) *DeviceRepo { return &DeviceRepo{db} }

func (r *DeviceRepo) Create(d *model.Device) error {
	_, err := r.db.Exec(
		`INSERT INTO devices (id, user_id, public_key, token_hash, registered_at) VALUES (?,?,?,?,?)`,
		d.ID, d.UserID, d.PublicKey, d.TokenHash, d.RegisteredAt,
	)
	return err
}

func (r *DeviceRepo) GetByID(id string) (*model.Device, error) {
	row := r.db.QueryRow(`SELECT id, user_id, public_key, token_hash, registered_at, COALESCE(last_active,0) FROM devices WHERE id=?`, id)
	var d model.Device
	err := row.Scan(&d.ID, &d.UserID, &d.PublicKey, &d.TokenHash, &d.RegisteredAt, &d.LastActive)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return &d, err
}

func (r *DeviceRepo) GetByTokenHash(hash string) (*model.Device, error) {
	row := r.db.QueryRow(`SELECT id, user_id, public_key, token_hash, registered_at, COALESCE(last_active,0) FROM devices WHERE token_hash=?`, hash)
	var d model.Device
	err := row.Scan(&d.ID, &d.UserID, &d.PublicKey, &d.TokenHash, &d.RegisteredAt, &d.LastActive)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return &d, err
}

func (r *DeviceRepo) UpdateLastActive(id string) {
	r.db.Exec(`UPDATE devices SET last_active=? WHERE id=?`, time.Now().Unix(), id)
}
