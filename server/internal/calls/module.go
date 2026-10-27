// Package calls is lane C: the call state machine and signaling.
package calls

import (
	"context"
	"encoding/json"
	"errors"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
	"github.com/velixio/myApp/server/internal/realtime"
)

var uuidRE = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

const (
	endedKeep    = 60 * time.Second // an ended call stays visible to call.status
	answeredKeep = 4 * time.Hour    // an answered call is forgotten after this
	pushTimeout  = 10 * time.Second
	maxIDLen     = 128
)

// entry is a call plus the facts the machine does not hold.
type entry struct {
	call *Call
	// blocked means the callee had blocked the caller: nothing rang and the
	// callee's devices must not see or act on the call.
	blocked bool
}

// Module is the calls module.
type Module struct {
	d      core.Deps
	frames realtime.FrameRouter

	mu    sync.Mutex
	calls map[string]*entry

	bg sync.WaitGroup // detached ring pushes, drained by WaitBackground
}

// WaitBackground waits for ring pushes still being sent. App calls it at
// shutdown, with a timeout.
func (m *Module) WaitBackground() { m.bg.Wait() }

// New returns the calls module, which registers its frames on frames.
func New(d core.Deps, frames realtime.FrameRouter) *Module {
	m := &Module{d: d, frames: frames, calls: map[string]*entry{}}
	frames.HandleFrame("call.invite", m.invite)
	frames.HandleFrame("call.answer", m.answer)
	frames.HandleFrame("call.signal", m.signal)
	frames.HandleFrame("call.decline", m.decline)
	frames.HandleFrame("call.hangup", m.hangup)
	frames.HandleFrame("call.status", m.status)
	return m
}

// Run ends unanswered calls, once a second, and forgets old ones, until ctx
// is cancelled.
func (m *Module) Run(ctx context.Context) {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			m.tick(ctx)
		}
	}
}

// tick times out ringing calls and drops expired ones. Directory lookups run
// outside the lock; the call is re-checked under it before it is ended.
func (m *Module) tick(ctx context.Context) {
	now := m.d.Now()
	type due struct {
		id string
		e  *entry
	}
	var dues []due
	m.mu.Lock()
	for id, e := range m.calls {
		switch {
		case expired(e.call, now):
			delete(m.calls, id)
		case e.call.State == Ringing && now.Sub(e.call.Started) >= ringTimeout:
			dues = append(dues, due{id, e})
		}
	}
	m.mu.Unlock()
	for _, du := range dues {
		devs, err := m.d.Directory.DeviceIDs(ctx, du.e.call.CalleeAccount)
		if err != nil {
			continue // retried next second
		}
		m.mu.Lock()
		if m.calls[du.id] == du.e && du.e.call.State == Ringing {
			m.deliver(ctx, du.e, du.e.call.Tick(devs, m.d.Now()))
		}
		m.mu.Unlock()
	}
}

func expired(c *Call, now time.Time) bool {
	switch c.State {
	case Ended:
		return now.Sub(c.Changed) >= endedKeep
	case Answered:
		return now.Sub(c.Changed) >= answeredKeep
	}
	return false
}

type frameData struct {
	CallID  string      `json:"callID"`
	To      string      `json:"to"`
	Video   bool        `json:"video"`
	Payload httpx.Bytes `json:"payload"`
}

func decode(data json.RawMessage) (frameData, error) {
	var f frameData
	if err := json.Unmarshal(data, &f); err != nil || f.CallID == "" || len(f.CallID) > maxIDLen {
		return f, httpx.Err("bad_request", "bad call frame")
	}
	return f, nil
}

func (m *Module) invite(ctx context.Context, from core.Principal, data json.RawMessage) (any, error) {
	f, err := decode(data)
	if err != nil {
		return nil, err
	}
	if m.d.Config.TURNSecret == "" {
		return nil, httpx.Err("calls_disabled", "calls are not available")
	}
	if !uuidRE.MatchString(f.To) || strings.EqualFold(f.To, from.AccountID) || len(f.Payload) == 0 {
		return nil, httpx.Err("bad_request", "bad call invite")
	}
	f.To = strings.ToLower(f.To)
	devs, err := m.d.Directory.DeviceIDs(ctx, f.To)
	if err != nil {
		return nil, err
	}
	blocked, err := m.d.Blocks.IsBlocked(ctx, f.To, from.AccountID)
	if err != nil {
		return nil, err
	}
	now := m.d.Now()
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, dup := m.calls[f.CallID]; dup {
		return nil, httpx.Err("bad_request", "duplicate callID")
	}
	c := &Call{ID: f.CallID, CallerAccount: from.AccountID, CallerDevice: from.DeviceID,
		CalleeAccount: f.To, Video: f.Video, State: Ringing, Started: now, Changed: now}
	m.calls[f.CallID] = &entry{call: c, blocked: blocked}
	if blocked {
		return nil, nil // the caller learns only by the timeout
	}
	ring := map[string]any{"callID": c.ID, "fromAccountID": c.CallerAccount,
		"fromDeviceID": c.CallerDevice, "video": c.Video, "payload": httpx.Bytes(f.Payload)}
	for _, dev := range slices.Compact(slices.Clone(devs)) {
		if !m.frames.Send(dev, "call.ring", ring) {
			m.pushRing(dev, f.Payload)
		}
	}
	return nil, nil
}

// pushRing sends the PushCall push off the caller's goroutine: frame handlers
// run in the WebSocket read loop and a push can take seconds.
func (m *Module) pushRing(dev string, payload []byte) {
	m.bg.Add(1)
	go func() {
		defer m.bg.Done()
		ctx, cancel := context.WithTimeout(context.Background(), pushTimeout)
		defer cancel()
		if err := m.d.Push.Push(ctx, dev, core.PushCall, payload); err != nil {
			m.d.Log.Warn("call push failed")
		}
	}()
}

