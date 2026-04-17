package service

import (
	"log"
	"time"

	"github.com/raaz-io/raazd/internal/db"
)

type CleanupService struct {
	msgRepo  *db.MessageRepo
	interval time.Duration
}

func NewCleanupService(msgRepo *db.MessageRepo, interval time.Duration) *CleanupService {
	return &CleanupService{msgRepo, interval}
}

func (s *CleanupService) Start() {
	go func() {
		ticker := time.NewTicker(s.interval)
		defer ticker.Stop()
		for range ticker.C {
			s.run()
		}
	}()
}

func (s *CleanupService) run() {
	if n, err := s.msgRepo.DeleteAcked(); err != nil {
		log.Printf("cleanup: failed to delete acked messages: %v", err)
	} else if n > 0 {
		log.Printf("cleanup: deleted %d acked messages", n)
	}

	if n, err := s.msgRepo.DeleteExpired(); err != nil {
		log.Printf("cleanup: failed to delete expired messages: %v", err)
	} else if n > 0 {
		log.Printf("cleanup: deleted %d expired messages", n)
	}
}
