// Package turn is lane C: TURN credentials and relay health checks. This file
// is a stub.
package turn

import (
	"context"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

// Module is the turn module.
type Module struct{ d core.Deps }

// New returns the turn module.
func New(d core.Deps) *Module { return &Module{d: d} }

// Routes registers the TURN endpoints.
func (m *Module) Routes(r *httpx.Router) {}

// Run does background work until ctx is cancelled.
func (m *Module) Run(ctx context.Context) {}
