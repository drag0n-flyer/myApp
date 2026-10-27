package testkit

import (
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/velixio/myApp/server/internal/config"
	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/core/coretest"
	"github.com/velixio/myApp/server/internal/db"
	"github.com/velixio/myApp/server/internal/mail/mailtest"
)

// Clock is a controllable time source.
type Clock struct {
	mu  sync.Mutex
	now time.Time
}

// NewClock returns a Clock at 2026-11-01T12:00:00Z.
func NewClock() *Clock {
	return &Clock{now: time.Date(2026, 11, 1, 12, 0, 0, 0, time.UTC)}
}

// Now returns the current fake time.
func (c *Clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

// Advance moves the fake time forward by d.
func (c *Clock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

// Fakes are the test doubles behind the Deps that NewDeps returns.
type Fakes struct {
	Clock  *Clock
	Notify *coretest.Notifier
	Push   *coretest.Pusher
	Blocks *coretest.Blocklist
	Mail   *mailtest.Capture
	Live   *coretest.Live
}

// NewDeps returns Deps on a fresh test database, with the real Postgres
// directory and a fake for everything else, plus the fakes. Each opt runs last
// and may replace any field.
func NewDeps(t testing.TB, opts ...func(*core.Deps)) (core.Deps, *Fakes) {
	t.Helper()
	f := &Fakes{
		Clock:  NewClock(),
		Notify: &coretest.Notifier{},
		Push:   &coretest.Pusher{},
		Blocks: &coretest.Blocklist{},
		Mail:   &mailtest.Capture{},
		Live:   &coretest.Live{},
	}
	pool := DB(t)
	d := core.Deps{
		DB: pool,
		Config: config.Config{
			Host:       "chat.test",
			TURNSecret: "s3cret",
			Relays:     []config.Relay{{Host: "127.0.0.1"}},
		},
		Policy:    config.DefaultPolicy(),
		Log:       slog.New(slog.DiscardHandler),
		Now:       f.Clock.Now,
		Mail:      f.Mail,
		Directory: db.NewDirectory(pool),
		Blocks:    f.Blocks,
		Notify:    f.Notify,
		Push:      f.Push,
		Live:      f.Live,
	}
	for _, opt := range opts {
		opt(&d)
	}
	return d, f
}
