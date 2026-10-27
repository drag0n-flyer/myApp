package admin

import (
	"context"
	"errors"
	"regexp"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/drag0n-flyer/myApp/server/internal/core"
)

// adminLockKey is the advisory lock that serialises everything that decides
// by counting admins: first-visit setup, disabling and removing.
const adminLockKey = 6002001

// Plain errors of the account store. The API turns them into its codes, and
// the CLI prints their text.
var (
	errUserExists = errors.New("that username is already taken")
	errLastAdmin  = errors.New("at least one enabled admin must remain")
	errSelf       = errors.New("you cannot disable or remove yourself; ask another admin or use the command line")
	errSetupDone  = errors.New("the first admin already exists")
)

// inputError is a rejected username or password.
type inputError string

func (e inputError) Error() string { return string(e) }

var adminIDRE = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func lockAdmins(ctx context.Context, tx pgx.Tx) error {
	_, err := tx.Exec(ctx, `select pg_advisory_xact_lock($1)`, adminLockKey)
	return err
}

// checkNewAdmin normalises the username and applies the password rules.
func checkNewAdmin(username, password string) (string, error) {
	u, err := normalizeUsername(username)
	if err != nil {
		return "", inputError(err.Error())
	}
	if err := validatePassword(u, password); err != nil {
		return "", inputError(err.Error())
	}
	return u, nil
}

// insertAdmin adds the row inside tx; a taken username is errUserExists.
func (m *Module) insertAdmin(ctx context.Context, tx pgx.Tx, username, hash string) (string, error) {
	now := m.d.Now()
	var id string
	err := tx.QueryRow(ctx,
		`insert into admin_users (username, password_hash, created_at, password_changed_at)
		 values ($1, $2, $3, $3) returning id::text`, username, hash, now).Scan(&id)
	var pg *pgconn.PgError
	if errors.As(err, &pg) && pg.Code == "23505" {
		return "", errUserExists
	}
	return id, err
}

// createAdmin adds an admin and audits it as by.
func (m *Module) createAdmin(ctx context.Context, username, password string, by Entry) (id string, err error) {
	u, err := checkNewAdmin(username, password)
	if err != nil {
		return "", err
	}
	hash, err := hashPassword(password)
	if err != nil {
		return "", err
	}
	err = pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		var err error
		if id, err = m.insertAdmin(ctx, tx, u, hash); err != nil {
			return err
		}
		by.Action, by.Target, by.Detail = "admin.add", id, map[string]any{"username": u}
		return m.audit(ctx, tx, by)
	})
	return id, err
}

// adminByUsername returns the ID of the admin called username.
func (m *Module) adminByUsername(ctx context.Context, username string) (string, error) {
	u := normalizeLoose(username)
	var id string
	err := m.d.DB.QueryRow(ctx, `select id::text from admin_users where username = $1`, u).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", core.ErrNotFound
	}
	return id, err
}

// setDisabled disables or enables an admin, ending the sessions of a disabled
// one. by.AdminUserID is the admin acting, who may not disable themselves.
// With guardLast, disabling the last enabled admin is errLastAdmin; either
// way wasLast says that it was the last one. changed is false when the admin
// was already in the state asked for. Asking for the state the admin
// is already in changes and audits nothing.
func (m *Module) setDisabled(ctx context.Context, id string, disable bool, by Entry, guardLast bool) (wasLast, changed bool, err error) {
	if !adminIDRE.MatchString(id) {
		return false, false, core.ErrNotFound
	}
	err = pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		if err := lockAdmins(ctx, tx); err != nil {
			return err
		}
		username, disabled, err := m.lockUser(ctx, tx, id)
		if err != nil {
			return err
		}
		if disabled == disable {
			return nil
		}
		changed = true
		now := m.d.Now()
		action := "admin.enable"
		if disable {
			action = "admin.disable"
			if by.AdminUserID == id {
				return errSelf
			}
			var others int
			if err := tx.QueryRow(ctx, `select count(*) from admin_users where disabled_at is null and id <> $1`, id).Scan(&others); err != nil {
				return err
			}
			wasLast = others == 0
			if wasLast && guardLast {
				return errLastAdmin
			}
			if _, err := tx.Exec(ctx, `update admin_users set disabled_at = $2 where id = $1`, id, now); err != nil {
				return err
			}
			if _, err := tx.Exec(ctx, `delete from admin_sessions where admin_user_id = $1`, id); err != nil {
				return err
			}
		} else if _, err := tx.Exec(ctx, `update admin_users set disabled_at = null where id = $1`, id); err != nil {
			return err
		}
		by.Action, by.Target, by.Detail = action, id, map[string]any{"username": username}
		return m.audit(ctx, tx, by)
	})
	return wasLast, changed && err == nil, err
}

