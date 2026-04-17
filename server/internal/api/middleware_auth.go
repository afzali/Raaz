package api

import (
	"context"
	"crypto/sha256"
	"fmt"
	"net/http"
	"strings"

	"github.com/raaz-io/raazd/internal/db"
)

type contextKey string

const ctxDevice contextKey = "device"

func AuthMiddleware(deviceRepo *db.DeviceRepo) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			auth := r.Header.Get("Authorization")
			if !strings.HasPrefix(auth, "Bearer ") {
				jsonErr(w, http.StatusUnauthorized, "missing bearer token")
				return
			}
			token := strings.TrimPrefix(auth, "Bearer ")
			hash := sha256Hex(token)

			device, err := deviceRepo.GetByTokenHash(hash)
			if err != nil || device == nil {
				jsonErr(w, http.StatusUnauthorized, "invalid token")
				return
			}

			deviceRepo.UpdateLastActive(device.ID)
			ctx := context.WithValue(r.Context(), ctxDevice, device)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func sha256Hex(s string) string {
	h := sha256.Sum256([]byte(s))
	return fmt.Sprintf("%x", h)
}
