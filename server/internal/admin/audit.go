package admin

import (
	"context"
	"encoding/json"

	"github.com/jackc/pgx/v5/pgconn"
)

// Entry is one audit log row. TokenID and AdminUserID are empty when no token
// or admin account is involved.
type Entry struct {
	Actor, TokenID, AdminUserID, Action, Target string
	Detail                                      map[string]any
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
	var tokenID any
	if e.TokenID != "" {
		tokenID = e.TokenID
	}
	var userID any
	if e.AdminUserID != "" {
		userID = e.AdminUserID
	}
	_, err = q.Exec(ctx,
		`insert into admin_audit (at, actor, token_id, admin_user_id, action, target, detail) values ($1, $2, $3, $4, $5, $6, $7)`,
		m.d.Now(), e.Actor, tokenID, userID, e.Action, e.Target, b)
	return err
}
