package testkit

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

// defaultRemoteAddr is what httptest.NewRequest uses (a TEST-NET-1 address).
const defaultRemoteAddr = "192.0.2.1:1234"

// testVerifier accepts tokens of the form test.<accountID>.<deviceID>.
type testVerifier struct{}

func (testVerifier) Verify(_ context.Context, token string) (core.Principal, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 || parts[0] != "test" || parts[1] == "" || parts[2] == "" {
		return core.Principal{}, core.ErrNotFound
	}
	return core.Principal{AccountID: parts[1], DeviceID: parts[2]}, nil
}

// Option configures Serve. It satisfies the same interface as a module, so it
// goes in Serve's module list.
type Option struct{ apply func(*serveConfig) }

// Routes does nothing; Option only exists to ride in Serve's argument list.
func (Option) Routes(*httpx.Router) {}

type serveConfig struct {
	verifier   core.SessionVerifier
	remoteAddr string
}

// WithVerifier makes Serve check tokens with v instead of the test verifier.
func WithVerifier(v core.SessionVerifier) Option {
	return Option{func(c *serveConfig) { c.verifier = v }}
}

// WithRemoteAddr sets the RemoteAddr ("ip:port") every request appears to come from.
func WithRemoteAddr(addr string) Option {
	return Option{func(c *serveConfig) { c.remoteAddr = addr }}
}

// Client sends requests straight into a handler, without a network.
type Client struct {
	t          testing.TB
	Handler    http.Handler
	remoteAddr string
}

// Response is what a Client call got back.
type Response struct {
	Status int
	Header http.Header
	Body   []byte
}

// Serve builds a router with the given modules' routes and returns a Client
// for it. Options (WithVerifier, WithRemoteAddr) may be mixed into mods.
func Serve(t testing.TB, d core.Deps, mods ...interface{ Routes(*httpx.Router) }) *Client {
	t.Helper()
	cfg := serveConfig{verifier: testVerifier{}, remoteAddr: defaultRemoteAddr}
	for _, m := range mods {
		if o, ok := m.(Option); ok {
			o.apply(&cfg)
		}
	}
	r := httpx.NewRouter(cfg.verifier, d.Log)
	for _, m := range mods {
		if _, ok := m.(Option); !ok {
			m.Routes(r)
		}
	}
	return &Client{t: t, Handler: r, remoteAddr: cfg.remoteAddr}
}

// Token returns the test token for p, as the test verifier expects it.
func Token(p core.Principal) string { return "test." + p.AccountID + "." + p.DeviceID }

// Do sends a request as p; a nil p sends no Authorization header. A []byte
// body is sent as is, any other non-nil body as JSON.
func (c *Client) Do(p *core.Principal, method, path string, body any) *Response {
	c.t.Helper()
	token := ""
	if p != nil {
		token = Token(*p)
	}
	return c.DoToken(token, method, path, body)
}

// DoToken is Do with an explicit bearer token; an empty token sends no
// Authorization header.
func (c *Client) DoToken(token, method, path string, body any) *Response {
	c.t.Helper()
	var rd io.Reader
	jsonBody := false
	switch b := body.(type) {
	case nil:
	case []byte:
		rd = bytes.NewReader(b)
	default:
		enc, err := json.Marshal(b)
		if err != nil {
			c.t.Fatalf("testkit: marshal request body: %v", err)
		}
		rd = bytes.NewReader(enc)
		jsonBody = true
	}
	req := httptest.NewRequest(method, path, rd)
	req.RemoteAddr = c.remoteAddr
	if jsonBody {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	c.Handler.ServeHTTP(rec, req)
	res := rec.Result()
	defer res.Body.Close()
	out, err := io.ReadAll(res.Body)
	if err != nil {
		c.t.Fatalf("testkit: read response: %v", err)
	}
	return &Response{Status: res.StatusCode, Header: res.Header, Body: out}
}

// Decode unmarshals the response body into v, failing the test if it can't.
func (r *Response) Decode(t testing.TB, v any) {
	t.Helper()
	if err := json.Unmarshal(r.Body, v); err != nil {
		t.Fatalf("testkit: decode response (status %d): %v", r.Status, err)
	}
}

// Code returns the response's error.code, or "" if the body is not an error
// envelope.
func (r *Response) Code() string {
	var env struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	if json.Unmarshal(r.Body, &env) != nil {
		return ""
	}
	return env.Error.Code
}

// String summarises the response for failure messages.
func (r *Response) String() string { return fmt.Sprintf("%d %s", r.Status, r.Body) }
