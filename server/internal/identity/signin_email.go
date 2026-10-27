package identity

import (
	"crypto/subtle"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

// emailSignInStart answers POST /v1/signin/email/start. It always answers
// 202 {}: for an unknown or malformed email, for a rate-limited one, and when
// the mail could not be sent, so the answer does not reveal who has an account.
func (m *Module) emailSignInStart(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Email string `json:"email"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if err := m.sendSignInCode(r, in.Email); err != nil {
		// Only the error's Go type is logged: its text may carry the address.
		m.d.Log.Error("email sign-in code not sent", "errType", errType(err))
	}
	httpx.JSON(w, http.StatusAccepted, struct{}{})
	return nil
}

func (m *Module) sendSignInCode(r *http.Request, email string) error {
	ctx := r.Context()
	emailLower, ok := NormalizeEmail(email)
	if !ok {
		return nil
	}
	if ok, _ := m.emailRate.Allow(emailLower); !ok {
		return nil
	}
	if ok, _ := m.emailIPRate.Allow(m.ip(r)); !ok {
		return nil
	}
	var known bool
	if err := m.d.DB.QueryRow(ctx, `select exists (select 1 from accounts where email_lower = $1)`, emailLower).Scan(&known); err != nil {
		return err
	}
	if !known {
		return nil
	}
	code := newCode()
	now := m.d.Now()
	// Only the newest code works.
	if _, err := m.d.DB.Exec(ctx, `delete from email_codes where email_lower = $1 or expires_at <= $2`, emailLower, now); err != nil {
		return err
	}
	if _, err := m.d.DB.Exec(ctx,
		`insert into email_codes (id, email_lower, code_hash, expires_at) values ($1, $2, $3, $4)`,
		core.NewID(), emailLower, hashCode(code), now.Add(codeTTL*time.Minute)); err != nil {
		return err
	}
	subject, body := codeEmail(code)
	return m.d.Mail.Send(ctx, emailLower, subject, body)
}

// emailSignInFinish answers POST /v1/signin/email/finish. An unknown email
// gets code_invalid like a wrong code, and both are limited to 5 tries per
// email (then rate_limited), so the two cannot be told apart.
func (m *Module) emailSignInFinish(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Email      string `json:"email"`
		Code       string `json:"code"`
		DeviceName string `json:"deviceName"`
		Platform   string `json:"platform"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if err := validDevice(in.DeviceName, in.Platform); err != nil {
		return err
	}
	invalid := httpx.Err("code_invalid", "that code is not right")
	emailLower, ok := NormalizeEmail(in.Email)
	if !ok {
		return invalid
	}
	if ok, _ := m.emailTryRate.Allow(emailLower); !ok {
		return httpx.Err("rate_limited", "too many wrong codes; ask for a new one later")
	}
	ctx := r.Context()
	now := m.d.Now()
	tx, err := m.d.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit

	// One statement picks the newest live code and spends an attempt on it.
	var (
		id   string
		hash []byte
	)
	err = tx.QueryRow(ctx,
		`update email_codes set attempts = attempts + 1
		 where id = (select id from email_codes where email_lower = $1 and expires_at > $2 and attempts < $3
		             order by expires_at desc limit 1)
		 returning id, code_hash`, emailLower, now, codeMaxAttempts).Scan(&id, &hash)
	if errors.Is(err, pgx.ErrNoRows) {
		return invalid
	}
	if err != nil {
		return err
	}
	if subtle.ConstantTimeCompare(hash, hashCode(in.Code)) != 1 {
		// Keep the spent attempt.
		if err := tx.Commit(ctx); err != nil {
			return err
		}
		return invalid
	}
	var accountID string
	err = tx.QueryRow(ctx, `select id from accounts where email_lower = $1`, emailLower).Scan(&accountID)
	if errors.Is(err, pgx.ErrNoRows) {
		return invalid
	}
	if err != nil {
		return err
	}
	// A code works once.
	if tag, err := tx.Exec(ctx, `delete from email_codes where id = $1`, id); err != nil {
		return err
	} else if tag.RowsAffected() != 1 {
		return invalid
	}
	deviceID, token, err := m.issueSession(ctx, tx, accountID, in.DeviceName, in.Platform)
	if err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"accountID": accountID, "deviceID": deviceID, "sessionToken": token})
	return nil
}

func errType(err error) string { return fmt.Sprintf("%T", err) }
