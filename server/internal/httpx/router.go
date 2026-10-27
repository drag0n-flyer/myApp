package httpx

import (
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/velixio/myapp/server/internal/core"
	"github.com/velixio/myapp/server/internal/ratelimit"
)

// HandlerFunc is an HTTP handler that reports failure by returning an error.
// An *Error is sent to the client as is; any other error becomes a 500
// "internal" whose text is logged but never sent.
type HandlerFunc func(w http.ResponseWriter, r *http.Request) error

// Router maps Go 1.22 ServeMux patterns to HandlerFuncs.
type Router struct {
	mux      *http.ServeMux
	verifier core.SessionVerifier
	log      *slog.Logger
}

// NewRouter returns a Router whose Authed routes check tokens with v.
// A nil log discards logs.
func NewRouter(v core.SessionVerifier, log *slog.Logger) *Router {
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	rt := &Router{mux: http.NewServeMux(), verifier: v, log: log}
	// Anything no pattern matches gets the error envelope, not plain text.
	rt.Public("/", func(http.ResponseWriter, *http.Request) error { return Err("not_found", "no such endpoint") })
	return rt
}

// Public registers a route that needs no session.
func (rt *Router) Public(pattern string, h HandlerFunc) {
	rt.mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
		if err := h(w, r); err != nil {
			rt.fail(w, err)
		}
	})
}

// Authed registers a route that needs a valid session token; the handler can
// read the caller with PrincipalFrom.
func (rt *Router) Authed(pattern string, h HandlerFunc) {
	rt.Public(pattern, authenticate(rt.verifier, h))
}

// ServeHTTP implements http.Handler.
func (rt *Router) ServeHTTP(w http.ResponseWriter, r *http.Request) { rt.mux.ServeHTTP(w, r) }

func (rt *Router) fail(w http.ResponseWriter, err error) {
	var e *Error
	if !errors.As(err, &e) {
		rt.log.Error("request failed", "err", err)
		e = Err("internal", "internal error")
	}
	writeError(w, e)
}

// Limited wraps h with a rate limit. key picks the bucket for a request. A
// denied request gets 429 rate_limited with Retry-After in whole seconds,
// rounded up.
func Limited(l *ratelimit.Limiter, key func(*http.Request) string, h HandlerFunc) HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) error {
		if ok, retry := l.Allow(key(r)); !ok {
			secs := max(1, int((retry+time.Second-1)/time.Second))
			w.Header().Set("Retry-After", strconv.Itoa(secs))
			return Err("rate_limited", "too many requests")
		}
		return h(w, r)
	}
}
