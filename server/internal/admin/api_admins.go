package admin

import (
	"errors"
	"net/http"
	"strings"
	"sync/atomic"

	"github.com/jackc/pgx/v5"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

// dummyVerifies counts the verifies spent on unknown usernames (for tests).
var dummyVerifies atomic.Int64

// normalizeLoose lower-cases a username for a lookup, without the rules, so a
// wrong name is just not found.
func normalizeLoose(name string) string { return strings.ToLower(name) }

// adminAPIErr turns the account store's errors into the API's.
func adminAPIErr(err error) error {
	var in inputError
	switch {
	case err == nil:
		return nil
	case errors.As(err, &in):
		return httpx.Err("bad_request", in.Error())
	case errors.Is(err, core.ErrNotFound):
		return httpx.Err("not_found", "no such admin")
	case errors.Is(err, errUserExists), errors.Is(err, errLastAdmin), errors.Is(err, errSelf), errors.Is(err, errSetupDone):
		return httpx.Err("conflict", err.Error())
	}
	return err
}

type credentials struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

// setupNeeded answers GET /admin/api/setup: is there no admin account yet?
func (m *Module) setupNeeded(w http.ResponseWriter, r *http.Request, _ Session) error {
	var exists bool
	if err := m.d.DB.QueryRow(r.Context(), `select exists (select 1 from admin_users)`).Scan(&exists); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]bool{"needed": !exists})
	return nil
}

// setup answers POST /admin/api/setup: it creates the first admin and signs
// them in. It works only while there is no admin; a second request, even a
// racing one, is 409 conflict.
func (m *Module) setup(w http.ResponseWriter, r *http.Request, _ Session) error {
	if err := m.verifyCSRF(r, false); err != nil {
		return err
	}
	// Each attempt on an empty install costs one argon2 hash.
	if ok, wait := m.limits().setup.Allow("all"); !ok {
		retryAfter(w, wait)
		return httpx.Err("rate_limited", "too many attempts")
	}
	var in credentials
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	ctx := r.Context()
	// A cheap look first, so a finished install does not spend a hash.
	var exists bool
	if err := m.d.DB.QueryRow(ctx, `select exists (select 1 from admin_users)`).Scan(&exists); err != nil {
		return err
	}
	if exists {
		return adminAPIErr(errSetupDone)
	}
	username, err := checkNewAdmin(in.Username, in.Password)
	if err != nil {
		return adminAPIErr(err)
	}
	hash, err := hashPassword(in.Password)
	if err != nil {
		return err
	}
	var sid string
	err = pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		if err := lockAdmins(ctx, tx); err != nil {
			return err
		}
		if err := tx.QueryRow(ctx, `select exists (select 1 from admin_users)`).Scan(&exists); err != nil {
			return err
		}
		if exists {
			return errSetupDone
		}
		id, err := m.insertAdmin(ctx, tx, username, hash)
		if err != nil {
			return err
		}
		if sid, err = m.newSession(ctx, tx, r, "", id); err != nil {
			return err
		}
		return m.audit(ctx, tx, Entry{Actor: "dashboard", AdminUserID: id, Action: "admin.setup", Target: id,
			Detail: map[string]any{"username": username}})
	})
	if err != nil {
		return adminAPIErr(err)
	}
	m.setCookie(w, r, sid, 0)
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (m *Module) listAdmins(w http.ResponseWriter, r *http.Request, _ Session) error {
	rows, err := m.loadAdmins(r.Context())
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"admins": rows})
	return nil
}

func (m *Module) addAdmin(w http.ResponseWriter, r *http.Request, s Session) error {
	var in credentials
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	id, err := m.createAdmin(r.Context(), in.Username, in.Password, s.entry("", "", nil))
	if err != nil {
		return adminAPIErr(err)
	}
	rows, err := m.loadAdmins(r.Context())
	if err != nil {
		return err
	}
	for _, a := range rows {
		if a.ID == id {
			httpx.JSON(w, http.StatusOK, a)
			return nil
		}
	}
	return httpx.Err("internal", "internal error")
}

func (m *Module) disableAdmin(w http.ResponseWriter, r *http.Request, s Session) error {
	if _, _, err := m.setDisabled(r.Context(), r.PathValue("id"), true, s.entry("", "", nil), true); err != nil {
		return adminAPIErr(err)
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (m *Module) enableAdmin(w http.ResponseWriter, r *http.Request, s Session) error {
	if _, _, err := m.setDisabled(r.Context(), r.PathValue("id"), false, s.entry("", "", nil), true); err != nil {
		return adminAPIErr(err)
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// changeAdminPassword sets a new password for an admin, who may be the caller.
// All of that admin's sessions end, the caller's own included.
func (m *Module) changeAdminPassword(w http.ResponseWriter, r *http.Request, s Session) error {
	var in struct {
		Password string `json:"password"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	id := r.PathValue("id")
	if err := m.setPassword(r.Context(), id, in.Password, s.entry("", "", nil)); err != nil {
		return adminAPIErr(err)
	}
	if id == s.AdminUserID {
		m.setCookie(w, r, "", -1)
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (m *Module) removeAdmin(w http.ResponseWriter, r *http.Request, s Session) error {
	if err := m.deleteAdmin(r.Context(), r.PathValue("id"), s.entry("", "", nil)); err != nil {
		return adminAPIErr(err)
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
