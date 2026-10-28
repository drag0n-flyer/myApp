package admin

import (
	"bufio"
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"github.com/jackc/pgx/v5"
	"io"
	"math/big"
	"os"
	"strings"

	"github.com/drag0n-flyer/myApp/server/internal/core"
)

// cliStdin is where the CLI reads a password from.
var cliStdin io.Reader = os.Stdin

// generatedAlphabet leaves out look-alike characters (0 O o 1 l I).
const generatedAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

// generatePassword returns 20 random characters.
func generatePassword() (string, error) {
	max := big.NewInt(int64(len(generatedAlphabet)))
	b := make([]byte, 20)
	for i := range b {
		n, err := rand.Int(rand.Reader, max)
		if err != nil {
			return "", err
		}
		b[i] = generatedAlphabet[n.Int64()]
	}
	return string(b), nil
}

// readPasswordLine reads one line, without its line break.
func readPasswordLine(in io.Reader) (string, error) {
	line, err := bufio.NewReaderSize(io.LimitReader(in, 4096), 4096).ReadString('\n')
	if err != nil && !(errors.Is(err, io.EOF) && line != "") {
		if errors.Is(err, io.EOF) {
			return "", errors.New("no password on standard input")
		}
		return "", err
	}
	if !strings.HasSuffix(line, "\n") && len(line) >= 4096 {
		return "", errors.New("the password line is too long")
	}
	return strings.TrimRight(line, "\r\n"), nil
}

// userArgs splits "<username> [--generate]" in either order.
func userArgs(cmd string, args []string, allowGenerate bool) (username string, generate bool, err error) {
	for _, a := range args {
		switch {
		case a == "--generate" && allowGenerate:
			generate = true
		case strings.HasPrefix(a, "-"):
			return "", false, fmt.Errorf("unknown option %s", a)
		case username == "":
			username = a
		default:
			return "", false, errors.New("unexpected argument")
		}
	}
	if username == "" {
		return "", false, fmt.Errorf("usage: appserver %s <username>%s", cmd, map[bool]string{true: " [--generate]", false: ""}[allowGenerate])
	}
	return username, generate, nil
}

// cliPassword gets the new password: generated, or one line from stdin. On a
// terminal the typing is not shown.
func cliPassword(generate bool) (pw string, generated bool, err error) {
	if generate {
		pw, err = generatePassword()
		return pw, true, err
	}
	if f, ok := cliStdin.(*os.File); ok && isTerminal(f) {
		fmt.Fprint(os.Stderr, "New password (not shown): ")
		err = withoutEcho(f, func() (err error) { pw, err = readPasswordLine(f); return })
		fmt.Fprintln(os.Stderr)
		return pw, false, err
	}
	pw, err = readPasswordLine(cliStdin)
	return pw, false, err
}

var cliBy = Entry{Actor: "cli"}

func (m *Module) cmdUserList(ctx context.Context, args []string, out io.Writer) error {
	if len(args) != 0 {
		return errors.New("unexpected argument")
	}
	rows, err := m.loadAdmins(ctx)
	if err != nil {
		return err
	}
	if len(rows) == 0 {
		_, err = fmt.Fprintln(out, "no admins yet; open the dashboard to create the first one, or run appserver admin user add")
		return err
	}
	for _, a := range rows {
		status, last := "enabled", "never"
		if a.Disabled {
			status = "disabled"
		}
		if a.LastLoginAt != nil {
			last = a.LastLoginAt.UTC().Format("2006-01-02 15:04 UTC")
		}
		if _, err := fmt.Fprintf(out, "%-32s %-8s created %s, last sign-in %s\n", a.Username, status, a.CreatedAt.UTC().Format("2006-01-02"), last); err != nil {
			return err
		}
	}
	return nil
}

