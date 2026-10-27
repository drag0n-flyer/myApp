// Package attachments is lane E: encrypted blob upload, download and expiry.
// This file is a stub.
package attachments

import (
	"context"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

// Module is the attachments module.
type Module struct{ d core.Deps }

// New returns the attachments module.
func New(d core.Deps) *Module { return &Module{d: d} }

// Routes registers the attachment endpoints.
func (m *Module) Routes(r *httpx.Router) {}

// Run does background work (expiry) until ctx is cancelled.
func (m *Module) Run(ctx context.Context) {}
