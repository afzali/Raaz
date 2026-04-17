FROM golang:1.22-alpine AS builder
RUN apk add --no-cache gcc musl-dev sqlite-dev

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=1 GOOS=linux go build -ldflags="-s -w" -o raazd ./cmd/raazd/

FROM alpine:3.19
RUN apk add --no-cache sqlite-libs ca-certificates
WORKDIR /app
COPY --from=builder /app/raazd .

EXPOSE 8080
ENV RAAZ_ADDR=:8080
ENV RAAZ_DB=/data/raaz_server.db

VOLUME ["/data"]
ENTRYPOINT ["./raazd"]
