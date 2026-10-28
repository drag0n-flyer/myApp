package admin

import (
	"crypto/rand"
	"errors"
	"net/http"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
	qrcode "github.com/skip2/go-qrcode"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

const (
	totpPendingTTL  = 10 * time.Minute // a made but unconfirmed secret
	pendingLoginTTL = 5 * time.Minute  // between the password and the code
	pendingLoginMax = 5                // wrong codes per pending login
	pendingCookie   = "admin_pending"
)

var (
	errTotpOn   = errors.New("two-step sign-in is already on")
	errTotpOff  = errors.New("two-step sign-in is not on")
	errTotpCode = inputError("that code is not right")
)

// pendingLogin is a sign-in that has the right password and waits for a code.
type pendingLogin struct {
	adminID, username, hash string
	expires                 time.Time
	tries                   int
}

// pendingLogins lives in memory: a restart only makes the admin type the
// password again.
type pendingLogins struct {
	mu sync.Mutex
	m  map[string]*pendingLogin // by SHA-256 of the cookie value
}

func (p *pendingLogins) add(now time.Time, cookie string, l *pendingLogin) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.m == nil {
		p.m = map[string]*pendingLogin{}
	}
	for k, v := range p.m {
		if !now.Before(v.expires) {
			delete(p.m, k)
		}
	}
	p.m[string(hashString(cookie))] = l
}

// take consumes the pending login for one code attempt: it is removed under
// the lock, so two requests with the same cookie can never both verify. It
// returns nil when there is none, it expired, or it used up its attempts.
// The caller hands it back with giveBack after a wrong code.
func (p *pendingLogins) take(now time.Time, cookie string) *pendingLogin {
	p.mu.Lock()
	defer p.mu.Unlock()
	k := string(hashString(cookie))
	l := p.m[k]
	if l == nil {
		return nil
	}
	delete(p.m, k)
	if !now.Before(l.expires) || l.tries >= pendingLoginMax {
		return nil
	}
	l.tries++
	return l
}

