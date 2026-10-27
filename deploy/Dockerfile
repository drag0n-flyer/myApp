# Build context is server/:  docker build -f deploy/Dockerfile server
FROM golang:1.26 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# Builds every binary under cmd/ (appserver, and pushgw when present).
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/ ./cmd/...

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/ /
USER nonroot:nonroot
EXPOSE 8080
ENTRYPOINT ["/appserver"]
