// Package delivery is lane B: KeyPackages, conversations, commits, messages,
// inbox and escrow.
package delivery

import (
	"context"
	"fmt"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/drag0n-flyer/myApp/server/internal/ratelimit"
)

// Module is the delivery module.
type Module struct {
	d          core.Deps
	claimLimit *ratelimit.Limiter // KeyPackage claims: 100/hour per account
	msgLimit   *ratelimit.Limiter // messages: 600/minute per device
}

// New returns the delivery module.
func New(d core.Deps) *Module {
	return &Module{
		d:          d,
		claimLimit: ratelimit.New(100, time.Hour, d.Now),
		msgLimit:   ratelimit.New(600, time.Minute, d.Now),
	}
}

// Routes registers the delivery endpoints.
func (m *Module) Routes(r *httpx.Router) {
	m.keyPackageRoutes(r)
	m.conversationRoutes(r)
	m.messageRoutes(r)
	m.inboxRoutes(r)
}

// Run prunes expired rows every hour until ctx is cancelled.
func (m *Module) Run(ctx context.Context) {
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if err := m.PruneOnce(ctx); err != nil && ctx.Err() == nil {
				// The error text is not logged (section 8); the type is enough.
				m.d.Log.Error("delivery prune failed", "errType", fmt.Sprintf("%T", err))
			}
		}
	}
}
