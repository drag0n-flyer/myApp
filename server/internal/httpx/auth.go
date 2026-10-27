package httpx

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/velixio/myApp/server/internal/core"
)

type principalKey struct{}

// PrincipalFrom returns the caller set by the Authed middleware. On a route
// registered with Public it is the zero value.
func PrincipalFrom(ctx context.Context) core.Principal {
	p, _ := ctx.Value(principalKey{}).(core.Principal)
	return p
}

// authenticate checks the "Authorization: Bearer <token>" header, and nothing
// else, with the verifier. A missing, malformed, unknown or revoked token is
// the same 401. Any other verifier error (a database outage, say) is returned
// as is and becomes a retryable 500, because clients treat 401 as signed out.
func authenticate(v core.SessionVerifier, h HandlerFunc) HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) error {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok || token == "" {
			return Err("unauthorized", "missing or malformed bearer token")
		}
		p, err := v.Verify(r.Context(), token)
		if errors.Is(err, core.ErrNotFound) {
			return Err("unauthorized", "invalid session")
		}
		if err != nil {
			return err
		}
		ctx := context.WithValue(r.Context(), principalKey{}, p)
		return h(w, r.WithContext(ctx))
	}
}