// lockUser locks the row and returns its username and whether it is disabled.
func (m *Module) lockUser(ctx context.Context, tx pgx.Tx, id string) (username string, disabled bool, err error) {
	err = tx.QueryRow(ctx, `select username, disabled_at is not null from admin_users where id = $1 for update`, id).
		Scan(&username, &disabled)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, core.ErrNotFound
	}
	return username, disabled, err
}

// deleteAdmin deletes an admin; the database ends their sessions with them.
func (m *Module) deleteAdmin(ctx context.Context, id string, by Entry) error {
	if !adminIDRE.MatchString(id) {
		return core.ErrNotFound
	}
	return pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		if err := lockAdmins(ctx, tx); err != nil {
			return err
		}
		username, _, err := m.lockUser(ctx, tx, id)
		if err != nil {
			return err
		}
		if by.AdminUserID == id {
			return errSelf
		}
		// Whether the target is enabled or not, an enabled admin must remain.
		var others int
		if err := tx.QueryRow(ctx, `select count(*) from admin_users where disabled_at is null and id <> $1`, id).Scan(&others); err != nil {
			return err
		}
		if others == 0 {
			return errLastAdmin
		}
		if _, err := tx.Exec(ctx, `delete from admin_users where id = $1`, id); err != nil {
			return err
		}
		by.Action, by.Target, by.Detail = "admin.remove", id, map[string]any{"username": username}
		return m.audit(ctx, tx, by)
	})
}

// setPassword replaces an admin's password and ends all their sessions. It
// does not enable a disabled admin.
func (m *Module) setPassword(ctx context.Context, id, password string, by Entry) error {
	if !adminIDRE.MatchString(id) {
		return core.ErrNotFound
	}
	var username string
	err := m.d.DB.QueryRow(ctx, `select username from admin_users where id = $1`, id).Scan(&username)
	if errors.Is(err, pgx.ErrNoRows) {
		return core.ErrNotFound
	}
	if err != nil {
		return err
	}
	if err := validatePassword(username, password); err != nil {
		return inputError(err.Error())
	}
	hash, err := hashPassword(password)
	if err != nil {
		return err
	}
	return pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx,
			`update admin_users set password_hash = $2, password_changed_at = $3 where id = $1`, id, hash, m.d.Now())
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return core.ErrNotFound
		}
		if _, err := tx.Exec(ctx, `delete from admin_sessions where admin_user_id = $1`, id); err != nil {
			return err
		}
		by.Action, by.Target, by.Detail = "admin.password", id, map[string]any{"username": username}
		return m.audit(ctx, tx, by)
	})
}

// adminRow is one admin as the API and the CLI list them.
type adminRow struct {
	ID          string     `json:"id"`
	Username    string     `json:"username"`
	Disabled    bool       `json:"disabled"`
	CreatedAt   time.Time  `json:"createdAt"`
	LastLoginAt *time.Time `json:"lastLoginAt"`
}

func (m *Module) loadAdmins(ctx context.Context) ([]adminRow, error) {
	rows, err := m.d.DB.Query(ctx,
		`select id::text, username, disabled_at is not null, created_at, last_login_at
		 from admin_users order by created_at, username`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []adminRow{}
	for rows.Next() {
		var a adminRow
		if err := rows.Scan(&a.ID, &a.Username, &a.Disabled, &a.CreatedAt, &a.LastLoginAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}