// op looks up the call, checks the caller may touch it, and runs fn with the
// callee's current devices. Anything that is not the caller's device or an
// account device of the callee gets not_found. Directory lookups happen
// outside m.mu (a slow database must not stall other calls); the entry is
// looked up again under the lock before anything changes.
func (m *Module) op(ctx context.Context, from core.Principal, data json.RawMessage,
	fn func(e *entry, devs []string, now time.Time) ([]Output, error)) (any, error) {
	f, err := decode(data)
	if err != nil {
		return nil, err
	}
	notFound := httpx.Err("not_found", "no such call")

	m.mu.Lock()
	e := m.lookup(f.CallID, m.d.Now())
	if e == nil || !m.mayAct(e, from) {
		m.mu.Unlock()
		return nil, notFound
	}
	calleeAccount, callerDevice, answered := e.call.CalleeAccount, e.call.CallerDevice, e.call.State == Answered
	m.mu.Unlock()

	devs, err := m.d.Directory.DeviceIDs(ctx, calleeAccount)
	if err != nil {
		return nil, err
	}
	callerGone := false
	if answered {
		if _, err := m.d.Directory.AccountOf(ctx, callerDevice); errors.Is(err, core.ErrNotFound) {
			callerGone = true
		}
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.d.Now()
	if m.lookup(f.CallID, now) != e {
		return nil, notFound // forgotten while we looked
	}
	if m.reap(ctx, e, devs, callerGone, now) {
		return nil, httpx.Err("bad_request", "call is no longer active")
	}
	outs, err := fn(e, devs, now)
	switch {
	case errors.Is(err, ErrNotParticipant):
		return nil, notFound
	case errors.Is(err, ErrWrongState):
		return nil, httpx.Err("bad_request", "call is not in a state that allows this")
	case err != nil:
		return nil, err
	}
	m.deliver(ctx, e, outs)
	return nil, nil
}

// reap ends an answered call as failed when either connected device has been
// revoked, so a dead device never keeps receiving signals. It reports whether
// it ended the call. Call it with m.mu held; it does no I/O but Send.
func (m *Module) reap(ctx context.Context, e *entry, devs []string, callerGone bool, now time.Time) bool {
	c := e.call
	if c.State != Answered {
		return false
	}
	if !callerGone && slices.Contains(devs, c.AnsweredBy) {
		return false
	}
	m.deliver(ctx, e, c.end(ReasonFailed, devs, now))
	return true
}

func (m *Module) lookup(id string, now time.Time) *entry {
	e := m.calls[id]
	if e != nil && expired(e.call, now) {
		delete(m.calls, id)
		return nil
	}
	return e
}

func (m *Module) mayAct(e *entry, from core.Principal) bool {
	c := e.call
	if from.DeviceID == c.CallerDevice && from.AccountID == c.CallerAccount {
		return true
	}
	return !e.blocked && from.AccountID == c.CalleeAccount
}

// deliver sends the machine's outputs. A blocked call's outputs go to the
// caller only.
func (m *Module) deliver(_ context.Context, e *entry, outs []Output) {
	for _, o := range outs {
		for _, dev := range o.To {
			if e.blocked && dev != e.call.CallerDevice {
				continue
			}
			m.frames.Send(dev, o.Type, wirePayload(o.Data))
		}
	}
}

func (m *Module) answer(ctx context.Context, from core.Principal, data json.RawMessage) (any, error) {
	f, err := decode(data)
	if err != nil {
		return nil, err
	}
	return m.op(ctx, from, data, func(e *entry, devs []string, now time.Time) ([]Output, error) {
		return e.call.Answer(from.DeviceID, devs, f.Payload, now)
	})
}

func (m *Module) signal(ctx context.Context, from core.Principal, data json.RawMessage) (any, error) {
	f, err := decode(data)
	if err != nil {
		return nil, err
	}
	return m.op(ctx, from, data, func(e *entry, devs []string, _ time.Time) ([]Output, error) {
		return e.call.Signal(from.DeviceID, devs, f.Payload)
	})
}

func (m *Module) decline(ctx context.Context, from core.Principal, data json.RawMessage) (any, error) {
	return m.op(ctx, from, data, func(e *entry, devs []string, now time.Time) ([]Output, error) {
		return e.call.Decline(from.DeviceID, devs, now)
	})
}

func (m *Module) hangup(ctx context.Context, from core.Principal, data json.RawMessage) (any, error) {
	return m.op(ctx, from, data, func(e *entry, devs []string, now time.Time) ([]Output, error) {
		return e.call.Hangup(from.DeviceID, devs, now)
	})
}

func (m *Module) status(_ context.Context, from core.Principal, data json.RawMessage) (any, error) {
	f, err := decode(data)
	if err != nil {
		return nil, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	e := m.lookup(f.CallID, m.d.Now())
	if e == nil || !m.mayAct(e, from) {
		return nil, httpx.Err("not_found", "no such call")
	}
	out := map[string]any{"state": e.call.State.String()}
	if e.call.State == Ended {
		out["reason"] = e.call.Reason
	}
	return out, nil
}

// wirePayload returns data with a []byte payload replaced by httpx.Bytes, so it
// goes out as base64url without padding like every client-facing binary field.
func wirePayload(data any) any {
	m, ok := data.(map[string]any)
	if !ok {
		return data
	}
	p, ok := m["payload"].([]byte)
	if !ok {
		return data
	}
	out := make(map[string]any, len(m))
	for k, v := range m {
		out[k] = v
	}
	out["payload"] = httpx.Bytes(p)
	return out
}
