FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -o raazd .

FROM alpine:latest
RUN apk add --no-cache ca-certificates
WORKDIR /app
COPY --from=builder /app/raazd .
VOLUME /app/pb_data
EXPOSE 8090
CMD ["./raazd", "serve", "--http=0.0.0.0:8090"]
