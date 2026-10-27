// Package social is lane E: user lookup, email discovery, profile settings,
// blocks and reports. This file is a stub.
package social

import (
	"context"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

// Module is the social module. It is the server's core.Blocklist.
type Module struct{ d core.Deps }

var _ core.Blocklist = (*Module)(nil)

// New returns the social module.
func New(d core.Deps) *Module { return &Module{d: d} }

// IsBlocked reports whether blocker has blocked blocked. The stub says no.
func (m *Module) IsBlocked(ctx context.Context, blocker, blocked string) (bool, error) {
	return false, nil
}

// Routes registers the social endpoints.
func (m *Module) Routes(r *httpx.Router) {}

// Commands returns the admin CLI subcommands.
func (m *Module) Commands() map[string]core.Command { return nil }
