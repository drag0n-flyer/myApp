# Build context is server/:  docker build -f deploy/Dockerfile server
FROM golang:1.26 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# Builds every binary under cmd/ (appserver, and pushgw when present).
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/ ./cmd/...
# Data directory owned by the nonroot user (uid 65532); a new named volume
# mounted here inherits this ownership.
RUN mkdir -p /rootfs/var/lib/appserver

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/ /
COPY --from=build --chown=65532:65532 /rootfs/var/lib/appserver /var/lib/appserver
USER nonroot:nonroot
EXPOSE 8080
ENTRYPOINT ["/appserver"]
