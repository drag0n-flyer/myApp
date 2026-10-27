// Package admin is the operator dashboard: static pages and a JSON API served
// only on the admin listener. It has no public Routes method on purpose; app
// mounts Handler on the admin listener and nowhere else.
package admin

import (
	"context"
	"net/http"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/push"
	"github.com/drag0n-flyer/myApp/server/internal/turn"
	"github.com/jackc/pgx/v5"
)

// Session is the signed-in admin behind a request.
type Session struct{ AdminUserID, Username string }

// ActorID is the ID that stands for the signed-in admin in records.
func (s Session) ActorID() string { return s.AdminUserID }

// entry is an audit entry by this session's admin.
func (s Session) entry(action, target string, detail map[string]any) Entry {
	return Entry{Actor: "dashboard", AdminUserID: s.AdminUserID, Action: action, Target: target, Detail: detail}
}

type apiFunc func(w http.ResponseWriter, r *http.Request, s Session) error

// Identity is what the dashboard asks of the identity module.
type Identity interface {
	CreateInviteIn(ctx context.Context, q interface {
		QueryRow(context.Context, string, ...any) pgx.Row
	}, uses int, ttl time.Duration) (id, code string, expires time.Time, err error)
	RevokeInvite(ctx context.Context, id string) error
	AdminRevokeDevice(ctx context.Context, deviceID string) error
	AdminDeleteAccount(ctx context.Context, accountID string) error
}

// Sources are the other modules' read and write hooks.
type Sources struct {
	Started  time.Time
	Live     interface{ ConnectionCount() int }
	Push     interface{ Stats() push.Stats }
	Relays   interface{ Relays() []turn.RelayStatus }
	Identity Identity
	Reports  interface {
		MarkReportHandled(ctx context.Context, id, adminUserID string) error
	}
	Info interface {
		Document(ctx context.Context) ([]byte, error)
	}
}

// Module is the admin module.
type Module struct {
	d   core.Deps
	src Sources
}

// New returns the admin module.
func New(d core.Deps, src Sources) *Module {
	if d.Config.AdminAllowHTTP && d.Log != nil {
		d.Log.Warn("admin dashboard allows plain HTTP for APP_ADMIN_HOSTS; sign-in codes, passwords and session cookies cross the network unencrypted")
	}
	return &Module{d: d, src: src}
}

// Run deletes expired sessions and old audit entries every hour until
// ctx is done.
func (m *Module) Run(ctx context.Context) {
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			_ = m.cleanup(ctx)
		}
	}
}
