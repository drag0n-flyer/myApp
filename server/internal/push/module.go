// Package push is lane D: the push token registry and the sender to the push
// gateway. This file is a stub.
package push

import (
	"context"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

// Module is the push module. It is the server's core.Pusher.
type Module struct{ d core.Deps }

var _ core.Pusher = (*Module)(nil)

// New returns the push module.
func New(d core.Deps) *Module { return &Module{d: d} }

// Push sends a push notification to a device. The stub does nothing.
func (m *Module) Push(ctx context.Context, deviceID string, kind core.PushKind, payload []byte) error {
	return nil
}

// Routes registers the push endpoints.
func (m *Module) Routes(r *httpx.Router) {}

// Commands returns the admin CLI subcommands.
func (m *Module) Commands() map[string]core.Command { return nil }
