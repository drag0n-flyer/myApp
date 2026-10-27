// Package pushgw is the push gateway: it holds the APNs credentials, checks
// signed requests from registered servers and forwards pushes to APNs (spec §7.4).
package pushgw

import (
	"context"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hpke"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/x509"
	"embed"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"slices"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/db"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/drag0n-flyer/myApp/server/internal/pushproto"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Migrate applies the gateway's own migrations.
func Migrate(ctx context.Context, pool *pgxpool.Pool) error {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		return err
	}
	return db.MigrateFS(ctx, pool, sub)
}

// APNs delivers one push to Apple. gone is true when Apple says the device
// token is no longer valid.
type APNs interface {
	Send(ctx context.Context, h pushproto.Handle, kind string, data []byte) (gone bool, err error)
}

// FCM delivers one push to Firebase Cloud Messaging. gone is true when FCM says
// the registration token is no longer valid.
type FCM interface {
	Send(ctx context.Context, h pushproto.Handle, kind string, data []byte) (gone bool, err error)
}

// GatewayConfig is the gateway's configuration.
type GatewayConfig struct {
	HPKEKey    hpke.PrivateKey // must be DHKEM(X25519)
	HPKEPublic []byte          // the raw X25519 public key of HPKEKey
	BundleIDs  []string
	// FCM sends to Android handles; with nil, fcm handles are refused.
	FCM FCM
	// AndroidAppIDs are the application IDs allowed in fcm handles.
	AndroidAppIDs []string
	Now           func() time.Time
	// TrustProxy keys per-IP limits on the last X-Forwarded-For entry.
	TrustProxy bool
}

const (
	kemX25519    = 0x0020
	maxBodyBytes = 32 << 10
)

// LoadHPKEKey parses a PKCS#8 PEM private key and requires it to be X25519.
func LoadHPKEKey(pemBytes []byte) (hpke.PrivateKey, []byte, error) {
	blk, _ := pem.Decode(pemBytes)
	if blk == nil {
		return nil, nil, errors.New("pushgw: HPKE key is not PEM")
	}
	k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	if err != nil {
		return nil, nil, errors.New("pushgw: HPKE key is not PKCS#8")
	}
	xk, ok := k.(*ecdh.PrivateKey)
	if !ok || xk.Curve() != ecdh.X25519() {
		return nil, nil, errors.New("pushgw: HPKE key must be X25519")
	}
	priv, err := hpke.NewDHKEMPrivateKey(xk)
	if err != nil {
		return nil, nil, err
	}
	return priv, xk.PublicKey().Bytes(), nil
}

type gateway struct {
	pool   *pgxpool.Pool
	cfg    GatewayConfig
	apns   APNs
	log    *slog.Logger
	lim    *limits
	replay *replayCache
}

// NewGateway returns the gateway's HTTP handler. It panics if the HPKE key is
// not X25519 or does not match HPKEPublic, since that is a startup error.
func NewGateway(pool *pgxpool.Pool, cfg GatewayConfig, apns APNs, log *slog.Logger) http.Handler {
	if cfg.HPKEKey == nil || cfg.HPKEKey.KEM().ID() != kemX25519 ||
		subtle.ConstantTimeCompare(cfg.HPKEKey.PublicKey().Bytes(), cfg.HPKEPublic) != 1 {
		panic("pushgw: HPKE key must be X25519 and match HPKEPublic")
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	g := &gateway{pool: pool, cfg: cfg, apns: apns, log: log, lim: newLimits(cfg.Now), replay: newReplayCache()}
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) { writeErr(w, apiErr("not_found", "no such endpoint")) })
	route := func(pattern string, h func(http.ResponseWriter, *http.Request) error) {
		mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
			if err := h(w, r); err != nil {
				var e *gwError
				if !errors.As(err, &e) {
					g.log.Error("request failed", "route", r.Pattern, "errType", errTypeName(err))
					e = apiErr("internal", "internal error")
				}
				writeErr(w, e)
			}
		})
	}
	route("GET /v1/gateway-key", g.gatewayKey)
	route("POST /v1/servers", g.registerServer)
	route("POST /v1/push", g.push)
	return mux
}

