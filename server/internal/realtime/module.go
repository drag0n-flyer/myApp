// Package realtime is lane C: the WebSocket hub and the Notifier. This file
// is a stub.
package realtime

import (
	"context"
	"encoding/json"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

// FrameHandler handles one client frame. A non-nil reply is sent back to the
// sender; a non-nil error is reported to it.
type FrameHandler func(ctx context.Context, from core.Principal, data json.RawMessage) (reply any, err error)

// FrameRouter lets other modules (calls) take part in the WebSocket protocol.
type FrameRouter interface {
	// HandleFrame registers h for frames of frameType.
	HandleFrame(frameType string, h FrameHandler)
	// Send writes a frame to a connected device and reports whether it was connected.
	Send(deviceID string, frameType string, data any) bool
}

// Module is the realtime module. It is the server's core.Notifier and
// core.Disconnector.
type Module struct{ d core.Deps }

var (
	_ core.Notifier     = (*Module)(nil)
	_ core.Disconnector = (*Module)(nil)
	_ FrameRouter       = (*Module)(nil)
)

// New returns the realtime module.
func New(d core.Deps) *Module { return &Module{d: d} }

// Notify delivers ev over the device's WebSocket, or by push when offline.
func (m *Module) Notify(ctx context.Context, deviceID string, ev core.Event) {}

// Disconnect closes the device's live WebSocket.
func (m *Module) Disconnect(deviceID string, code int, reason string) {}

// Routes registers the WebSocket endpoint.
func (m *Module) Routes(r *httpx.Router) {}

// HandleFrame registers h for frames of frameType.
func (m *Module) HandleFrame(frameType string, h FrameHandler) {}

// Send writes a frame to a connected device.
func (m *Module) Send(deviceID string, frameType string, data any) bool { return false }
