package model

type Device struct {
	ID           string `json:"device_id"`
	UserID       string `json:"user_id"`
	PublicKey    string `json:"public_key"`
	TokenHash    string `json:"-"`
	RegisteredAt int64  `json:"registered_at"`
	LastActive   int64  `json:"last_active"`
}

type Message struct {
	ID              string `json:"server_message_id"`
	RecipientDevice string `json:"recipient_device_id"`
	SenderDevice    string `json:"sender_device_id"`
	Ciphertext      string `json:"ciphertext"`
	CreatedAt       int64  `json:"created_at"`
	ExpiresAt       int64  `json:"expires_at"`
	Acked           bool   `json:"-"`
}

// Request/Response DTOs

type RegisterDeviceRequest struct {
	UserID    string `json:"user_id"`
	DeviceID  string `json:"device_id"`
	PublicKey string `json:"public_key"`
}

type RegisterDeviceResponse struct {
	DeviceID string `json:"device_id"`
	Token    string `json:"token"`
}

type PushMessageRequest struct {
	MessageID         string `json:"message_id"`
	RecipientDeviceID string `json:"recipient_device_id"`
	SenderDeviceID    string `json:"sender_device_id"`
	Ciphertext        string `json:"ciphertext"`
	TTLSeconds        int64  `json:"ttl_seconds"`
}

type PushMessageResponse struct {
	ServerMessageID string `json:"server_message_id"`
	ExpiresAt       int64  `json:"expires_at"`
}

type PullMessagesResponse struct {
	Messages []PullMessage `json:"messages"`
}

type PullMessage struct {
	ServerMessageID string `json:"server_message_id"`
	SenderDeviceID  string `json:"sender_device_id"`
	Ciphertext      string `json:"ciphertext"`
	CreatedAt       int64  `json:"created_at"`
	ExpiresAt       int64  `json:"expires_at"`
}

type HealthResponse struct {
	Status  string `json:"status"`
	Version string `json:"version"`
	Time    int64  `json:"time"`
}

type ErrorResponse struct {
	Error string `json:"error"`
}
