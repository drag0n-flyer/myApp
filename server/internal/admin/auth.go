package admin

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"math"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/drag0n-flyer/myApp/server/internal/ratelimit"
)

const (
	cookieName      = "admin_session"
	idleTimeout     = 15 * time.Minute
	sessionLifetime = 8 * time.Hour
	auditKeep       = 365 * 24 * time.Hour
)

// BackgroundHeader marks a request the page sends on its own, such as the
// overview's 30 s refresh. It is authenticated normally but does not reset
// the idle timer; the absolute lifetime still applies.
const BackgroundHeader = "X-Admin-Background"

// sessionState is what requireSession learned about the request's session. It
// travels in the request context, because Session is fixed by the skeleton.
type sessionState struct {
	idHash, csrfHash []byte
	csrf             string
	expiresAt        time.Time
}

type ctxKey struct{}

func stateFrom(ctx context.Context) *sessionState {
	st, _ := ctx.Value(ctxKey{}).(*sessionState)
	return st
}

// csrfToken derives the session's CSRF token from its cookie value, so it is
// stable for the session's life, can be handed to the page again on every
// GET /session, and is stored only as a hash. Without the HttpOnly cookie it
// cannot be computed.
func csrfToken(cookieValue string) string {
	mac := hmac.New(sha256.New, []byte(cookieValue))
	mac.Write([]byte("admin csrf token v1"))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// loginLimits are the login rate limiters of one module. Module has no field
// for them (module.go belongs to the skeleton), so they live here by module.
type loginLimits struct{ perAddr, global *ratelimit.Limiter }

var limitsByModule sync.Map // *Module -> *loginLimits

func (m *Module) limits() *loginLimits {
	if l, ok := limitsByModule.Load(m); ok {
		return l.(*loginLimits)
	}
	l, _ := limitsByModule.LoadOrStore(m, &loginLimits{
		perAddr: ratelimit.New(5, time.Minute, m.d.Now),
		global:  ratelimit.New(30, time.Hour, m.d.Now),
	})
	return l.(*loginLimits)
}

func unauthorized() error { return httpx.Err("unauthorized", "sign in required") }

// cookieSecure is false only for a loopback Host.
func (m *Module) cookieSecure(r *http.Request) bool {
	host := r.Host
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	host = strings.TrimSuffix(strings.TrimPrefix(strings.ToLower(host), "["), "]")
	switch host {
	case "localhost", "127.0.0.1", "::1":
		return false
	}
	return true
}

func (m *Module) setCookie(w http.ResponseWriter, r *http.Request, value string, maxAge int) {
	http.SetCookie(w, &http.Cookie{
		Name: cookieName, Value: value, Path: "/admin", MaxAge: maxAge,
		HttpOnly: true, SameSite: http.SameSiteStrictMode, Secure: m.cookieSecure(r),
	})
}

func retryAfter(w http.ResponseWriter, wait time.Duration) {
	w.Header().Set("Retry-After", strconv.Itoa(max(1, int(math.Ceil(wait.Seconds())))))
}

// login exchanges a one-time token for a session cookie.
func (m *Module) login(w http.ResponseWriter, r *http.Request, _ Session) error {
	// The login has no session yet, so no token, but the header checks stay.
	if err := m.verifyCSRF(r, false); err != nil {
		return err
	}
	l := m.limits()
	if ok, wait := l.perAddr.Allow(httpx.ClientIP(r, m.d.Config.TrustProxy)); !ok {
		retryAfter(w, wait)
		return httpx.Err("rate_limited", "too many attempts")
	}
	if ok, wait := l.global.Allow("all"); !ok {
		retryAfter(w, wait)
		return httpx.Err("rate_limited", "too many attempts")
	}
	var in struct {
		Token string `json:"token"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	ctx := r.Context()
	sid, err := randomString(32)
	if err != nil {
		return err
	}
	csrf := csrfToken(sid)
	now := m.d.Now()
	err = pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		id, _, err := m.redeemToken(ctx, tx, in.Token)
		if err != nil {
			return err
		}
		// A browser that signs in again gives up its previous session.
		if old, cerr := r.Cookie(cookieName); cerr == nil && old.Value != "" && len(old.Value) <= 256 {
			if _, err := tx.Exec(ctx, `delete from admin_sessions where id_hash = $1`, hashString(old.Value)); err != nil {
				return err
			}
		}
		if _, err := tx.Exec(ctx,
			`insert into admin_sessions (id_hash, token_id, csrf_hash, created_at, last_seen_at, expires_at)
			 values ($1, $2, $3, $4, $4, $5)`,
			hashString(sid), id, hashString(csrf), now, now.Add(sessionLifetime)); err != nil {
			return err
		}
		return m.audit(ctx, tx, Entry{Actor: "dashboard", TokenID: id, Action: "login", Target: id})
	})
	if errors.Is(err, core.ErrNotFound) {
		// Unknown, used and expired tokens all look the same.
		if aerr := m.audit(ctx, m.d.DB, Entry{Actor: "dashboard", Action: "login.failed"}); aerr != nil {
			m.d.Log.Error("admin audit write failed", "action", "login.failed")
			return aerr
		}
		return unauthorized()
	}
	if err != nil {
		return err
	}
	m.setCookie(w, r, sid, 0)
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// logout ends the request's session.
func (m *Module) logout(w http.ResponseWriter, r *http.Request, s Session) error {
	st := stateFrom(r.Context())
	if st == nil {
		return unauthorized()
	}
	ctx := r.Context()
	err := pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `delete from admin_sessions where id_hash = $1`, st.idHash); err != nil {
			return err
		}
		return m.audit(ctx, tx, Entry{Actor: "dashboard", TokenID: s.TokenID, Action: "logout", Target: s.TokenID})
	})
	if err != nil {
		return err
	}
	m.setCookie(w, r, "", -1)
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// session describes the request's session to the page.
func (m *Module) session(w http.ResponseWriter, r *http.Request, s Session) error {
	st := stateFrom(r.Context())
	if st == nil {
		return unauthorized()
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"tokenID":            s.TokenID,
		"label":              s.Label,
		"csrf":               st.csrf,
		"idleTimeoutSeconds": int(idleTimeout.Seconds()),
		"expiresAt":          st.expiresAt.UTC(),
	})
	return nil
}

// requireSession runs h as the signed-in token, or answers 401. A session that
// is idle too long or past its lifetime is deleted.
func (m *Module) requireSession(h apiFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s, st, err := m.lookupSession(r)
		if err != nil {
			httpx.WriteError(w, err)
			return
		}
		serve(h, s, m.d.Log).ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, st)))
	})
}

func (m *Module) lookupSession(r *http.Request) (Session, *sessionState, error) {
	c, err := r.Cookie(cookieName)
	if err != nil || c.Value == "" || len(c.Value) > 256 {
		return Session{}, nil, unauthorized()
	}
	ctx := r.Context()
	idHash := hashString(c.Value)
	var (
		s                 Session
		gotHash, csrfHash []byte
		lastSeen, expires time.Time
		ok                bool
	)
	err = pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		err := tx.QueryRow(ctx,
			`select s.id_hash, s.token_id, t.label, s.csrf_hash, s.last_seen_at, s.expires_at
			 from admin_sessions s join admin_tokens t on t.id = s.token_id
			 where s.id_hash = $1 for update of s`, idHash).
			Scan(&gotHash, &s.TokenID, &s.Label, &csrfHash, &lastSeen, &expires)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		now := m.d.Now()
		if subtle.ConstantTimeCompare(gotHash, idHash) != 1 {
			return nil
		}
		if now.Sub(lastSeen) > idleTimeout || !now.Before(expires) {
			_, err := tx.Exec(ctx, `delete from admin_sessions where id_hash = $1`, idHash)
			return err
		}
		ok = true
		if r.Header.Get(BackgroundHeader) != "" {
			return nil // the page's own refresh does not count as activity
		}
		_, err = tx.Exec(ctx, `update admin_sessions set last_seen_at = $2 where id_hash = $1`, idHash, now)
		return err
	})
	if err != nil {
		return Session{}, nil, err
	}
	if !ok {
		return Session{}, nil, unauthorized()
	}
	return s, &sessionState{idHash: idHash, csrfHash: csrfHash, csrf: csrfToken(c.Value), expiresAt: expires}, nil
}

// cleanup deletes idle and expired sessions, unused expired tokens and audit
// entries older than 365 days. Used tokens stay as long as their audit rows.
func (m *Module) cleanup(ctx context.Context) error {
	now := m.d.Now()
	if _, err := m.d.DB.Exec(ctx,
		`delete from admin_sessions where last_seen_at < $1 or expires_at <= $2`,
		now.Add(-idleTimeout), now); err != nil {
		return err
	}
	if _, err := m.d.DB.Exec(ctx,
		`delete from admin_tokens where used_at is null and expires_at <= $1`, now); err != nil {
		return err
	}
	_, err := m.d.DB.Exec(ctx, `delete from admin_audit where at < $1`, now.Add(-auditKeep))
	return err
}