// giveBack keeps the pending login for another try after a wrong code, unless
// its attempts are used up.
func (p *pendingLogins) giveBack(cookie string, l *pendingLogin) {
	if l.tries >= pendingLoginMax {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.m == nil {
		p.m = map[string]*pendingLogin{}
	}
	p.m[string(hashString(cookie))] = l
}

func (m *Module) setPendingCookie(w http.ResponseWriter, r *http.Request, value string, maxAge int) {
	http.SetCookie(w, &http.Cookie{
		Name: pendingCookie, Value: value, Path: "/admin", MaxAge: maxAge,
		HttpOnly: true, SameSite: http.SameSiteStrictMode, Secure: m.cookieSecure(r),
	})
}

// startPendingLogin answers a correct password of an admin with two-step
// sign-in on: no session yet, only a short-lived cookie.
func (m *Module) startPendingLogin(w http.ResponseWriter, r *http.Request, id, username, hash string) error {
	cookie, err := randomString(32)
	if err != nil {
		return err
	}
	m.limits().pending.add(m.d.Now(), cookie, &pendingLogin{
		adminID: id, username: username, hash: hash, expires: m.d.Now().Add(pendingLoginTTL)})
	m.setPendingCookie(w, r, cookie, int(pendingLoginTTL.Seconds()))
	httpx.JSON(w, http.StatusOK, map[string]bool{"needsCode": true})
	return nil
}

// loginCode finishes a two-step sign-in: POST /admin/api/login/code.
func (m *Module) loginCode(w http.ResponseWriter, r *http.Request, _ Session) error {
	if err := m.verifyCSRF(r, false); err != nil {
		return err
	}
	l := m.limits()
	if ok, wait := l.perAddr.Allow(httpx.ClientIP(r, m.d.Config.TrustProxy)); !ok {
		retryAfter(w, wait)
		return httpx.Err("rate_limited", "too many attempts")
	}
	var in struct {
		Code string `json:"code"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	c, cerr := r.Cookie(pendingCookie)
	if cerr != nil || c.Value == "" || len(c.Value) > 256 {
		return unauthorized()
	}
	ctx := r.Context()
	p := l.pending.take(m.d.Now(), c.Value)
	if p == nil {
		return unauthorized()
	}
	var sid string
	errWrong := errors.New("wrong code")
	err := pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		var secret []byte
		var on, disabled bool
		var last *int64
		var hash string
		err := tx.QueryRow(ctx,
			`select totp_secret, totp_enabled_at is not null, totp_last_step, password_hash, disabled_at is not null
			 from admin_users where id = $1 for update`, p.adminID).Scan(&secret, &on, &last, &hash, &disabled)
		if errors.Is(err, pgx.ErrNoRows) {
			return core.ErrNotFound
		}
		if err != nil {
			return err
		}
		// The password changed, the account was disabled or two-step sign-in
		// was turned off since the password step: start over.
		if disabled || !on || hash != p.hash {
			return core.ErrNotFound
		}
		var lastStep int64
		if last != nil {
			lastStep = *last
		}
		step, ok := totpMatch(secret, in.Code, m.d.Now(), lastStep)
		if !ok {
			return errWrong
		}
		if _, err := tx.Exec(ctx, `update admin_users set totp_last_step = $2, last_login_at = $3 where id = $1`,
			p.adminID, step, m.d.Now()); err != nil {
			return err
		}
		if sid, err = m.newSession(ctx, tx, r, p.adminID); err != nil {
			return err
		}
		return m.audit(ctx, tx, Entry{Actor: "dashboard", AdminUserID: p.adminID, Action: "login", Target: p.adminID,
			Detail: map[string]any{"username": p.username, "twoStep": true}})
	})
	switch {
	case err == nil:
		m.setPendingCookie(w, r, "", -1)
		m.setCookie(w, r, sid, 0)
		w.WriteHeader(http.StatusNoContent)
		return nil
	case errors.Is(err, errWrong):
		l.pending.giveBack(c.Value, p)
		if aerr := m.audit(ctx, m.d.DB, Entry{Actor: "dashboard", AdminUserID: p.adminID, Action: "login.code_failed",
			Target: p.adminID, Detail: map[string]any{"username": p.username}}); aerr != nil {
			m.d.Log.Error("admin audit write failed", "action", "login.code_failed")
			return aerr
		}
		return unauthorized()
	case errors.Is(err, core.ErrNotFound):
		return unauthorized()
	}
	// Another failure (the database, say): the admin may try again.
	l.pending.giveBack(c.Value, p)
	return err
}

// totpTryAllowed limits code guesses made while signed in.
func (m *Module) totpTryAllowed(w http.ResponseWriter, id string) error {
	if ok, wait := m.limits().totpTry.Allow(id); !ok {
		retryAfter(w, wait)
		return httpx.Err("rate_limited", "too many attempts")
	}
	return nil
}

// totpStart makes a new pending secret for the signed-in admin. It needs the
// current password, like turning two-step sign-in off.
func (m *Module) totpStart(w http.ResponseWriter, r *http.Request, s Session) error {
	var in struct {
		Password string `json:"password"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if err := m.totpTryAllowed(w, s.AdminUserID); err != nil {
		return err
	}
	var hash string
	if err := m.d.DB.QueryRow(r.Context(), `select password_hash from admin_users where id = $1`, s.AdminUserID).Scan(&hash); err != nil {
		return err
	}
	if !verifyPassword(hash, in.Password) {
		return adminAPIErr(inputError("the password is not right"))
	}
	secret := make([]byte, totpSecretLen)
	if _, err := rand.Read(secret); err != nil {
		return err
	}
	tag, err := m.d.DB.Exec(r.Context(),
		`update admin_users set totp_secret = $2, totp_pending_at = $3, totp_last_step = null
		 where id = $1 and totp_enabled_at is null`, s.AdminUserID, secret, m.d.Now())
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return adminAPIErr(errTotpOn)
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"secret": totpBase32.EncodeToString(secret)})
	return nil
}

