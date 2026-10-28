package admin

import (
	"errors"
	"fmt"
	"log/slog"
	"net/http"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

// Handler is the full chain: secureHeaders (so a 421 has them too), hostGuard,
// sameOriginAPI, then static files or the API (requireSession, checkCSRF, handler). POST /admin/api/login skips
// the session and CSRF checks but not the header checks.
func (m *Module) Handler() http.Handler {
	mux := m.mux(func(h apiFunc, public bool) http.Handler {
		if public {
			return serve(h, Session{}, m.d.Log)
		}
		return m.requireSession(m.checkCSRF(h))
	})
	return m.secureHeaders(m.hostGuard(sameOriginAPI(mux)))
}

// testHandler serves the same routes, running every request as s with no
// host, session or CSRF checks.
func (m *Module) testHandler(s Session) http.Handler {
	return m.mux(func(h apiFunc, _ bool) http.Handler { return serve(h, s, m.d.Log) })
}

// serve runs h as s and answers an error with the server's envelope. An error
// that is not an API error is a server fault: it is logged by route and type,
// never with its text.
func serve(h apiFunc, s Session, log *slog.Logger) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := h(w, r, s); err != nil {
			var e *httpx.Error
			if !errors.As(err, &e) && log != nil {
				log.Error("admin request failed", "path", r.URL.Path, "errType", fmt.Sprintf("%T", err))
			}
			httpx.WriteError(w, err)
		}
	})
}

// mux registers every path of the design's section 7 and the static files.
// wrap builds the handler for one API route; public routes need no session.
func (m *Module) mux(wrap func(h apiFunc, public bool) http.Handler) *http.ServeMux {
	mux := http.NewServeMux()
	api := func(pattern string, h apiFunc) { mux.Handle(pattern, wrap(h, false)) }

	mux.Handle("POST /admin/api/login", wrap(m.login, true))
	mux.Handle("POST /admin/api/login/code", wrap(m.loginCode, true))
	mux.Handle("GET /admin/api/setup", wrap(m.setupNeeded, true))
	mux.Handle("POST /admin/api/setup", wrap(m.setup, true))
	api("POST /admin/api/logout", m.logout)
	api("POST /admin/api/me/totp/start", m.totpStart)
	api("GET /admin/api/me/totp/qr", m.totpQR)
	api("POST /admin/api/me/totp/enable", m.totpEnable)
	api("POST /admin/api/me/totp/disable", m.totpDisable)
	api("GET /admin/api/session", m.session)
	api("GET /admin/api/overview", m.overview)
	api("GET /admin/api/invites", m.listInvites)
	api("POST /admin/api/invites", m.createInvite)
	api("POST /admin/api/invites/{id}/revoke", m.revokeInvite)
	api("GET /admin/api/accounts", m.listAccounts)
	api("GET /admin/api/accounts/{id}", m.getAccount)
	api("POST /admin/api/accounts/{id}/devices/{deviceID}/revoke", m.revokeDevice)
	api("POST /admin/api/accounts/{id}/delete", m.deleteAccount)
	api("GET /admin/api/reports", m.listReports)
	api("GET /admin/api/reports/{id}", m.getReport)
	api("POST /admin/api/reports/{id}/handle", m.handleReport)
	api("GET /admin/api/server", m.serverInfo)
	api("GET /admin/api/audit", m.listAudit)
	api("GET /admin/api/admins", m.listAdmins)
	api("POST /admin/api/admins", m.addAdmin)
	api("POST /admin/api/admins/{id}/disable", m.disableAdmin)
	api("POST /admin/api/admins/{id}/enable", m.enableAdmin)
	api("POST /admin/api/admins/{id}/password", m.changeAdminPassword)
	api("DELETE /admin/api/admins/{id}", m.removeAdmin)

	m.static(mux)
	return mux
}
