package config

import (
	"flag"
	"os"
)

type Config struct {
	ListenAddr  string
	DBPath      string
	MessageTTL  int64 // seconds
	Version     string
}

func Load() *Config {
	cfg := &Config{
		Version: "1.0.0",
	}

	flag.StringVar(&cfg.ListenAddr, "addr", envOrDefault("RAAZ_ADDR", ":8080"), "Listen address")
	flag.StringVar(&cfg.DBPath, "db", envOrDefault("RAAZ_DB", "./raaz_server.db"), "SQLite database path")
	flag.Int64Var(&cfg.MessageTTL, "ttl", 86400, "Default message TTL in seconds (24h)")
	flag.Parse()

	return cfg
}

func envOrDefault(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
