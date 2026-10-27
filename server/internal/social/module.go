// Package social is lane E: user lookup, email discovery, profile settings,
// blocks and reports.
package social

import (
	"time"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
	"github.com/velixio/myApp/server/internal/ratelimit"
)

// Module is the social module. It is the server's core.Blocklist.
type Module struct {
	d           core.Deps
	lookups     *ratelimit.Limiter // 60 per hour per account
	discovers   *ratelimit.Limiter // 20 per day per account
	reportLimit *ratelimit.Limiter // 20 per day per account
}

var _ core.Blocklist = (*Module)(nil)

// New returns the social module.
func New(d core.Deps) *Module {
	return &Module{
		d:           d,
		lookups:     ratelimit.New(60, time.Hour, d.Now),
		discovers:   ratelimit.New(20, 24*time.Hour, d.Now),
		reportLimit: ratelimit.New(20, 24*time.Hour, d.Now),
	}
}

// Routes registers the social endpoints.
func (m *Module) Routes(r *httpx.Router) {
	m.lookupRoutes(r)
	m.blockRoutes(r)
	m.reportRoutes(r)
}

// Commands returns the admin CLI subcommands.
func (m *Module) Commands() map[string]core.Command {
	return map[string]core.Command{"reports list": m.reportsList}
}
