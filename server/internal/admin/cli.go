package admin

import (
	"context"
	"errors"
	"fmt"
	"io"

	"github.com/jackc/pgx/v5"

	"github.com/drag0n-flyer/myApp/server/internal/core"
)

// Commands returns the admin CLI commands.
func (m *Module) Commands() map[string]core.Command {
	return map[string]core.Command{
		"admin sessions end":        m.cmdSessionsEnd,
		"admin user list":           m.cmdUserList,
		"admin user add":            m.cmdUserAdd,
		"admin user reset-password": m.cmdUserResetPassword,
		"admin user disable":        m.cmdUserSetDisabled(true),
		"admin user enable":         m.cmdUserSetDisabled(false),
		"admin user reset-2fa":      m.cmdUserReset2FA,
	}
}

// cmdSessionsEnd ends every dashboard session.
func (m *Module) cmdSessionsEnd(ctx context.Context, args []string, out io.Writer) error {
	if len(args) != 0 {
		return errors.New("unexpected argument")
	}
	var n int64
	err := pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, `delete from admin_sessions`)
		if err != nil {
			return err
		}
		n = tag.RowsAffected()
		return m.audit(ctx, tx, Entry{Actor: "cli", Action: "sessions.end", Detail: map[string]any{"count": n}})
	})
	if err != nil {
		return err
	}
	_, err = fmt.Fprintf(out, "ended %d sessions\n", n)
	return err
}
