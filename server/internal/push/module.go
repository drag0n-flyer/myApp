// Package push is lane D: the push token registry and the sender to the push
// gateway (spec §7.4).
package push

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/config"
	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/drag0n-flyer/myApp/server/internal/pushproto"
	"github.com/jackc/pgx/v5"
)

const (
	httpTimeout    = 10 * time.Second
	maxSealedBytes = 2 << 10 // a sealed push handle is far smaller
)

// Module is the push module. It is the server's core.Pusher. Push is disabled
// (it does nothing and returns nil) when PushGatewayURL is empty.
type Module struct {
	d  core.Deps
	hc *http.Client

	keyMu sync.Mutex
	key   ed25519.PrivateKey // cached after the first good read; failures are retried
	vapid *ecdsa.PrivateKey  // the UnifiedPush VAPID key, cached the same way
	up    *http.Client       // UnifiedPush endpoints: SSRF-guarded, no redirects
}

var _ core.Pusher = (*Module)(nil)

// New returns the push module.
func New(d core.Deps) *Module {
	// Never follow a redirect: the request is signed for one URL, and a
	// redirect would send it and its handle to another host.
	hc := &http.Client{
		Timeout:       httpTimeout,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	return &Module{d: d, hc: hc, up: newUPClient(false, nil)}
}

func validKind(k string) bool {
	return k == "alert" || k == "voip" || k == "fcm" || k == "unifiedpush"
}

const badKindMsg = "kind must be alert, voip, fcm or unifiedpush"

// Routes registers the push endpoints.
func (m *Module) Routes(r *httpx.Router) {
	r.Authed("PUT /v1/push/tokens/{kind}", m.putToken)
	r.Authed("DELETE /v1/push/tokens/{kind}", m.deleteToken)
}

func (m *Module) putToken(w http.ResponseWriter, r *http.Request) error {
	kind := r.PathValue("kind")
	if !validKind(kind) {
		return httpx.Err("bad_request", badKindMsg)
	}
	p := httpx.PrincipalFrom(r.Context())
	var stored []byte
	if kind == "unifiedpush" {
		var body struct {
			Endpoint string      `json:"endpoint"`
			P256dh   httpx.Bytes `json:"p256dh"`
			Auth     httpx.Bytes `json:"auth"`
		}
		if err := httpx.Decode(r, &body); err != nil {
			return err
		}
		if m.d.Config.VapidKeyFile == "" {
			return httpx.Err("bad_request", "this server does not support UnifiedPush")
		}
		if _, err := validateUploadEndpoint(body.Endpoint); err != nil {
			return httpx.Err("bad_request", err.Error())
		}
		if err := validateKeys(body.P256dh, body.Auth); err != nil {
			return httpx.Err("bad_request", err.Error())
		}
		var err error
		if stored, err = json.Marshal(upRegistration{Endpoint: body.Endpoint, P256dh: body.P256dh, Auth: body.Auth}); err != nil {
			return err
		}
	} else {
		var body struct {
			Sealed httpx.Bytes `json:"sealed"`
		}
		if err := httpx.Decode(r, &body); err != nil {
			return err
		}
		if len(body.Sealed) == 0 || len(body.Sealed) > maxSealedBytes {
			return httpx.Err("bad_request", "sealed must be between 1 byte and 2 KiB")
		}
		stored = body.Sealed
	}
	// One transport per device: fcm and unifiedpush replace each other.
	other := map[string]string{"fcm": "unifiedpush", "unifiedpush": "fcm"}[kind]
	tx, err := m.d.DB.Begin(r.Context())
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(r.Context()) }()
	if other != "" {
		if _, err := tx.Exec(r.Context(),
			`delete from push_tokens where device_id = $1 and kind = $2`, p.DeviceID, other); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(r.Context(),
		`insert into push_tokens (device_id, kind, sealed, updated_at) values ($1, $2, $3, $4)
		 on conflict (device_id, kind) do update set sealed = excluded.sealed, updated_at = excluded.updated_at`,
		p.DeviceID, kind, stored, m.d.Now()); err != nil {
		return err
	}
	if err := tx.Commit(r.Context()); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (m *Module) deleteToken(w http.ResponseWriter, r *http.Request) error {
	kind := r.PathValue("kind")
	if !validKind(kind) {
		return httpx.Err("bad_request", badKindMsg)
	}
	p := httpx.PrincipalFrom(r.Context())
	if _, err := m.d.DB.Exec(r.Context(),
		`delete from push_tokens where device_id = $1 and kind = $2`, p.DeviceID, kind); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (m *Module) loadKey() (ed25519.PrivateKey, error) {
	m.keyMu.Lock()
	defer m.keyMu.Unlock()
	if m.key != nil {
		return m.key, nil
	}
	k, err := readKey(m.d.Config.PushKeyFile)
	if err != nil {
		return nil, err // not cached: a fixed key file works without a restart
	}
	m.key = k
	return k, nil
}

// Validate checks the push configuration at startup. With a gateway URL set,
// the server ID and a parseable Ed25519 signing key are required.
func Validate(cfg config.Config) error {
	if err := validateVAPID(cfg); err != nil {
		return err
	}
	if cfg.PushGatewayURL == "" {
		return nil
	}
	if cfg.PushServerID == "" {
		return errors.New("push: APP_PUSH_GATEWAY_URL requires APP_PUSH_SERVER_ID")
	}
	_, err := readKey(cfg.PushKeyFile)
	return err
}

// ValidateVAPID checks the UnifiedPush VAPID key, when one is configured.
func validateVAPID(cfg config.Config) error {
	if cfg.VapidKeyFile == "" {
		return nil
	}
	_, err := readVAPIDKey(cfg.VapidKeyFile)
	return err
}

func readKey(path string) (ed25519.PrivateKey, error) {
	if path == "" {
		return nil, errors.New("push: no signing key file configured")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("push: read signing key: %w", err)
	}
	blk, _ := pem.Decode(b)
	if blk == nil {
		return nil, errors.New("push: signing key is not PEM")
	}
	k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	if err != nil {
		return nil, errors.New("push: signing key is not PKCS#8")
	}
	ek, ok := k.(ed25519.PrivateKey)
	if !ok {
		return nil, errors.New("push: signing key is not Ed25519")
	}
	return ek, nil
}

// handleFor returns the device's sealed handle for the push, and its kind.
func (m *Module) handleFor(ctx context.Context, deviceID string, kind core.PushKind) (string, []byte, error) {
	order := []string{"alert", "fcm", "unifiedpush"}
	if kind == core.PushCall {
		order = []string{"voip", "alert", "fcm", "unifiedpush"}
	}
	for _, k := range order {
		var sealed []byte
		err := m.d.DB.QueryRow(ctx,
			`select sealed from push_tokens where device_id = $1 and kind = $2`, deviceID, k).Scan(&sealed)
		if errors.Is(err, pgx.ErrNoRows) {
			continue
		}
		if err != nil {
			return "", nil, err
		}
		return k, sealed, nil
	}
	return "", nil, nil
}

// Push sends a push notification to a device through the gateway. A device
// with no handle, or a disabled gateway, is not an error.
func (m *Module) Push(ctx context.Context, deviceID string, kind core.PushKind, payload []byte) error {
	if kind != core.PushMessage && kind != core.PushCall {
		return nil
	}
	handleKind, sealed, err := m.handleFor(ctx, deviceID, kind)
	if err != nil || sealed == nil {
		return err
	}
	if handleKind == "unifiedpush" { // direct, no gateway
		return m.sendUnifiedPush(ctx, deviceID, kind, sealed, payload)
	}
	if m.d.Config.PushGatewayURL == "" {
		return nil
	}
	key, err := m.loadKey()
	if err != nil {
		return err
	}
	gwKind := "message"
	if kind == core.PushCall {
		gwKind = "call"
	}
	var nonce [12]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return err
	}
	body, err := json.Marshal(pushproto.PushRequest{
		Handle: sealed, Kind: gwKind, Data: pushproto.Pad(payload),
		Nonce: base64.RawURLEncoding.EncodeToString(nonce[:]),
	})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimRight(m.d.Config.PushGatewayURL, "/")+"/v1/push", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	pushproto.Sign(key, req, body, m.d.Config.PushServerID, m.d.Now())
	resp, err := m.hc.Do(req)
	if err != nil {
		return errors.New("push: gateway request failed")
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<16))
	switch {
	case resp.StatusCode == http.StatusGone:
		_, err := m.d.DB.Exec(ctx,
			`delete from push_tokens where device_id = $1 and kind = $2 and sealed = $3`,
			deviceID, handleKind, sealed)
		return err
	case resp.StatusCode/100 != 2:
		return fmt.Errorf("push: gateway answered %d", resp.StatusCode)
	}
	return nil
}

// Commands returns the admin CLI subcommands.
func (m *Module) Commands() map[string]core.Command {
	return map[string]core.Command{
		"keygen push":   m.keygen,
		"keygen vapid":  m.keygenVAPID,
		"push register": m.register,
	}
}

func (m *Module) keygen(_ context.Context, _ []string, out io.Writer) error {
	_, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		return err
	}
	der, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		return err
	}
	return pem.Encode(out, &pem.Block{Type: "PRIVATE KEY", Bytes: der})
}

