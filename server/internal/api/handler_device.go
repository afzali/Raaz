package api

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"time"

	"github.com/raaz-io/raazd/internal/db"
	"github.com/raaz-io/raazd/internal/model"
)

type DeviceHandler struct {
	repo *db.DeviceRepo
}

func NewDeviceHandler(repo *db.DeviceRepo) *DeviceHandler {
	return &DeviceHandler{repo}
}

func (h *DeviceHandler) Register(w http.ResponseWriter, r *http.Request) {
	var req model.RegisterDeviceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.DeviceID == "" || req.UserID == "" || req.PublicKey == "" {
		jsonErr(w, http.StatusBadRequest, "device_id, user_id and public_key are required")
		return
	}

	// Generate a 256-bit random bearer token
	tokenBytes := make([]byte, 32)
	if _, err := rand.Read(tokenBytes); err != nil {
		jsonErr(w, http.StatusInternalServerError, "failed to generate token")
		return
	}
	token := base64.URLEncoding.EncodeToString(tokenBytes)
	tokenHash := sha256Hex(token)

	device := &model.Device{
		ID:           req.DeviceID,
		UserID:       req.UserID,
		PublicKey:    req.PublicKey,
		TokenHash:    tokenHash,
		RegisteredAt: time.Now().Unix(),
	}

	if err := h.repo.Create(device); err != nil {
		// If already registered, just issue a new token would require update logic.
		// For now return conflict.
		jsonErr(w, http.StatusConflict, "device already registered")
		return
	}

	jsonCreated(w, model.RegisterDeviceResponse{
		DeviceID: req.DeviceID,
		Token:    token,
	})
}
