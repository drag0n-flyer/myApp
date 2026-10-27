// Package delivery is lane B: KeyPackages, conversations, commits, messages,
// inbox and escrow.
package delivery

import (
	"context"
	"time"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
	"github.com/velixio/myApp/server/internal/ratelimit"
)

// Module is the delivery module.
type Module struct {
	d          core.Deps
	claimLimit *ratelimit.Limiter // KeyPackage claims: 100/hour per account
}

// New returns the delivery module.
func New(d core.Deps) *Module {
	return &Module{d: d, claimLimit: ratelimit.New(100, time.Hour, d.Now)}
}

// Routes registers the delivery endpoints.
func (m *Module) Routes(r *httpx.Router) {
	m.keyPackageRoutes(r)
	m.conversationRoutes(r)
}

// Commands returns the admin CLI subcommands.
func (m *Module) Commands() map[string]core.Command { return nil }

// Run does background work until ctx is cancelled.
func (m *Module) Run(ctx context.Context) {}
