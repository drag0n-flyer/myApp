// Package serverinfo is lane E: GET /v1/server, the /invite/{code} page and
// apple-app-site-association. This file is a stub.
package serverinfo

import (
	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

// Module is the serverinfo module.
type Module struct{ d core.Deps }

// New returns the serverinfo module.
func New(d core.Deps) *Module { return &Module{d: d} }

// Routes registers the server info endpoints.
func (m *Module) Routes(r *httpx.Router) {}