func (m *Module) cmdUserAdd(ctx context.Context, args []string, out io.Writer) error {
	username, generate, err := userArgs("admin user add", args, true)
	if err != nil {
		return err
	}
	pw, generated, err := cliPassword(generate)
	if err != nil {
		return err
	}
	if _, err := m.createAdmin(ctx, username, pw, cliBy); err != nil {
		return err
	}
	u, _ := normalizeUsername(username)
	fmt.Fprintf(out, "created admin %s\n", u)
	if generated {
		fmt.Fprintf(out, "password: %s\n", pw)
	}
	return nil
}

func (m *Module) cmdUserResetPassword(ctx context.Context, args []string, out io.Writer) error {
	username, generate, err := userArgs("admin user reset-password", args, true)
	if err != nil {
		return err
	}
	id, err := m.cliAdminID(ctx, username)
	if err != nil {
		return err
	}
	pw, generated, err := cliPassword(generate)
	if err != nil {
		return err
	}
	if err := m.setPassword(ctx, id, pw, cliBy); err != nil {
		return err
	}
	fmt.Fprintf(out, "password changed for %s; their sessions ended\n", normalizeLoose(username))
	if generated {
		fmt.Fprintf(out, "password: %s\n", pw)
	}
	return nil
}

func (m *Module) cliAdminID(ctx context.Context, username string) (string, error) {
	id, err := m.adminByUsername(ctx, username)
	if errors.Is(err, core.ErrNotFound) {
		return "", fmt.Errorf("no admin called %s", normalizeLoose(username))
	}
	return id, err
}

func (m *Module) cmdUserSetDisabled(disable bool) core.Command {
	name := "admin user enable"
	if disable {
		name = "admin user disable"
	}
	return func(ctx context.Context, args []string, out io.Writer) error {
		username, _, err := userArgs(name, args, false)
		if err != nil {
			return err
		}
		id, err := m.cliAdminID(ctx, username)
		if err != nil {
			return err
		}
		wasLast, changed, err := m.setDisabled(ctx, id, disable, cliBy, false)
		if err != nil {
			return err
		}
		u := normalizeLoose(username)
		if !changed {
			state := map[bool]string{true: "disabled", false: "enabled"}[disable]
			fmt.Fprintf(out, "%s is already %s\n", u, state)
			return nil
		}
		if disable {
			fmt.Fprintf(out, "disabled %s; their sessions ended\n", u)
			if wasLast {
				fmt.Fprintln(out, "warning: no enabled admin is left. Run appserver admin user enable or appserver admin user add to get back in.")
			}
		} else {
			fmt.Fprintf(out, "enabled %s\n", u)
		}
		return nil
	}
}

// cmdUserReset2FA turns two-step sign-in off for an admin who lost their
// authenticator, and ends that admin's sessions. There are no recovery codes;
// this is the way back in.
func (m *Module) cmdUserReset2FA(ctx context.Context, args []string, out io.Writer) error {
	username, _, err := userArgs("admin user reset-2fa", args, false)
	if err != nil {
		return err
	}
	id, err := m.cliAdminID(ctx, username)
	if err != nil {
		return err
	}
	u := normalizeLoose(username)
	reset := false
	err = pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		var on bool
		if err := tx.QueryRow(ctx, `select totp_enabled_at is not null from admin_users where id = $1 for update`, id).Scan(&on); err != nil {
			return err
		}
		if !on {
			return nil
		}
		reset = true
		if _, err := tx.Exec(ctx,
			`update admin_users set totp_secret = null, totp_enabled_at = null, totp_last_step = null, totp_pending_at = null where id = $1`, id); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `delete from admin_sessions where admin_user_id = $1`, id); err != nil {
			return err
		}
		e := cliBy
		e.Action, e.Target, e.Detail = "admin.totp.reset", id, map[string]any{"username": u}
		return m.audit(ctx, tx, e)
	})
	if err != nil {
		return err
	}
	if !reset {
		_, err = fmt.Fprintf(out, "two-step sign-in is already off for %s\n", u)
		return err
	}
	_, err = fmt.Fprintf(out, "two-step sign-in is off for %s; their sessions ended\n", u)
	return err
}
