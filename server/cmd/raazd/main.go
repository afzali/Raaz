package main

import (
	"log"
	"net/http"
	"time"

	"github.com/raaz-io/raazd/internal/api"
	"github.com/raaz-io/raazd/internal/config"
	"github.com/raaz-io/raazd/internal/db"
	"github.com/raaz-io/raazd/internal/service"
)

func main() {
	cfg := config.Load()

	database := db.Open(cfg.DBPath)
	defer database.Close()
	db.Migrate(database)

	deviceRepo := db.NewDeviceRepo(database)
	msgRepo := db.NewMessageRepo(database)

	cleanup := service.NewCleanupService(msgRepo, 15*time.Minute)
	cleanup.Start()

	router := api.NewRouter(deviceRepo, msgRepo, cfg.MessageTTL, cfg.Version)

	log.Printf("Raaz relay server v%s listening on %s", cfg.Version, cfg.ListenAddr)
	if err := http.ListenAndServe(cfg.ListenAddr, router); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}
