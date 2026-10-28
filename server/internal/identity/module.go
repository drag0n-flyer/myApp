// Package identity is lane A: accounts, invites, email codes, passkeys,
// sessions, devices, linking and deletion. A1 to A3 cover usernames,
// invites, sign-up, passkeys and sessions.
package identity

import (
	"net/http"
	"sync"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/drag0n-flyer/myApp/server/internal/ratelimit"
)

var _ core.SessionVerifier = (*Module)(nil)

// Module is the identity module.
type Module struct {
	d            core.Deps
	wa           *relyingParty // nil when the config is unusable; waErr says why
	waErr        error
	bg           sync.WaitGroup     // detached work, see emailSignInStart
	linkRate     *ratelimit.Limiter // link start: 10/hour per IP
	linkClaim    *ratelimit.Limiter // link claim: 60/min per link
	optionsRate  *ratelimit.Limiter // passkey options: 30/min per IP
	usernameRate *ratelimit.Limiter // 30/min per IP
	inviteRate   *ratelimit.Limiter // 10/hour per IP
	signupRate   *ratelimit.Limiter // sign-up start: 5/hour per IP
	emailRate    *ratelimit.Limiter // email code send: 3/hour per email
	emailIPRate  *ratelimit.Limiter // email code send: 10/hour per IP
	emailTryRate *ratelimit.Limiter // email sign-in code tries: 5 per 10 minutes per email
}

// New returns the identity module.
func New(d core.Deps) *Module {
	wa, waErr := newRelyingParty(d.Config)
	return &Module{
		d:            d,
		wa:           wa,
		waErr:        waErr,
		linkRate:     ratelimit.New(10, time.Hour, d.Now),
		linkClaim:    ratelimit.New(60, time.Minute, d.Now),
		optionsRate:  ratelimit.New(30, time.Minute, d.Now),
		usernameRate: ratelimit.New(30, time.Minute, d.Now),
		inviteRate:   ratelimit.New(10, time.Hour, d.Now),
		signupRate:   ratelimit.New(5, time.Hour, d.Now),
		emailRate:    ratelimit.New(3, time.Hour, d.Now),
		emailIPRate:  ratelimit.New(10, time.Hour, d.Now),
		emailTryRate: ratelimit.New(5, 10*time.Minute, d.Now),
	}
}

func (m *Module) ip(r *http.Request) string { return httpx.ClientIP(r, m.d.Config.TrustProxy) }

// Routes registers the identity endpoints.
func (m *Module) Routes(r *httpx.Router) {
	r.Public("GET /v1/usernames/{name}", httpx.Limited(m.usernameRate, m.ip, m.usernameAvailable))
	r.Public("POST /v1/invites/check", httpx.Limited(m.inviteRate, m.ip, m.checkInvite))
	r.Public("POST /v1/signup/start", httpx.Limited(m.signupRate, m.ip, m.signupStart))
	r.Public("POST /v1/signup/verify", m.signupVerify)
	r.Public("POST /v1/signup/passkey/options", httpx.Limited(m.optionsRate, m.ip, m.signupPasskeyOptions))
	r.Public("POST /v1/signup/finish", m.signupFinish)
	r.Public("POST /v1/signin/passkey/options", httpx.Limited(m.optionsRate, m.ip, m.signinOptions))
	r.Public("POST /v1/signin/passkey/finish", m.signinFinish)
	r.Authed("POST /v1/me/passkeys/options", httpx.Limited(m.optionsRate, m.ip, m.mePasskeyOptions))
	r.Authed("POST /v1/me/passkeys", m.mePasskeyAdd)
	r.Public("POST /v1/signin/email/start", m.emailSignInStart)
	r.Public("POST /v1/signin/email/finish", m.emailSignInFinish)
	r.Public("POST /v1/link", httpx.Limited(m.linkRate, m.ip, m.linkStart))
	r.Authed("POST /v1/link/{id}/info", m.linkInfo)
	r.Authed("POST /v1/link/{id}/approve", m.linkApprove)
	r.Public("POST /v1/link/{id}/claim", httpx.Limited(m.linkClaim, func(r *http.Request) string { return r.PathValue("id") }, m.linkClaimHandler))
	r.Authed("GET /v1/me", m.me)
	r.Authed("DELETE /v1/me", m.deleteAccountHandler)
	r.Authed("GET /v1/devices", m.listDevices)
	r.Authed("DELETE /v1/devices/{id}", m.revokeDeviceHandler)
}

// Commands returns the admin CLI subcommands, keyed like "invite create".
func (m *Module) Commands() map[string]core.Command {
	return map[string]core.Command{
		"invite create": m.inviteCreateCommand,
		"lab session":   m.labSessionCommand,
	}
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

// WaitBackground waits for detached work started by handlers, such as the
// email sign-in code send. App calls it at shutdown, with a timeout.
func (m *Module) WaitBackground() { m.bg.Wait() }
