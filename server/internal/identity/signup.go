package identity

import (
	"crypto/subtle"
	"net/http"
	"regexp"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/jackc/pgx/v5"
)

var uuidRE = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// signupStart checks the invite, username and email, then emails a code.
// The invite is checked here and used up only when the sign-up finishes.
func (m *Module) signupStart(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		InviteCode string `json:"inviteCode"`
		Username   string `json:"username"`
		Email      string `json:"email"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	ctx := r.Context()
	if !ValidUsername(in.Username) {
		return httpx.Err("invalid_username", "usernames are 3 to 32 characters: a-z, 0-9 and _, starting with a letter")
	}
	emailLower, ok := NormalizeEmail(in.Email)
	if !ok {
		return httpx.Err("invalid_email", "that email address is not valid")
	}
	switch {
	case in.InviteCode == "":
		if m.d.Policy.RequiresInvite {
			return httpx.Err("invite_required", "this server needs an invite")
		}
	default:
		usable, err := m.inviteUsable(ctx, in.InviteCode)
		if err != nil {
			return err
		}
		if !usable {
			return httpx.Err("invite_invalid", "that invite is not valid")
		}
	}
	if Reserved(in.Username) {
		return httpx.Err("username_taken", "that username is taken")
	}
	var taken bool
	if err := m.d.DB.QueryRow(ctx,
		`select exists (select 1 from accounts where username = $1)`, in.Username).Scan(&taken); err != nil {
		return err
	}
	if taken {
		return httpx.Err("username_taken", "that username is taken")
	}

	// Code-send limits: 3/hour per email and 10/hour per IP.
	if ok, _ := m.emailRate.Allow(emailLower); !ok {
		return httpx.Err("rate_limited", "too many codes sent to this email; try again later")
	}
	if ok, _ := m.emailIPRate.Allow(m.ip(r)); !ok {
		return httpx.Err("rate_limited", "too many codes sent; try again later")
	}

	code := newCode()
	id := core.NewID()
	var invite any
	if in.InviteCode != "" {
		invite = in.InviteCode
	}
	_, err := m.d.DB.Exec(ctx,
		`insert into signups (id, account_id, username, email, email_lower, invite_code, code_hash, expires_at)
		 values ($1, $2, $3, $4, $5, $6, $7, $8)`,
		id, core.NewID(), in.Username, in.Email, emailLower, invite, hashCode(code),
		m.d.Now().Add(codeTTL*time.Minute))
	if err != nil {
		return err
	}
	subject, body := codeEmail(code)
	if err := m.d.Mail.Send(ctx, emailLower, subject, body); err != nil {
		_, _ = m.d.DB.Exec(ctx, `delete from signups where id = $1`, id)
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"signupID": id})
	return nil
}

// signupVerify checks the emailed code. Every check, right or wrong, uses one
// of the 5 attempts, and the increment is a single statement so concurrent
// guesses cannot exceed the limit.
func (m *Module) signupVerify(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		SignupID string `json:"signupID"`
		Code     string `json:"code"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if !uuidRE.MatchString(in.SignupID) {
		return httpx.Err("not_found", "no such sign-up")
	}
	ctx := r.Context()
	now := m.d.Now()
	var hash []byte
	err := m.d.DB.QueryRow(ctx,
		`update signups set attempts = attempts + 1
		 where id = $1 and attempts < $2 and expires_at > $3
		 returning code_hash`, in.SignupID, codeMaxAttempts, now).Scan(&hash)
	if err == pgx.ErrNoRows {
		var expires time.Time
		err = m.d.DB.QueryRow(ctx, `select expires_at from signups where id = $1`, in.SignupID).Scan(&expires)
		switch {
		case err == pgx.ErrNoRows:
			return httpx.Err("not_found", "no such sign-up")
		case err != nil:
			return err
		case !expires.After(now):
			return httpx.Err("expired", "this sign-up has expired; start again")
		default:
			return httpx.Err("rate_limited", "too many wrong codes; start again")
		}
	}
	if err != nil {
		return err
	}
	if subtle.ConstantTimeCompare(hash, hashCode(in.Code)) != 1 {
		return httpx.Err("code_invalid", "that code is not right")
	}
	if _, err := m.d.DB.Exec(ctx, `update signups set verified = true where id = $1`, in.SignupID); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, struct{}{})
	return nil
}
