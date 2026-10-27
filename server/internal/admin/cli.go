package admin

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"github.com/drag0n-flyer/myApp/server/internal/core"
)

// Commands returns the admin CLI commands.
func (m *Module) Commands() map[string]core.Command {
	return map[string]core.Command{
		"admin token create":        m.cmdTokenCreate,
		"admin sessions end":        m.cmdSessionsEnd,
		"admin user list":           m.cmdUserList,
		"admin user add":            m.cmdUserAdd,
		"admin user reset-password": m.cmdUserResetPassword,
		"admin user disable":        m.cmdUserSetDisabled(true),
		"admin user enable":         m.cmdUserSetDisabled(false),
	}
}

// cmdTokenCreate prints one new login token and a newline, nothing else.
func (m *Module) cmdTokenCreate(ctx context.Context, args []string, out io.Writer) error {
	fs := flag.NewFlagSet("admin token create", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	label := fs.String("label", "", "text shown in the audit log")
	minutes := fs.Int("minutes", 15, "minutes the token works if unused")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 0 {
		return errors.New("unexpected argument")
	}
	if *minutes < 1 || *minutes > 60 {
		return errors.New("--minutes must be from 1 to 60")
	}
	if utf8.RuneCountInString(*label) > 64 {
		return errors.New("--label must be at most 64 characters")
	}
	for _, r := range *label {
		if unicode.IsControl(r) {
			return errors.New("--label must not contain control characters")
		}
	}
	_, token, err := m.createToken(ctx, *label, time.Duration(*minutes)*time.Minute)
	if err != nil {
		return err
	}
	_, err = fmt.Fprintln(out, token)
	return err
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