// totpQR draws the pending secret as a QR code image.
func (m *Module) totpQR(w http.ResponseWriter, r *http.Request, s Session) error {
	var secret []byte
	err := m.d.DB.QueryRow(r.Context(),
		`select totp_secret from admin_users
		 where id = $1 and totp_enabled_at is null and totp_secret is not null and totp_pending_at > $2`,
		s.AdminUserID, m.d.Now().Add(-totpPendingTTL)).Scan(&secret)
	if errors.Is(err, pgx.ErrNoRows) {
		return httpx.Err("not_found", "no two-step setup is waiting")
	}
	if err != nil {
		return err
	}
	png, err := qrcode.Encode(otpauthURL(m.d.Config.Host, s.Username, totpBase32.EncodeToString(secret)), qrcode.Medium, 256)
	if err != nil {
		return err
	}
	h := w.Header()
	h.Set("Content-Type", "image/png")
	h.Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, err = w.Write(png)
	return err
}

// totpEnable turns two-step sign-in on after a correct code for the pending
// secret.
func (m *Module) totpEnable(w http.ResponseWriter, r *http.Request, s Session) error {
	var in struct {
		Code string `json:"code"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if err := m.totpTryAllowed(w, s.AdminUserID); err != nil {
		return err
	}
	ctx := r.Context()
	err := pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		var secret []byte
		var on bool
		var pendingAt *time.Time
		err := tx.QueryRow(ctx,
			`select totp_secret, totp_enabled_at is not null, totp_pending_at from admin_users where id = $1 for update`,
			s.AdminUserID).Scan(&secret, &on, &pendingAt)
		if err != nil {
			return err
		}
		if on {
			return errTotpOn
		}
		if secret == nil || pendingAt == nil || !pendingAt.After(m.d.Now().Add(-totpPendingTTL)) {
			return inputError("the setup ran out of time; start again")
		}
		step, ok := totpMatch(secret, in.Code, m.d.Now(), 0)
		if !ok {
			return errTotpCode
		}
		if _, err := tx.Exec(ctx,
			`update admin_users set totp_enabled_at = $2, totp_last_step = $3, totp_pending_at = null where id = $1`,
			s.AdminUserID, m.d.Now(), step); err != nil {
			return err
		}
		// The admin's other sessions end; the one that turned it on stays.
		keep := []byte{}
		if c, cerr := r.Cookie(cookieName); cerr == nil && c.Value != "" && len(c.Value) <= 256 {
			keep = hashString(c.Value)
		}
		if _, err := tx.Exec(ctx, `delete from admin_sessions where admin_user_id = $1 and id_hash <> $2`,
			s.AdminUserID, keep); err != nil {
			return err
		}
		return m.audit(ctx, tx, s.entry("admin.totp.enable", s.AdminUserID, map[string]any{"username": s.Username}))
	})
	if err != nil {
		return adminAPIErr(err)
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// totpDisable turns two-step sign-in off; it needs the password and a code.
func (m *Module) totpDisable(w http.ResponseWriter, r *http.Request, s Session) error {
	var in struct {
		Password string `json:"password"`
		Code     string `json:"code"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if err := m.totpTryAllowed(w, s.AdminUserID); err != nil {
		return err
	}
	ctx := r.Context()
	wrong := inputError("the password or the code is not right")
	err := pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		var secret []byte
		var on bool
		var last *int64
		var hash string
		err := tx.QueryRow(ctx,
			`select totp_secret, totp_enabled_at is not null, totp_last_step, password_hash from admin_users where id = $1 for update`,
			s.AdminUserID).Scan(&secret, &on, &last, &hash)
		if err != nil {
			return err
		}
		if !on {
			return errTotpOff
		}
		var lastStep int64
		if last != nil {
			lastStep = *last
		}
		pwOK := verifyPassword(hash, in.Password)
		if _, codeOK := totpMatch(secret, in.Code, m.d.Now(), lastStep); !pwOK || !codeOK {
			return wrong
		}
		if _, err := tx.Exec(ctx,
			`update admin_users set totp_secret = null, totp_enabled_at = null, totp_last_step = null, totp_pending_at = null where id = $1`,
			s.AdminUserID); err != nil {
			return err
		}
		return m.audit(ctx, tx, s.entry("admin.totp.disable", s.AdminUserID, map[string]any{"username": s.Username}))
	})
	if err != nil {
		return adminAPIErr(err)
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
