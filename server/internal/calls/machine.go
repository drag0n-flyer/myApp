package calls

import (
	"errors"
	"slices"
	"time"
)

// State is a call's lifecycle state.
type State int

// Call states (design §7.3).
const (
	Ringing State = iota
	Answered
	Ended
)

func (s State) String() string {
	switch s {
	case Ringing:
		return "ringing"
	case Answered:
		return "answered"
	default:
		return "ended"
	}
}

// End reasons (design §7.3).
const (
	ReasonDeclined          = "declined"
	ReasonHangup            = "hangup"
	ReasonMissed            = "missed"
	ReasonAnsweredElsewhere = "answered_elsewhere"
	ReasonFailed            = "failed"
)

// ringTimeout is how long a call may ring before it is missed.
const ringTimeout = 45 * time.Second

var (
	// ErrNotParticipant means the device may not do this on the call. The
	// caller reports it as not_found.
	ErrNotParticipant = errors.New("calls: not a participant")
	// ErrWrongState means the call's state does not allow the operation.
	ErrWrongState = errors.New("calls: wrong state")
)

// Call is one call. It is a plain value with no locking; the owner of the
// Call serialises access. The callee devices are passed in per operation
// because they are looked up from the directory at the time.
type Call struct {
	ID            string
	CallerAccount string
	CallerDevice  string
	CalleeAccount string
	AnsweredBy    string // callee device that answered; set at most once
	Video         bool
	State         State
	Reason        string // set when State is Ended
	Started       time.Time
	Changed       time.Time
}

// Output is a frame the machine wants sent to a set of devices.
type Output struct {
	To   []string // device IDs
	Type string   // WebSocket frame type
	Data any
}

// timedOut reports whether the ring time is up. Answer and Decline refuse a
// call past it (ErrWrongState, call unchanged) so the timeout does not depend
// on how often Tick runs; the next Tick ends the call as missed.
func (c *Call) timedOut(now time.Time) bool { return now.Sub(c.Started) >= ringTimeout }

func (c *Call) isCallee(device string, callees []string) bool {
	return device != c.CallerDevice && slices.Contains(callees, device)
}

// everyone is the caller device plus every callee device, without duplicates.
func (c *Call) everyone(callees []string) []string {
	out := []string{c.CallerDevice}
	for _, d := range callees {
		if !slices.Contains(out, d) {
			out = append(out, d)
		}
	}
	return out
}

func (c *Call) ended(to []string) Output {
	return Output{To: to, Type: "call.ended", Data: map[string]any{"callID": c.ID, "reason": c.Reason}}
}

// end moves the call to Ended with reason. To is every device that may still
// be involved: all callee devices while ringing, only the two connected
// devices once answered.
func (c *Call) end(reason string, callees []string, now time.Time) []Output {
	to := c.everyone(callees)
	if c.State == Answered {
		to = []string{c.CallerDevice, c.AnsweredBy}
	}
	c.State, c.Reason, c.Changed = Ended, reason, now
	return []Output{c.ended(to)}
}

// Answer lets a callee device pick up a ringing call. The caller is told, and
// every other callee device is told the call was answered elsewhere.
func (c *Call) Answer(device string, calleeDevices []string, payload []byte, now time.Time) ([]Output, error) {
	if !c.isCallee(device, calleeDevices) {
		return nil, ErrNotParticipant
	}
	if c.State != Ringing || c.timedOut(now) {
		return nil, ErrWrongState
	}
	c.State, c.AnsweredBy, c.Changed = Answered, device, now
	outs := []Output{{To: []string{c.CallerDevice}, Type: "call.answered",
		Data: map[string]any{"callID": c.ID, "deviceID": device, "payload": payload}}}
	var others []string
	for _, d := range calleeDevices {
		if d != device && d != c.CallerDevice && !slices.Contains(others, d) {
			others = append(others, d)
		}
	}
	if len(others) > 0 {
		outs = append(outs, Output{To: others, Type: "call.ended",
			Data: map[string]any{"callID": c.ID, "reason": ReasonAnsweredElsewhere}})
	}
	return outs, nil
}

// Decline ends a ringing call as declined on every device. Only a callee
// device may decline.
func (c *Call) Decline(device string, calleeDevices []string, now time.Time) ([]Output, error) {
	if !c.isCallee(device, calleeDevices) {
		return nil, ErrNotParticipant
	}
	if c.State != Ringing || c.timedOut(now) {
		return nil, ErrWrongState
	}
	return c.end(ReasonDeclined, calleeDevices, now), nil
}

// Hangup ends the call as hangup. While ringing either side may hang up;
// once answered only the caller device and the answering device may.
func (c *Call) Hangup(device string, calleeDevices []string, now time.Time) ([]Output, error) {
	switch {
	case c.State == Answered:
		if device != c.CallerDevice && device != c.AnsweredBy {
			return nil, ErrNotParticipant
		}
	case device != c.CallerDevice && !c.isCallee(device, calleeDevices):
		return nil, ErrNotParticipant
	}
	if c.State == Ended {
		return nil, ErrWrongState
	}
	return c.end(ReasonHangup, calleeDevices, now), nil
}

// Signal routes an opaque signaling payload. While ringing, the caller's
// signals go to every callee device and a callee's go to the caller. Once
// answered, signals pass only between the caller and the answering device.
func (c *Call) Signal(device string, calleeDevices []string, payload []byte) ([]Output, error) {
	data := map[string]any{"callID": c.ID, "fromDeviceID": device, "payload": payload}
	var to []string
	switch {
	case c.State == Answered:
		switch device {
		case c.CallerDevice:
			to = []string{c.AnsweredBy}
		case c.AnsweredBy:
			to = []string{c.CallerDevice}
		default:
			return nil, ErrNotParticipant
		}
	case device == c.CallerDevice:
		for _, d := range calleeDevices {
			if d != c.CallerDevice && !slices.Contains(to, d) {
				to = append(to, d)
			}
		}
	case c.isCallee(device, calleeDevices):
		to = []string{c.CallerDevice}
	default:
		return nil, ErrNotParticipant
	}
	if c.State == Ended {
		return nil, ErrWrongState
	}
	if len(to) == 0 {
		return nil, nil
	}
	return []Output{{To: to, Type: "call.signal", Data: data}}, nil
}

// Tick ends a call that has rung for 45 s without an answer as missed.
func (c *Call) Tick(calleeDevices []string, now time.Time) []Output {
	if c.State != Ringing || !c.timedOut(now) {
		return nil
	}
	return c.end(ReasonMissed, calleeDevices, now)
}
