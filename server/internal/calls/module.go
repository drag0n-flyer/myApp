// Package calls is lane C: the call state machine and signaling. This file is
// a stub.
package calls

import (
	"context"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/realtime"
)

// Module is the calls module.
type Module struct {
	d      core.Deps
	frames realtime.FrameRouter
}

// New returns the calls module, which registers its frames on frames.
func New(d core.Deps, frames realtime.FrameRouter) *Module { return &Module{d: d, frames: frames} }

// Run does background work until ctx is cancelled.
func (m *Module) Run(ctx context.Context) {}
