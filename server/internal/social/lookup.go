package social

import (
	"errors"
	"net/http"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	"github.com/velixio/myApp/server/internal/httpx"
)

const maxDisplayName = 64

type userInfo struct {
	AccountID   string `json:"accountID"`
	Username    string `json:"username"`
	DisplayName string `json:"displayName"`
}

func accountKey(r *http.Request) string { return httpx.PrincipalFrom(r.Context()).AccountID }

func (m *Module) lookupRoutes(r *httpx.Router) {
	r.Authed("GET /v1/users/{username}", httpx.Limited(m.lookups, accountKey, m.lookupUser))
	r.Authed("POST /v1/discover/email", httpx.Limited(m.discovers, accountKey, m.discoverEmail))
	r.Authed("PATCH /v1/me/settings", m.patchSettings)
}

func (m *Module) lookupUser(w http.ResponseWriter, r *http.Request) error {
	return m.respond(w, r, "username = $1", r.PathValue("username"))
}

func (m *Module) discoverEmail(w http.ResponseWriter, r *http.Request) error {
	var req struct {
		Email string `json:"email"`
	}
	if err := httpx.Decode(r, &req); err != nil {
		return err
	}
	email := strings.ToLower(strings.TrimSpace(req.Email))
	return m.respond(w, r, "email_lower = $1 and find_by_email", email)
}

// respond runs a one-row lookup. Any miss, for whatever reason, is the same
// not_found so callers cannot tell unknown from opted out (spec section 8).
func (m *Module) respond(w http.ResponseWriter, r *http.Request, where string, arg any) error {
	var u userInfo
	err := m.d.DB.QueryRow(r.Context(),
		`select id, username, display_name from accounts where `+where, arg).
		Scan(&u.AccountID, &u.Username, &u.DisplayName)
	if errors.Is(err, pgx.ErrNoRows) {
		return httpx.Err("not_found", "no such user")
	}
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, u)
	return nil
}

func (m *Module) patchSettings(w http.ResponseWriter, r *http.Request) error {
	var req struct {
		DisplayName *string `json:"displayName"`
		FindByEmail *bool   `json:"findByEmail"`
	}
	if err := httpx.Decode(r, &req); err != nil {
		return err
	}
	if n := req.DisplayName; n != nil {
		if !utf8.ValidString(*n) || utf8.RuneCountInString(*n) > maxDisplayName ||
			strings.IndexFunc(*n, unicode.IsControl) >= 0 {
			return httpx.Err("bad_request", "display name is at most 64 characters with no control characters")
		}
	}
	_, err := m.d.DB.Exec(r.Context(),
		`update accounts set display_name = coalesce($2, display_name),
		        find_by_email = coalesce($3, find_by_email) where id = $1`,
		httpx.PrincipalFrom(r.Context()).AccountID, req.DisplayName, req.FindByEmail)
	if err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
