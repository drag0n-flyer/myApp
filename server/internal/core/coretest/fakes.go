// Package coretest holds in-memory fakes of the core interfaces for tests.
package coretest

import (
	"context"
	"sync"

	"github.com/drag0n-flyer/myApp/server/internal/core"
)

// Notifier records every Notify call.
type Notifier struct {
	mu     sync.Mutex
	events map[string][]core.Event
}

// Notify records ev for deviceID.
func (n *Notifier) Notify(_ context.Context, deviceID string, ev core.Event) {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.events == nil {
		n.events = map[string][]core.Event{}
	}
	n.events[deviceID] = append(n.events[deviceID], ev)
}

// Events returns the events recorded for deviceID, oldest first.
func (n *Notifier) Events(deviceID string) []core.Event {
	n.mu.Lock()
	defer n.mu.Unlock()
	return append([]core.Event(nil), n.events[deviceID]...)
}

// Push is one recorded Pusher call.
type Push struct {
	DeviceID string
	Kind     core.PushKind
	Payload  []byte
}

// Pusher records every Push call. Read Pushed only when no calls are in flight.
type Pusher struct {
	mu     sync.Mutex
	Pushed []Push
}

// Push records the call and succeeds.
func (p *Pusher) Push(_ context.Context, deviceID string, kind core.PushKind, payload []byte) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.Pushed = append(p.Pushed, Push{DeviceID: deviceID, Kind: kind, Payload: payload})
	return nil
}

// Blocklist is an in-memory core.Blocklist.
type Blocklist struct {
	mu      sync.Mutex
	blocked map[[2]string]bool
}

// Block makes IsBlocked(blocker, blocked) true.
func (b *Blocklist) Block(blocker, blocked string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.blocked == nil {
		b.blocked = map[[2]string]bool{}
	}
	b.blocked[[2]string{blocker, blocked}] = true
}

// IsBlocked reports whether blocker has blocked blocked.
func (b *Blocklist) IsBlocked(_ context.Context, blocker, blocked string) (bool, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.blocked[[2]string{blocker, blocked}], nil
}

// Disconnect is one recorded Disconnector call.
type Disconnect struct {
	DeviceID string
	Code     int
}

// Live records every Disconnect call. Read Disconnected only when no calls are
// in flight.
type Live struct {
	mu           sync.Mutex
	Disconnected []Disconnect
}

// Disconnect records the call.
func (l *Live) Disconnect(deviceID string, code int, _ string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.Disconnected = append(l.Disconnected, Disconnect{DeviceID: deviceID, Code: code})
}
