package api

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/raaz-io/raazd/internal/db"
	"github.com/raaz-io/raazd/internal/model"
)

type MessageHandler struct {
	msgRepo    *db.MessageRepo
	deviceRepo *db.DeviceRepo
	defaultTTL int64
}

func NewMessageHandler(msgRepo *db.MessageRepo, deviceRepo *db.DeviceRepo, defaultTTL int64) *MessageHandler {
	return &MessageHandler{msgRepo, deviceRepo, defaultTTL}
}

func (h *MessageHandler) Push(w http.ResponseWriter, r *http.Request) {
	device := r.Context().Value(ctxDevice).(*model.Device)

	var req model.PushMessageRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.RecipientDeviceID == "" || req.Ciphertext == "" {
		jsonErr(w, http.StatusBadRequest, "recipient_device_id and ciphertext are required")
		return
	}

	// Verify recipient device exists
	recipient, err := h.deviceRepo.GetByID(req.RecipientDeviceID)
	if err != nil || recipient == nil {
		jsonErr(w, http.StatusNotFound, "recipient device not found")
		return
	}

	ttl := req.TTLSeconds
	if ttl <= 0 || ttl > 7*24*3600 {
		ttl = h.defaultTTL
	}

	now := time.Now().Unix()
	serverID := uuid.New().String()
	msg := &model.Message{
		ID:              serverID,
		RecipientDevice: req.RecipientDeviceID,
		SenderDevice:    device.ID,
		Ciphertext:      req.Ciphertext,
		CreatedAt:       now,
		ExpiresAt:       now + ttl,
	}

	if err := h.msgRepo.Create(msg); err != nil {
		jsonErr(w, http.StatusInternalServerError, "failed to store message")
		return
	}

	jsonAccepted(w, model.PushMessageResponse{
		ServerMessageID: serverID,
		ExpiresAt:       msg.ExpiresAt,
	})
}

func (h *MessageHandler) Pull(w http.ResponseWriter, r *http.Request) {
	device := r.Context().Value(ctxDevice).(*model.Device)

	msgs, err := h.msgRepo.GetPending(device.ID)
	if err != nil {
		jsonErr(w, http.StatusInternalServerError, "failed to fetch messages")
		return
	}

	pull := make([]model.PullMessage, 0, len(msgs))
	for _, m := range msgs {
		pull = append(pull, model.PullMessage{
			ServerMessageID: m.ID,
			SenderDeviceID:  m.SenderDevice,
			Ciphertext:      m.Ciphertext,
			CreatedAt:       m.CreatedAt,
			ExpiresAt:       m.ExpiresAt,
		})
	}

	jsonOK(w, model.PullMessagesResponse{Messages: pull})
}

func (h *MessageHandler) Ack(w http.ResponseWriter, r *http.Request) {
	device := r.Context().Value(ctxDevice).(*model.Device)
	msgID := chi.URLParam(r, "id")

	if err := h.msgRepo.Ack(msgID, device.ID); err != nil {
		jsonErr(w, http.StatusInternalServerError, "failed to ack message")
		return
	}

	noContent(w)
}
