package identity

import (
	"context"
	"crypto/rand"
	"flag"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"time"

	"github.com/velixio/myApp/server/internal/httpx"
)

// inviteAlphabet has 32 letters and digits, without I, O, 0 and 1.
const inviteAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

var inviteRE = regexp.MustCompile(`^[ABCDEFGHJKLMNPQRSTUVWXYZ2-9]{5}-[ABCDEFGHJKLMNPQRSTUVWXYZ2-9]{5}$`)

// NewInviteCode returns a random code in the form XXXXX-XXXXX.
func NewInviteCode() string {
	var b [10]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic("identity: no randomness: " + err.Error())
	}
	out := make([]byte, 0, 11)
	for i, v := range b {
		if i == 5 {
			out = append(out, '-')
		}
		out = append(out, inviteAlphabet[v%32]) // 256 is a multiple of 32: no bias
	}
	return string(out)
}

// CreateInvite stores a new invite good for uses sign-ups until ttl from now,
// and returns its code.
func (m *Module) CreateInvite(ctx context.Context, uses int, ttl time.Duration) (string, error) {
	if uses < 1 || ttl <= 0 {
		return "", fmt.Errorf("invite needs at least one use and a positive lifetime")
	}
	code := NewInviteCode()
	_, err := m.d.DB.Exec(ctx,
		`insert into invites (code, uses_left, expires_at) values ($1, $2, $3)`,
		code, uses, m.d.Now().Add(ttl))
	if err != nil {
		return "", err
	}
	return code, nil
}

// inviteUsable reports whether code names an invite that has uses left and
// has not expired. It does not use the invite up.
func (m *Module) inviteUsable(ctx context.Context, code string) (bool, error) {
	if !inviteRE.MatchString(code) {
		return false, nil
	}
	var ok bool
	err := m.d.DB.QueryRow(ctx,
		`select exists (select 1 from invites where code = $1 and uses_left > 0 and expires_at > $2)`,
		code, m.d.Now()).Scan(&ok)
	return ok, err
}

func (m *Module) checkInvite(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Code string `json:"code"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	ok, err := m.inviteUsable(r.Context(), in.Code)
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]bool{"valid": ok})
	return nil
}

func (m *Module) inviteCreateCommand(ctx context.Context, args []string, out io.Writer) error {
	fs := flag.NewFlagSet("invite create", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	uses := fs.Int("uses", 1, "number of sign-ups the invite allows")
	days := fs.Int("days", 14, "days until the invite expires")
	if err := fs.Parse(args); err != nil {
		return err
	}
	code, err := m.CreateInvite(ctx, *uses, time.Duration(*days)*24*time.Hour)
	if err != nil {
		return err
	}
	_, err = fmt.Fprintf(out, "https://%s/invite/%s\n", m.d.Config.Host, code)
	return err
}
