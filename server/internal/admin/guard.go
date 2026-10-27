package admin

import (
	"crypto/subtle"
	"mime"
	"net"
	"net/http"
	"strings"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

// hostName is a Host header value without its port, lower-cased, and without
// the brackets of an IPv6 literal. ok is false for a malformed value.
func hostName(host string) (name string, ok bool) {
	host = strings.ToLower(host)
	if h, port, err := net.SplitHostPort(host); err == nil {
		if port == "" || strings.Trim(port, "0123456789") != "" {
			return "", false
		}
		host = h
	} else if strings.Contains(host, "]") != strings.Contains(host, "[") || strings.Count(host, ":") > 0 && !strings.HasPrefix(host, "[") {
		return "", false
	}
	host = strings.TrimSuffix(strings.TrimPrefix(host, "["), "]")
	return host, host != ""
}

func loopbackName(name string) bool {
	return name == "localhost" || name == "127.0.0.1" || name == "::1"
}

// hostGuard answers 421 with no body for a Host that is not loopback or in
// AdminHosts. It stops DNS rebinding: a page on another name that resolves to
// the loopback address still sends its own name as Host.
func (m *Module) hostGuard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if name, ok := hostName(r.Host); ok && m.hostAllowed(name) {
			next.ServeHTTP(w, r)
			return
		}
		w.WriteHeader(http.StatusMisdirectedRequest)
	})
}

func (m *Module) hostAllowed(name string) bool {
	if loopbackName(name) {
		return true
	}
	for _, h := range m.d.Config.AdminHosts {
		if allowed, ok := hostName(h); ok && allowed == name {
			return true
		}
	}
	return false
}

const csp = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'; object-src 'none'"

// secureHeaders sets the dashboard's security headers on every response.
// API answers are never cached; static files are revalidated on each load.
func secureHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", csp)
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cross-Origin-Opener-Policy", "same-origin")
		h.Set("Cross-Origin-Resource-Policy", "same-origin")
		h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		if strings.HasPrefix(r.URL.Path, "/admin/api/") {
			h.Set("Cache-Control", "no-store")
		} else {
			h.Set("Cache-Control", "no-cache")
		}
		next.ServeHTTP(w, r)
	})
}

// sameOriginAPI answers 403 to an API request whose Sec-Fetch-Site header is
// present and is neither same-origin nor none, so a page on another localhost
// port cannot make signed-in GETs. A missing header (CLI tools, old browsers)
// is allowed.
func sameOriginAPI(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/admin/api/") {
			if site := r.Header.Get("Sec-Fetch-Site"); site != "" && site != "same-origin" && site != "none" {
				httpx.WriteError(w, forbidden())
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

// secureHeaders is the method form the skeleton's routes use.
func (m *Module) secureHeaders(next http.Handler) http.Handler { return secureHeaders(next) }

func forbidden() error { return httpx.Err("forbidden", "request not allowed") }

// verifyCSRF checks a state-changing request (any method but GET and HEAD):
// JSON content type, a same-origin Sec-Fetch-Site and Origin when present,
// and, when needToken, the session's X-CSRF-Token. Any failure is 403.
func (m *Module) verifyCSRF(r *http.Request, needToken bool) error {
	if r.Method == http.MethodGet || r.Method == http.MethodHead {
		return nil
	}
	if mt, _, err := mime.ParseMediaType(r.Header.Get("Content-Type")); err != nil || mt != "application/json" {
		return forbidden()
	}
	if site := r.Header.Get("Sec-Fetch-Site"); site != "" && site != "same-origin" {
		return forbidden()
	}
	if origin := r.Header.Get("Origin"); origin != "" &&
		origin != "http://"+r.Host && origin != "https://"+r.Host {
		return forbidden()
	}
	if needToken {
		st := stateFrom(r.Context())
		if st == nil || subtle.ConstantTimeCompare(hashString(r.Header.Get("X-CSRF-Token")), st.csrfHash) != 1 {
			return forbidden()
		}
	}
	return nil
}

// checkCSRF rejects a state-changing request that fails verifyCSRF.
func (m *Module) checkCSRF(h apiFunc) apiFunc {
	return func(w http.ResponseWriter, r *http.Request, s Session) error {
		if err := m.verifyCSRF(r, true); err != nil {
			return err
		}
		return h(w, r, s)
	}
}
