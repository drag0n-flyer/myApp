// Package identity is lane A: accounts, invites, email codes, passkeys,
// sessions, devices, linking and deletion. A1 covers usernames and invites;
// the rest is still a stub.
package identity

import (
	"context"
	"net/http"
	"time"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
	"github.com/velixio/myApp/server/internal/ratelimit"
)

var _ core.SessionVerifier = (*Module)(nil)

// Module is the identity module.
type Module struct {
	d            core.Deps
	usernameRate *ratelimit.Limiter // 30/min per IP
	inviteRate   *ratelimit.Limiter // 10/hour per IP
	signupRate   *ratelimit.Limiter // sign-up start: 5/hour per IP
	emailRate    *ratelimit.Limiter // email code send: 3/hour per email
	emailIPRate  *ratelimit.Limiter // email code send: 10/hour per IP
}

// New returns the identity module.
func New(d core.Deps) *Module {
	return &Module{
		d:            d,
		usernameRate: ratelimit.New(30, time.Minute, d.Now),
		inviteRate:   ratelimit.New(10, time.Hour, d.Now),
		signupRate:   ratelimit.New(5, time.Hour, d.Now),
		emailRate:    ratelimit.New(3, time.Hour, d.Now),
		emailIPRate:  ratelimit.New(10, time.Hour, d.Now),
	}
}

// Verify is the session verifier. The stub rejects every token.
func (m *Module) Verify(ctx context.Context, token string) (core.Principal, error) {
	return core.Principal{}, core.ErrNotFound
}

func (m *Module) ip(r *http.Request) string { return httpx.ClientIP(r, m.d.Config.TrustProxy) }

// Routes registers the identity endpoints. The authed GET /v1/me is still a
// stub so that the wiring (and the verifier) can be exercised.
func (m *Module) Routes(r *httpx.Router) {
	r.Public("GET /v1/usernames/{name}", httpx.Limited(m.usernameRate, m.ip, m.usernameAvailable))
	r.Public("POST /v1/invites/check", httpx.Limited(m.inviteRate, m.ip, m.checkInvite))
	r.Public("POST /v1/signup/start", httpx.Limited(m.signupRate, m.ip, m.signupStart))
	r.Public("POST /v1/signup/verify", m.signupVerify)
	r.Authed("GET /v1/me", func(http.ResponseWriter, *http.Request) error {
		return httpx.Err("not_found", "not implemented")
	})
}

// Commands returns the admin CLI subcommands, keyed like "invite create".
func (m *Module) Commands() map[string]core.Command {
	return map[string]core.Command{"invite create": m.inviteCreateCommand}
}

func (m *Module) usernameAvailable(w http.ResponseWriter, r *http.Request) error {
	name := r.PathValue("name")
	if !ValidUsername(name) {
		return httpx.Err("invalid_username", "usernames are 3 to 32 characters: a-z, 0-9 and _, starting with a letter")
	}
	available := !Reserved(name)
	if available {
		var taken bool
		if err := m.d.DB.QueryRow(r.Context(),
			`select exists (select 1 from accounts where username = $1)`, name).Scan(&taken); err != nil {
			return err
		}
		available = !taken
	}
	httpx.JSON(w, http.StatusOK, map[string]bool{"available": available})
	return nil
}
