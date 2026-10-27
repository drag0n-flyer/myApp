package admin

import (
	"context"
	"encoding/json"

	"github.com/jackc/pgx/v5/pgconn"
)

// Entry is one audit log row. AdminUserID is empty when no admin account is
// involved (the command line, a failed sign-in).
type Entry struct {
	Actor, AdminUserID, Action, Target string
	Detail                             map[string]any
}

// audit inserts one admin_audit row at the module clock's time. q is the pool
// or a transaction.
func (m *Module) audit(ctx context.Context, q interface {
	Exec(context.Context, string, ...any) (pgconn.CommandTag, error)
}, e Entry) error {
	detail := e.Detail
	if detail == nil {
		detail = map[string]any{}
	}
	b, err := json.Marshal(detail)
	if err != nil {
		return err
	}
	var userID any
	if e.AdminUserID != "" {
		userID = e.AdminUserID
	}
	_, err = q.Exec(ctx,
		`insert into admin_audit (at, actor, admin_user_id, action, target, detail) values ($1, $2, $3, $4, $5, $6)`,
		m.d.Now(), e.Actor, userID, e.Action, e.Target, b)
	return err
}
