package httpx

import (
	"context"
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
// else, with the verifier. Any failure is the same 401.
func authenticate(v core.SessionVerifier, h HandlerFunc) HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) error {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok || token == "" {
			return Err("unauthorized", "missing or malformed bearer token")
		}
		p, err := v.Verify(r.Context(), token)
		if err != nil {
			return Err("unauthorized", "invalid session")
		}
		ctx := context.WithValue(r.Context(), principalKey{}, p)
		return h(w, r.WithContext(ctx))
	}
}
