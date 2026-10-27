// Package identity is lane A: accounts, invites, email codes, passkeys,
// sessions, devices, linking and deletion. This file is a stub.
package identity

import (
	"context"
	"net/http"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

var _ core.SessionVerifier = (*Module)(nil)

// Module is the identity module.
type Module struct{ d core.Deps }

// New returns the identity module.
func New(d core.Deps) *Module { return &Module{d: d} }

// Verify is the session verifier. The stub rejects every token.
func (m *Module) Verify(ctx context.Context, token string) (core.Principal, error) {
	return core.Principal{}, core.ErrNotFound
}

// Routes registers the identity endpoints. The stub registers only an authed
// GET /v1/me so that the wiring (and the verifier) can be exercised.
func (m *Module) Routes(r *httpx.Router) {
	r.Authed("GET /v1/me", func(http.ResponseWriter, *http.Request) error {
		return httpx.Err("not_found", "not implemented")
	})
}

// Commands returns the admin CLI subcommands, keyed like "invite create".
func (m *Module) Commands() map[string]core.Command { return nil }
