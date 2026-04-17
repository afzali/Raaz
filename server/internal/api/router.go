package api

import (
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/raaz-io/raazd/internal/db"
	"github.com/raaz-io/raazd/internal/model"
)

func NewRouter(deviceRepo *db.DeviceRepo, msgRepo *db.MessageRepo, defaultTTL int64, version string) http.Handler {
	r := chi.NewRouter()

	r.Use(middleware.RealIP)
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)
	r.Use(middleware.Timeout(30 * time.Second))
	r.Use(securityHeaders)

	deviceHandler := NewDeviceHandler(deviceRepo)
	messageHandler := NewMessageHandler(msgRepo, deviceRepo, defaultTTL)
	authMiddleware := AuthMiddleware(deviceRepo)

	r.Route("/api/v1", func(r chi.Router) {
		// Public
		r.Get("/health", func(w http.ResponseWriter, req *http.Request) {
			jsonOK(w, model.HealthResponse{
				Status:  "ok",
				Version: version,
				Time:    time.Now().Unix(),
			})
		})
		r.Post("/devices/register", deviceHandler.Register)

		// Authenticated
		r.Group(func(r chi.Router) {
			r.Use(authMiddleware)
			r.Post("/messages", messageHandler.Push)
			r.Get("/messages", messageHandler.Pull)
			r.Delete("/messages/{id}", messageHandler.Ack)
		})
	})

	return r
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}