// gwError is an error answer. The gateway has its own small set of codes.
type gwError struct {
	Status  int    `json:"-"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *gwError) Error() string { return e.Code + ": " + e.Message }

var gwStatuses = map[string]int{
	"bad_request":  http.StatusBadRequest,
	"unauthorized": http.StatusUnauthorized,
	"forbidden":    http.StatusForbidden,
	"not_found":    http.StatusNotFound,
	"conflict":     http.StatusConflict,
	"gone":         http.StatusGone,
	"too_large":    http.StatusRequestEntityTooLarge,
	"rate_limited": http.StatusTooManyRequests,
	"internal":     http.StatusInternalServerError,
	"upstream":     http.StatusBadGateway,
}

func apiErr(code, message string) *gwError {
	return &gwError{Status: gwStatuses[code], Code: code, Message: message}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		status, b = http.StatusInternalServerError, []byte(`{"error":{"code":"internal","message":"internal error"}}`)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(append(b, '\n'))
}

func writeErr(w http.ResponseWriter, e *gwError) {
	writeJSON(w, e.Status, struct {
		Error *gwError `json:"error"`
	}{e})
}

func errTypeName(err error) string { return fmt.Sprintf("%T", err) }

// isUUID reports whether s is a canonical 8-4-4-4-12 hex UUID.
func isUUID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i := 0; i < len(s); i++ {
		switch c := s[i]; {
		case i == 8 || i == 13 || i == 18 || i == 23:
			if c != '-' {
				return false
			}
		case c >= '0' && c <= '9', c >= 'a' && c <= 'f', c >= 'A' && c <= 'F':
		default:
			return false
		}
	}
	return true
}

func (g *gateway) gatewayKey(w http.ResponseWriter, _ *http.Request) error {
	writeJSON(w, http.StatusOK, map[string]string{
		"publicKey": base64.RawURLEncoding.EncodeToString(g.cfg.HPKEPublic)})
	return nil
}

// readBody reads the capped request body.
func readBody(r *http.Request) ([]byte, error) {
	b, err := io.ReadAll(http.MaxBytesReader(nil, r.Body, maxBodyBytes))
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			return nil, apiErr("too_large", "request body is too large")
		}
		return nil, apiErr("bad_request", "request body could not be read")
	}
	return b, nil
}

func (g *gateway) registerServer(w http.ResponseWriter, r *http.Request) error {
	if r.URL.RawQuery != "" {
		return apiErr("bad_request", "no query string allowed")
	}
	if ok, _ := g.lim.register.Allow(httpx.ClientIP(r, g.cfg.TrustProxy)); !ok {
		return apiErr("rate_limited", "too many registrations")
	}
	body, err := readBody(r)
	if err != nil {
		return err
	}
	var req struct {
		PublicKey []byte `json:"publicKey"`
		Name      string `json:"name"`
		Contact   string `json:"contact"`
	}
	if json.Unmarshal(body, &req) != nil || len(req.PublicKey) != ed25519.PublicKeySize ||
		req.Name == "" || len(req.Name) > 200 || len(req.Contact) > 200 {
		return apiErr("bad_request", "publicKey, name and contact are required")
	}
	// Proof of possession: the request is signed with the key being registered.
	// X-Server-ID is not used here (the server has none yet).
	if pushproto.Verify(ed25519.PublicKey(req.PublicKey), r, body, g.cfg.Now()) != nil {
		return apiErr("unauthorized", "bad signature")
	}
	var id string
	err = g.pool.QueryRow(r.Context(),
		`insert into servers (id, public_key, name, contact, created_at) values ($1, $2, $3, $4, $5)
		 on conflict (public_key) do update set public_key = servers.public_key
		 returning id`,
		core.NewID(), req.PublicKey, req.Name, req.Contact, g.cfg.Now()).Scan(&id)
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, map[string]string{"serverID": id})
	return nil
}

func (g *gateway) push(w http.ResponseWriter, r *http.Request) error {
	if r.URL.RawQuery != "" {
		return apiErr("bad_request", "no query string allowed")
	}
	body, err := readBody(r)
	if err != nil {
		return err
	}

	// The public key is chosen by X-Server-ID; unknown IDs are rejected.
	id := r.Header.Get("X-Server-ID")
	if !isUUID(id) {
		return apiErr("unauthorized", "unknown server")
	}
	var pub []byte
	var disabled bool
	err = g.pool.QueryRow(r.Context(),
		`select public_key, disabled from servers where id = $1`, id).Scan(&pub, &disabled)
	if errors.Is(err, pgx.ErrNoRows) {
		return apiErr("unauthorized", "unknown server")
	}
	if err != nil {
		return err
	}
	if disabled {
		return apiErr("forbidden", "server is disabled")
	}
	now := g.cfg.Now()
	if pushproto.Verify(ed25519.PublicKey(pub), r, body, now) != nil {
		return apiErr("unauthorized", "bad signature")
	}
	// Key on the decoded signature: base64 decoding is not strict, so the text
	// of the header can be altered without changing the signature.
	rawSig, _ := base64.StdEncoding.DecodeString(r.Header.Get("X-Signature"))
	sigHash := sha256.Sum256(rawSig)
	if !g.replay.firstUse(string(sigHash[:]), now) {
		return apiErr("conflict", "request replayed")
	}
	if ok, _ := g.lim.server.Allow(id); !ok {
		return apiErr("rate_limited", "server over its limit")
	}

	var req pushproto.PushRequest
	if json.Unmarshal(body, &req) != nil || (req.Kind != "message" && req.Kind != "call") {
		return apiErr("bad_request", "kind must be message or call")
	}
	h, err := pushproto.OpenHandle(g.cfg.HPKEKey, req.Handle)
	if err != nil {
		return apiErr("bad_request", "handle cannot be opened")
	}
	if h.Kind != "alert" && h.Kind != "voip" && h.Kind != "fcm" {
		return apiErr("bad_request", "handle is not valid")
	}
	allowed := g.cfg.BundleIDs
	if h.Kind == "fcm" {
		allowed = g.cfg.AndroidAppIDs
		if g.cfg.FCM == nil {
			return apiErr("bad_request", "handle is not valid")
		}
	}
	if !slices.Contains(allowed, h.Bundle) {
		return apiErr("bad_request", "bundle not allowed")
	}
	if (h.Env != "production" && h.Env != "sandbox") || h.Token == "" {
		return apiErr("bad_request", "handle is not valid")
	}
	if req.Kind == "message" && h.Kind != "alert" && h.Kind != "fcm" {
		return apiErr("bad_request", "a message needs an alert or fcm handle")
	}
	// Unpad only validates. The padded bytes go on to APNs unchanged, so Apple
	// cannot see the true size; the device unpads.
	if _, err := pushproto.Unpad(req.Data); err != nil {
		return apiErr("bad_request", "data is not padded correctly")
	}
	lim := g.lim.messages
	if req.Kind == "call" {
		lim = g.lim.calls
	}
	if ok, _ := lim.Allow(handleKey(h.Env, h.Bundle, h.Token)); !ok {
		return apiErr("rate_limited", "handle over its limit")
	}

	var sender APNs = g.apns
	provider := "apns"
	if h.Kind == "fcm" {
		sender, provider = g.cfg.FCM, "fcm"
	}
	gone, err := sender.Send(r.Context(), h, req.Kind, req.Data)
	if err != nil {
		g.log.Error("push send failed", "provider", provider, "errType", errTypeName(err))
		return apiErr("upstream", "push provider failed")
	}
	if gone {
		return apiErr("gone", "handle is no longer valid")
	}
	w.WriteHeader(http.StatusAccepted)
	return nil
}
