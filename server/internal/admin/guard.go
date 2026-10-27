package admin

import "net/http"

// Lane A (A3): these pass through until the guards are built.

// hostGuard answers 421 for a Host that is not loopback or in AdminHosts.
func (m *Module) hostGuard(next http.Handler) http.Handler { return next }

// secureHeaders sets the dashboard's security headers.
func (m *Module) secureHeaders(next http.Handler) http.Handler { return next }

// requireSession runs h as the signed-in token, or answers 401.
func (m *Module) requireSession(h apiFunc) http.Handler { return serve(h, Session{}) }

// checkCSRF rejects a state-changing request without the session's token.
func (m *Module) checkCSRF(h apiFunc) apiFunc { return h }
