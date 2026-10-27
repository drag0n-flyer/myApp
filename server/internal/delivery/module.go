// Package delivery is lane B: KeyPackages, conversations, commits, messages,
// inbox and escrow. This file is a stub.
package delivery

import (
	"context"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

// Module is the delivery module.
type Module struct{ d core.Deps }

// New returns the delivery module.
func New(d core.Deps) *Module { return &Module{d: d} }

// Routes registers the delivery endpoints.
func (m *Module) Routes(r *httpx.Router) {}

// Commands returns the admin CLI subcommands.
func (m *Module) Commands() map[string]core.Command { return nil }

// Run does background work until ctx is cancelled.
func (m *Module) Run(ctx context.Context) {}
