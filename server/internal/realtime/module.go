// Package realtime is lane C: the WebSocket hub and the Notifier.
package realtime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
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
type Module struct {
	d         core.Deps
	hub       *hub
	pingEvery time.Duration

	mu       sync.RWMutex
	handlers map[string]FrameHandler

	bg      sync.WaitGroup // detached push sends, drained by WaitBackground
	pushSem chan struct{}  // bounds concurrent push sends
}

// revokedCode is the close code for a revoked or deleted device (spec section 7.3).
const revokedCode = 4003

const (
	pushTimeout     = 10 * time.Second // one push send, as the gateway client's own limit
	maxPushesActive = 256
)

var (
	_ core.Notifier     = (*Module)(nil)
	_ core.Disconnector = (*Module)(nil)
	_ FrameRouter       = (*Module)(nil)
)

// New returns the realtime module.
func New(d core.Deps) *Module {
	return &Module{d: d, hub: newHub(), pingEvery: 25 * time.Second, handlers: map[string]FrameHandler{}, pushSem: make(chan struct{}, maxPushesActive)}
}

// Run blocks until ctx is done, then closes every live socket (http.Server's
// Shutdown does not wait for hijacked connections) and refuses new ones.
func (m *Module) Run(ctx context.Context) {
	<-ctx.Done()
	m.hub.closeAll(int(websocket.StatusGoingAway), "server shutting down")
}

// Notify delivers ev over the device's WebSocket, or by push when offline.
func (m *Module) Notify(ctx context.Context, deviceID string, ev core.Event) {
	if m.Send(deviceID, ev.Type, ev.Data) {
		return
	}
	if ev.Push == core.PushNone {
		return
	}
	// The push goes out on its own goroutine: a gateway that is slow or down
	// must not hold up the request that produced the event (a message post
	// would otherwise wait up to the push timeout for every offline device).
	// The request's values are kept but its cancellation is not.
	select {
	case m.pushSem <- struct{}{}:
	default:
		m.d.Log.Warn("push dropped: too many in flight", "kind", int(ev.Push))
		return
	}
	pctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), pushTimeout)
	m.bg.Add(1)
	go func() {
		defer m.bg.Done()
		defer cancel()
		defer func() { <-m.pushSem }()
		if err := m.d.Push.Push(pctx, deviceID, ev.Push, ev.PushPayload); err != nil {
			m.d.Log.Warn("push failed", "kind", int(ev.Push))
		}
	}()
}

// WaitBackground waits for push sends started by Notify. App calls it at
// shutdown, with a timeout.
func (m *Module) WaitBackground() { m.bg.Wait() }

// Disconnect closes the device's live WebSocket.
func (m *Module) Disconnect(deviceID string, code int, reason string) {
	if c := m.hub.get(deviceID); c != nil {
		c.close(code, reason)
	}
}

// Routes registers the WebSocket endpoint.
func (m *Module) Routes(r *httpx.Router) { r.Authed("GET /v1/ws", m.serveWS) }

// HandleFrame registers h for frames of frameType.
func (m *Module) HandleFrame(frameType string, h FrameHandler) {
	m.mu.Lock()
	m.handlers[frameType] = h
	m.mu.Unlock()
}

// Send writes a frame to a connected device. It reports false when the device
// is offline or its buffer overflowed.
func (m *Module) Send(deviceID string, frameType string, data any) bool {
	c := m.hub.get(deviceID)
	if c == nil {
		return false
	}
	b, err := encodeFrame(frameType, "", data)
	if err != nil {
		return false
	}
	return c.enqueue(b)
}

func (m *Module) serveWS(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionContextTakeover})
	if err != nil {
		return nil // Accept already wrote the HTTP error
	}
	ws.SetReadLimit(maxFrameBytes)
	// ctx drives the socket I/O and must outlive close() so the close
	// handshake can finish; hctx is what handlers get and is cancelled the
	// moment the connection is closed.
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	hctx, hcancel := context.WithCancel(ctx)
	defer hcancel()
	c := &conn{ws: ws, dev: p.DeviceID, send: make(chan []byte, sendBuffer), done: make(chan struct{}), hub: m.hub, cancel: hcancel}
	if !m.hub.add(c) {
		ws.Close(websocket.StatusGoingAway, "server shutting down")
		return nil
	}
	go c.writeLoop(ctx)
	go c.pingLoop(ctx, m.pingEvery)
	// A revoke that ran between the token check and hub.add found no socket to
	// close. Now that the socket is registered, any later revoke will find it,
	// so one more check closes the gap.
	if _, err := m.d.Directory.AccountOf(ctx, p.DeviceID); errors.Is(err, core.ErrNotFound) {
		c.close(revokedCode, "revoked")
	}
	defer func() {
		c.once.Do(func() { close(c.done); c.hub.remove(c) })
		ws.CloseNow()
	}()
	for {
		// After close() the loop keeps reading so the close handshake can
		// finish, but dispatch drops every frame.
		_, b, err := ws.Read(ctx)
		if err != nil {
			return nil
		}
		m.dispatch(hctx, c, p, b)
	}
}

func (m *Module) dispatch(ctx context.Context, c *conn, p core.Principal, b []byte) {
	select {
	case <-c.done:
		return // closed (revoked or replaced): never run handlers
	default:
	}
	typ, id, data, err := decodeFrame(b)
	if err != nil {
		m.reply(c, "error", id, map[string]string{"code": "bad_request"})
		return
	}
	m.mu.RLock()
	h := m.handlers[typ]
	m.mu.RUnlock()
	if h == nil {
		m.reply(c, "error", id, map[string]string{"code": "bad_request"})
		return
	}
	out, err := h(ctx, p, data)
	if err != nil {
		code := "internal"
		var he *httpx.Error
		if errors.As(err, &he) {
			code = he.Code
		} else {
			// Never the error text (section 8): the route and the type tell
			// an operator where to look.
			m.d.Log.Error("frame handler failed", "frame", typ, "errType", fmt.Sprintf("%T", err))
		}
		m.reply(c, "error", id, map[string]string{"code": code})
		return
	}
	if id == "" {
		return // nothing to acknowledge
	}
	if out == nil {
		out = struct{}{}
	}
	m.reply(c, "ack", id, out)
}

func (m *Module) reply(c *conn, typ, id string, data any) {
	if b, err := encodeFrame(typ, id, data); err == nil {
		c.enqueue(b)
	}
}
