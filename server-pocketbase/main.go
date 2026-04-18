package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"time"

	dbx "github.com/pocketbase/dbx"
	"github.com/pocketbase/pocketbase"
	"github.com/pocketbase/pocketbase/apis"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/tools/cron"
)

const maxPayloadBytes = 64 * 1024 // 64 KB
const defaultTTLSeconds = 86400   // 24h

func main() {
	app := pocketbase.New()

	// ── Register migrations (schema setup) ─────────────────────────────
	app.OnBootstrap().BindFunc(func(e *core.BootstrapEvent) error {
		if err := e.Next(); err != nil {
			return err
		}
		return ensureCollections(e.App)
	})

	// ── Custom API routes ───────────────────────────────────────────────
	app.OnServe().BindFunc(func(se *core.ServeEvent) error {
		g := se.Router.Group("/api/v1")

		// POST /api/v1/devices/register
		g.POST("/devices/register", handleRegister(se.App))

		// Authenticated routes
		g.POST("/messages", requireDevice(se.App, handleSend(se.App)))
		g.GET("/messages", requireDevice(se.App, handlePull(se.App)))
		g.DELETE("/messages/{id}", requireDevice(se.App, handleAck(se.App)))
		g.GET("/health", handleHealth())

		return se.Next()
	})

	// ── Delete acked messages immediately after ACK ─────────────────────
	app.OnRecordAfterUpdateSuccess("messages").BindFunc(func(e *core.RecordEvent) error {
		if e.Record.GetBool("acked") {
			_ = e.App.Delete(e.Record)
		}
		return e.Next()
	})

	// ── Cleanup expired messages every 5 minutes ────────────────────────
	app.OnServe().BindFunc(func(se *core.ServeEvent) error {
		c := cron.New()
		c.MustAdd("cleanup", "*/5 * * * *", func() {
			now := time.Now().Unix()
			records, err := se.App.FindRecordsByFilter(
				"messages",
				"expires_at < {:now} && acked = false",
				"-created_at", 0, 0,
				dbx.Params{"now": now},
			)
			if err != nil {
				return
			}
			for _, r := range records {
				_ = se.App.Delete(r)
			}
		})
		c.Start()
		return se.Next()
	})

	if err := app.Start(); err != nil {
		log.Fatal(err)
	}
}

// ── Helpers ─────────────────────────────────────────────────────────────────

func hashToken(token string) string {
	h := sha256.Sum256([]byte(token))
	return hex.EncodeToString(h[:])
}

func generateToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// requireDevice middleware — validates Bearer token, injects device record into request context
func requireDevice(app core.App, next func(*core.RequestEvent) error) func(*core.RequestEvent) error {
	return func(e *core.RequestEvent) error {
		auth := e.Request.Header.Get("Authorization")
		if len(auth) < 8 || auth[:7] != "Bearer " {
			return apis.NewUnauthorizedError("missing bearer token", nil)
		}
		token := auth[7:]
		device, err := app.FindFirstRecordByFilter("devices", "token_hash = {:h}", dbx.Params{"h": hashToken(token)})
		if err != nil {
			return apis.NewUnauthorizedError("invalid token", nil)
		}
		// update last_active
		device.Set("last_active", time.Now().Unix())
		_ = app.Save(device)

		e.Set("device", device)
		return next(e)
	}
}

// ── Handlers ─────────────────────────────────────────────────────────────────

func handleRegister(app core.App) func(*core.RequestEvent) error {
	return func(e *core.RequestEvent) error {
		var body struct {
			UserId    string `json:"user_id"`
			DeviceId  string `json:"device_id"`
			PublicKey string `json:"public_key"`
		}
		if err := json.NewDecoder(e.Request.Body).Decode(&body); err != nil || body.UserId == "" || body.DeviceId == "" || body.PublicKey == "" {
			return apis.NewBadRequestError("missing fields", nil)
		}

		token, err := generateToken()
		if err != nil {
			return apis.NewInternalServerError("token generation failed", err)
		}
		tokenHash := hashToken(token)

		col, err := app.FindCollectionByNameOrId("devices")
		if err != nil {
			return apis.NewInternalServerError("collection not found", err)
		}

		// Upsert: update if device_id exists
		existing, _ := app.FindFirstRecordByFilter("devices", "device_id = {:id}", dbx.Params{"id": body.DeviceId})
		var record *core.Record
		if existing != nil {
			record = existing
		} else {
			record = core.NewRecord(col)
		}
		record.Set("user_id", body.UserId)
		record.Set("device_id", body.DeviceId)
		record.Set("public_key", body.PublicKey)
		record.Set("token_hash", tokenHash)
		record.Set("registered_at", time.Now().Unix())

		if err := app.Save(record); err != nil {
			return apis.NewInternalServerError("save failed", err)
		}

		return e.JSON(http.StatusCreated, map[string]string{"token": token})
	}
}