func (m *Module) keygenVAPID(_ context.Context, _ []string, out io.Writer) error {
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	return WriteVAPIDKey(out, k)
}

func (m *Module) register(ctx context.Context, args []string, out io.Writer) error {
	fs := flag.NewFlagSet("push register", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	gateway := fs.String("gateway", m.d.Config.PushGatewayURL, "gateway base URL")
	name := fs.String("name", "", "server name")
	contact := fs.String("contact", "", "contact address")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *gateway == "" || *name == "" || *contact == "" {
		return errors.New("usage: push register --gateway URL --name N --contact C")
	}
	key, err := m.loadKey()
	if err != nil {
		return err
	}
	body, err := json.Marshal(map[string]string{
		"publicKey": base64.StdEncoding.EncodeToString(key.Public().(ed25519.PublicKey)),
		"name":      *name,
		"contact":   *contact,
	})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimRight(*gateway, "/")+"/v1/servers", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	pushproto.Sign(key, req, body, "", m.d.Now())
	resp, err := m.hc.Do(req)
	if err != nil {
		return fmt.Errorf("push register: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("push register: gateway answered %d", resp.StatusCode)
	}
	var res struct {
		ServerID string `json:"serverID"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<16)).Decode(&res); err != nil || res.ServerID == "" {
		return errors.New("push register: bad gateway response")
	}
	_, err = fmt.Fprintln(out, res.ServerID)
	return err
}
