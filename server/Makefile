BINARY=raazd
VERSION=1.0.0

build:
	CGO_ENABLED=1 go build -ldflags="-s -w -X main.version=$(VERSION)" -o $(BINARY) ./cmd/raazd/

run:
	go run ./cmd/raazd/ -addr :8080 -db ./raaz_server.db

test:
	go test ./...

docker:
	docker build -t raazd:$(VERSION) .

clean:
	rm -f $(BINARY)

.PHONY: build run test docker clean