func handleSend(app core.App) func(*core.RequestEvent) error {
	return func(e *core.RequestEvent) error {
		sender := e.Get("device").(*core.Record)

		var body struct {
			RecipientDeviceId string `json:"recipient_device_id"`
			Ciphertext        string `json:"ciphertext"`
			MessageId         string `json:"message_id"`
			TtlSeconds        int64  `json:"ttl_seconds"`
		}
		if err := json.NewDecoder(e.Request.Body).Decode(&body); err != nil || body.RecipientDeviceId == "" || body.Ciphertext == "" {
			return apis.NewBadRequestError("missing fields", nil)
		}
		if len(body.Ciphertext) > maxPayloadBytes {
			return e.JSON(http.StatusRequestEntityTooLarge, map[string]string{"error": "payload_too_large"})
		}

		// Verify recipient exists
		recipient, err := app.FindFirstRecordByFilter("devices", "device_id = {:id}", dbx.Params{"id": body.RecipientDeviceId})
		if err != nil || recipient == nil {
			return apis.NewNotFoundError("recipient not found", nil)
		}

		ttl := body.TtlSeconds
		if ttl <= 0 {
			ttl = defaultTTLSeconds
		}
		now := time.Now().Unix()

		msgId := body.MessageId
		if msgId == "" || len(msgId) > 64 {
			msgId = generateId()
		}

		col, _ := app.FindCollectionByNameOrId("messages")
		msg := core.NewRecord(col)
		msg.Set("server_message_id", msgId)
		msg.Set("recipient_device_id", body.RecipientDeviceId)
		msg.Set("sender_device_id", sender.GetString("device_id"))
		msg.Set("ciphertext", body.Ciphertext)
		msg.Set("created_at_unix", now)
		msg.Set("expires_at", now+ttl)
		msg.Set("acked", false)

		if err := app.Save(msg); err != nil {
			return apis.NewInternalServerError("save failed", err)
		}

		return e.JSON(http.StatusCreated, map[string]any{
			"server_message_id": msgId,
			"expires_at":        now + ttl,
		})
	}
}

func handlePull(app core.App) func(*core.RequestEvent) error {
	return func(e *core.RequestEvent) error {
		device := e.Get("device").(*core.Record)
		deviceId := device.GetString("device_id")

		records, err := app.FindRecordsByFilter(
			"messages",
			"recipient_device_id = {:id} && acked = false",
			"created_at_unix", 100, 0,
			dbx.Params{"id": deviceId},
		)
		if err != nil {
			return apis.NewInternalServerError("query failed", err)
		}

		messages := make([]map[string]any, 0, len(records))
		for _, r := range records {
			messages = append(messages, map[string]any{
				"server_message_id": r.GetString("server_message_id"),
				"sender_device_id":  r.GetString("sender_device_id"),
				"ciphertext":        r.GetString("ciphertext"),
				"created_at":        r.GetInt("created_at_unix"),
				"expires_at":        r.GetInt("expires_at"),
			})
		}

		return e.JSON(http.StatusOK, map[string]any{"messages": messages})
	}
}

func handleAck(app core.App) func(*core.RequestEvent) error {
	return func(e *core.RequestEvent) error {
		device := e.Get("device").(*core.Record)
		deviceId := device.GetString("device_id")
		msgId := e.Request.PathValue("id")

		record, err := app.FindFirstRecordByFilter(
			"messages",
			"server_message_id = {:mid} && recipient_device_id = {:did}",
			dbx.Params{"mid": msgId, "did": deviceId},
		)
		if err != nil || record == nil {
			// idempotent — already deleted is OK
			return e.JSON(http.StatusOK, map[string]bool{"ok": true})
		}

		// Delete directly (no need to set acked, hook handles it)
		_ = app.Delete(record)
		return e.JSON(http.StatusOK, map[string]bool{"ok": true})
	}
}

func handleHealth() func(*core.RequestEvent) error {
	return func(e *core.RequestEvent) error {
		return e.JSON(http.StatusOK, map[string]string{"status": "ok"})
	}
}

func generateId() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// ── Collection bootstrap ──────────────────────────────────────────────────────

func ensureCollections(app core.App) error {
	if err := ensureDevices(app); err != nil {
		return err
	}
	return ensureMessages(app)
}

func ensureDevices(app core.App) error {
	if _, err := app.FindCollectionByNameOrId("devices"); err == nil {
		return nil // already exists
	}

	col := core.NewBaseCollection("devices")
	col.Fields.Add(
		&core.TextField{Name: "user_id", Required: true},
		&core.TextField{Name: "device_id", Required: true},
		&core.TextField{Name: "public_key", Required: true},
		&core.TextField{Name: "token_hash", Required: true},
		&core.NumberField{Name: "registered_at"},
		&core.NumberField{Name: "last_active"},
	)
	return app.Save(col)
}

func ensureMessages(app core.App) error {
	if _, err := app.FindCollectionByNameOrId("messages"); err == nil {
		return nil
	}

	col := core.NewBaseCollection("messages")
	col.Fields.Add(
		&core.TextField{Name: "server_message_id", Required: true},
		&core.TextField{Name: "recipient_device_id", Required: true},
		&core.TextField{Name: "sender_device_id", Required: true},
		&core.TextField{Name: "ciphertext", Required: true},
		&core.NumberField{Name: "created_at_unix"},
		&core.NumberField{Name: "expires_at"},
		&core.BoolField{Name: "acked"},
	)
	return app.Save(col)
}

