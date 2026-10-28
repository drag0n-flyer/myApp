// Package core holds the interfaces and values shared by every server module.
package core

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"log/slog"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/config"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ErrNotFound is returned when a looked-up record does not exist.
var ErrNotFound = errors.New("not found")

// NewID returns a random version 4 UUID in lowercase hex.
func NewID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic("core: no randomness: " + err.Error())
	}
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	var out [36]byte
	hex.Encode(out[0:8], b[0:4])
	out[8] = '-'
	hex.Encode(out[9:13], b[4:6])
	out[13] = '-'
	hex.Encode(out[14:18], b[6:8])
	out[18] = '-'
	hex.Encode(out[19:23], b[8:10])
	out[23] = '-'
	hex.Encode(out[24:], b[10:])
	return string(out[:])
}

// Directory maps accounts to their devices and back.
type Directory interface {
	DeviceIDs(ctx context.Context, accountID string) ([]string, error)
	AccountOf(ctx context.Context, deviceID string) (string, error) // ErrNotFound if none
}

// Principal identifies the authenticated caller of a request.
type Principal struct{ AccountID, DeviceID string }

// SessionVerifier turns a session token into the Principal it belongs to.
// Implementations must return ErrNotFound (possibly wrapped) for an unknown,
// expired or revoked token. Any other error means the check itself failed and
// is reported to clients as a retryable 500, not as a sign-out.
type SessionVerifier interface {
	Verify(ctx context.Context, token string) (Principal, error)
}

// PushKind says what to send to a device that is offline.
type PushKind int

const (
	PushNone PushKind = iota
	PushMessage
	PushCall
)

// Event is something to tell a device about.
type Event struct {
	Type        string   // WebSocket frame type, e.g. "inbox", "call.ring"
	Data        any      // marshalled as the frame's "data"
	Push        PushKind // what to send if the device is offline
	PushPayload []byte   // opaque; padded by the push sender
}

// Notifier delivers an Event to a device: over its WebSocket when connected,
// by push otherwise.
type Notifier interface {
	Notify(ctx context.Context, deviceID string, ev Event)
}

// Pusher sends a push notification to a device through the push gateway.
type Pusher interface {
	Push(ctx context.Context, deviceID string, kind PushKind, payload []byte) error
}

// Blocklist answers whether blocker has blocked blocked.
type Blocklist interface {
	IsBlocked(ctx context.Context, blocker, blocked string) (bool, error)
}

// Mailer sends one plain-text email.
type Mailer interface {
	Send(ctx context.Context, to, subject, body string) error
}

// Disconnector closes a device's live WebSocket.
type Disconnector interface {
	Disconnect(deviceID string, code int, reason string)
}

// Command is an admin CLI subcommand.
type Command func(ctx context.Context, args []string, out io.Writer) error

// Deps is everything a module needs; app wires it once and passes it to every
// module's New.
type Deps struct {
	DB        *pgxpool.Pool
	Config    config.Config
	Policy    config.Policy
	Log       *slog.Logger
	Now       func() time.Time
	Mail      Mailer
	Directory Directory
	Blocks    Blocklist
	Notify    Notifier
	Push      Pusher
	Live      Disconnector
}
