package identity

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/jackc/pgx/v5"
)

// labSessionCommand is the lab-only "lab session" command: it creates a
// session without passkey sign-in, for the Call Lab. It refuses to run unless
// APP_LAB_MODE=1. It prints "accountID deviceID token".
func (m *Module) labSessionCommand(ctx context.Context, args []string, out io.Writer) error {
	if !m.d.Config.LabMode {
		return errors.New("lab mode is off (set APP_LAB_MODE=1)")
	}
	fs := flag.NewFlagSet("lab session", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	username := fs.String("username", "", "account username (created if missing)")
	device := fs.String("device", "", "device name")
	platform := fs.String("platform", "", "iphone or mac")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if !ValidUsername(*username) {
		return fmt.Errorf("invalid username %q", *username)
	}
	if err := validDevice(*device, *platform); err != nil {
		return err
	}

	tx, err := m.d.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var accountID string
	err = tx.QueryRow(ctx, `select id from accounts where username = $1`, *username).Scan(&accountID)
	if errors.Is(err, pgx.ErrNoRows) {
		accountID = core.NewID()
		email := *username + "@lab.invalid"
		_, err = tx.Exec(ctx,
			`insert into accounts (id, username, email, email_lower, created_at) values ($1, $2, $3, $3, $4)`,
			accountID, *username, email, m.d.Now())
	}
	if err != nil {
		return err
	}
	deviceID, token, err := m.issueSession(ctx, tx, accountID, *device, *platform)
	if err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	_, err = fmt.Fprintf(out, "%s %s %s\n", accountID, deviceID, token)
	return err
}
