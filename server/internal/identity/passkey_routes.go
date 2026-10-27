package identity

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

// uuidBytes returns the 16 bytes of a UUID string, the WebAuthn user handle.
func uuidBytes(s string) []byte {
	b, _ := hex.DecodeString(strings.ReplaceAll(s, "-", ""))
	return b
}

// uuidString is the inverse of uuidBytes; it reports false unless b is 16 bytes.
func uuidString(b []byte) (string, bool) {
	if len(b) != 16 {
		return "", false
	}
	h := hex.EncodeToString(b)
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:], true
}

func (m *Module) webAuthnReady() error {
	if m.wa == nil {
		return m.waErr // reported to the client as a plain 500
	}
	return nil
}

func hasCredential(raw json.RawMessage) bool {
	return len(raw) > 0 && string(raw) != "null"
}

func isUnique(err error, constraint string) bool {
	var pg *pgconn.PgError
	return errors.As(err, &pg) && pg.Code == "23505" && pg.ConstraintName == constraint
}

// loadPasskeys returns the stored credentials of an account.
func (m *Module) loadPasskeys(ctx context.Context, accountID string) ([]passkeyRow, error) {
	rows, err := m.d.DB.Query(ctx,
		`select credential_id, public_key, sign_count, coalesce(aaguid, ''::bytea), flags from passkeys where account_id = $1`, accountID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []passkeyRow
	for rows.Next() {
		var p passkeyRow
		var sc int64
		var fl int16
		if err := rows.Scan(&p.CredentialID, &p.PublicKey, &sc, &p.AAGUID, &fl); err != nil {
			return nil, err
		}
		p.SignCount, p.Flags = uint32(sc), byte(fl)
		out = append(out, p)
	}
	return out, rows.Err()
}

func (m *Module) insertPasskey(ctx context.Context, tx pgx.Tx, accountID string, p passkeyRow) error {
	_, err := tx.Exec(ctx,
		`insert into passkeys (credential_id, account_id, public_key, sign_count, aaguid, flags, created_at)
		 values ($1, $2, $3, $4, $5, $6, $7)`,
		p.CredentialID, accountID, p.PublicKey, int64(p.SignCount), p.AAGUID, int16(p.Flags), m.d.Now())
	return err
}

// signupRow is the part of a signups row the passkey steps need.
type signupRow struct {
	accountID, username, emailLower string
	invite                          *string
	verified                        bool
	expires                         time.Time
	session                         []byte
}

// loadSignup reads a sign-up that is verified and not expired. An unknown ID
// is not_found, an unverified one forbidden, an expired one expired.
func (m *Module) loadSignup(ctx context.Context, id string) (signupRow, error) {
	var s signupRow
	if !uuidRE.MatchString(id) {
		return s, httpx.Err("not_found", "no such sign-up")
	}
	err := m.d.DB.QueryRow(ctx,
		`select account_id, username, email_lower, invite_code, verified, expires_at, webauthn_session
		 from signups where id = $1`, id).
		Scan(&s.accountID, &s.username, &s.emailLower, &s.invite, &s.verified, &s.expires, &s.session)
	if errors.Is(err, pgx.ErrNoRows) {
		return s, httpx.Err("not_found", "no such sign-up")
	}
	if err != nil {
		return s, err
	}
	if !s.expires.After(m.d.Now()) {
		return s, httpx.Err("expired", "this sign-up has expired; start again")
	}
	if !s.verified {
		return s, httpx.Err("forbidden", "verify your email first")
	}
	return s, nil
}

// signupPasskeyOptions answers POST /v1/signup/passkey/options.
func (m *Module) signupPasskeyOptions(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		SignupID string `json:"signupID"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if err := m.webAuthnReady(); err != nil {
		return err
	}
	s, err := m.loadSignup(r.Context(), in.SignupID)
	if err != nil {
		return err
	}
	opts, session, err := beginRegistration(m.wa, &waUser{id: uuidBytes(s.accountID), name: s.username})
	if err != nil {
		return err
	}
	if _, err := m.d.DB.Exec(r.Context(), `update signups set webauthn_session = $2 where id = $1`, in.SignupID, session); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, json.RawMessage(opts))
	return nil
}

// signupFinish answers POST /v1/signup/finish. One transaction uses up the
// invite, creates the account, stores the passkey and issues the session.
func (m *Module) signupFinish(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		SignupID   string          `json:"signupID"`
		Credential json.RawMessage `json:"credential"`
		DeviceName string          `json:"deviceName"`
		Platform   string          `json:"platform"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if err := validDevice(in.DeviceName, in.Platform); err != nil {
		return err
	}
	ctx := r.Context()
	s, err := m.loadSignup(ctx, in.SignupID)
	if err != nil {
		return err
	}
	var pk *passkeyRow
	if hasCredential(in.Credential) {
		if err := m.webAuthnReady(); err != nil {
			return err
		}
		if len(s.session) == 0 {
			return httpx.Err("bad_request", "ask for passkey options first")
		}
		row, err := finishRegistration(m.wa, &waUser{id: uuidBytes(s.accountID), name: s.username}, s.session, in.Credential)
		if err != nil {
			if errors.Is(err, errPasskey) {
				return httpx.Err("bad_request", "that passkey could not be verified")
			}
			return err
		}
		pk = &row
	}

	tx, err := m.d.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit

	// Deleting the sign-up first makes finish one-shot and serialises two
	// finishes of the same sign-up.
	tag, err := tx.Exec(ctx, `delete from signups where id = $1 and verified and expires_at > $2`, in.SignupID, m.d.Now())
	if err != nil {
		return err
	}
	if tag.RowsAffected() != 1 {
		return httpx.Err("not_found", "no such sign-up")
	}
	if s.invite != nil {
		tag, err := tx.Exec(ctx,
			`update invites set uses_left = uses_left - 1 where code = $1 and uses_left > 0 and expires_at > $2`,
			*s.invite, m.d.Now())
		if err != nil {
			return err
		}
		if tag.RowsAffected() != 1 {
			return httpx.Err("invite_invalid", "that invite is not valid")
		}
	}
	// The email is stored normalised, whatever the user typed.
	if _, err := tx.Exec(ctx,
		`insert into accounts (id, username, email, email_lower, created_at) values ($1, $2, $3, $3, $4)`,
		s.accountID, s.username, s.emailLower, m.d.Now()); err != nil {
		switch {
		case isUnique(err, "accounts_username_key"):
			return httpx.Err("username_taken", "that username is taken")
		case isUnique(err, "accounts_email_lower_key"):
			return httpx.Err("invalid_email", "that email address is already registered")
		}
		return err
	}
	if pk != nil {
		if err := m.insertPasskey(ctx, tx, s.accountID, *pk); err != nil {
			if isUnique(err, "passkeys_pkey") {
				return httpx.Err("bad_request", "that passkey is already registered")
			}
			return err
		}
	}
	deviceID, token, err := m.issueSession(ctx, tx, s.accountID, in.DeviceName, in.Platform)
	if err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"accountID": s.accountID, "deviceID": deviceID, "sessionToken": token})
	return nil
}

// takeChallenge removes and returns an unexpired challenge session, so a
// challenge works once. It reports false for an unknown or expired one.
func (m *Module) takeChallenge(ctx context.Context, id string) ([]byte, bool, error) {
	if !uuidRE.MatchString(id) {
		return nil, false, nil
	}
	var session []byte
	var expires time.Time
	err := m.d.DB.QueryRow(ctx, `delete from webauthn_challenges where id = $1 returning session, expires_at`, id).Scan(&session, &expires)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	return session, expires.After(m.d.Now()), nil
}

func (m *Module) storeChallenge(ctx context.Context, id string, session []byte) error {
	now := m.d.Now()
	// Expired challenges are dropped as new ones arrive.
	if _, err := m.d.DB.Exec(ctx, `delete from webauthn_challenges where expires_at <= $1`, now); err != nil {
		return err
	}
	_, err := m.d.DB.Exec(ctx,
		`insert into webauthn_challenges (id, session, expires_at) values ($1, $2, $3)
		 on conflict (id) do update set session = excluded.session, expires_at = excluded.expires_at`,
		id, session, now.Add(challengeTTL))
	return err
}

// signinOptions answers POST /v1/signin/passkey/options.
func (m *Module) signinOptions(w http.ResponseWriter, r *http.Request) error {
	if err := m.webAuthnReady(); err != nil {
		return err
	}
	opts, session, err := beginLogin(m.wa)
	if err != nil {
		return err
	}
	id := core.NewID()
	if err := m.storeChallenge(r.Context(), id, session); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"challengeID": id, "options": json.RawMessage(opts)})
	return nil
}

// signinFinish answers POST /v1/signin/passkey/finish. Every way for the
// assertion to be wrong gives the same 401.
func (m *Module) signinFinish(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		ChallengeID string          `json:"challengeID"`
		Credential  json.RawMessage `json:"credential"`
		DeviceName  string          `json:"deviceName"`
		Platform    string          `json:"platform"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if err := validDevice(in.DeviceName, in.Platform); err != nil {
		return err
	}
	if err := m.webAuthnReady(); err != nil {
		return err
	}
	ctx := r.Context()
	fail := httpx.Err("unauthorized", "sign-in failed")
	session, ok, err := m.takeChallenge(ctx, in.ChallengeID)
	if err != nil {
		return err
	}
	if !ok || !hasCredential(in.Credential) {
		return fail
	}
	var dbErr error // a failed lookup is a server error, not a bad passkey
	handle, row, err := finishLogin(m.wa, func(handle []byte) (*waUser, error) {
		accountID, ok := uuidString(handle)
		if !ok {
			return nil, errPasskey
		}
		var name string
		err := m.d.DB.QueryRow(ctx, `select username from accounts where id = $1`, accountID).Scan(&name)
		if err != nil {
			if !errors.Is(err, pgx.ErrNoRows) {
				dbErr = err
			}
			return nil, errPasskey
		}
		keys, err := m.loadPasskeys(ctx, accountID)
		if err != nil {
			dbErr = err
			return nil, errPasskey
		}
		u := &waUser{id: handle, name: name}
		for _, k := range keys {
			u.creds = append(u.creds, k.credential())
		}
		return u, nil
	}, session, in.Credential)
	if dbErr != nil {
		return dbErr
	}
	if err != nil {
		if errors.Is(err, errPasskey) {
			return fail
		}
		return err
	}
	accountID, _ := uuidString(handle)

	tx, err := m.d.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit
	// The counter never goes backwards, and the passkey must belong to the
	// account the assertion named. If the passkey vanished meanwhile, no session.
	tag, err := tx.Exec(ctx,
		`update passkeys set sign_count = greatest(sign_count, $2), flags = $3, last_used_at = $4
		 where credential_id = $1 and account_id = $5`,
		row.CredentialID, int64(row.SignCount), int16(row.Flags), m.d.Now(), accountID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return fail
	}
	deviceID, token, err := m.issueSession(ctx, tx, accountID, in.DeviceName, in.Platform)
	if err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"accountID": accountID, "deviceID": deviceID, "sessionToken": token})
	return nil
}

// meUser loads the caller as a WebAuthn user with their passkeys.
func (m *Module) meUser(ctx context.Context, accountID string) (*waUser, error) {
	var name string
	err := m.d.DB.QueryRow(ctx, `select username from accounts where id = $1`, accountID).Scan(&name)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, httpx.Err("not_found", "no such account")
	}
	if err != nil {
		return nil, err
	}
	keys, err := m.loadPasskeys(ctx, accountID)
	if err != nil {
		return nil, err
	}
	u := &waUser{id: uuidBytes(accountID), name: name}
	for _, k := range keys {
		u.creds = append(u.creds, k.credential())
	}
	return u, nil
}

// mePasskeyOptions answers POST /v1/me/passkeys/options. The pending session
// is kept under the account's own ID, so there is one per account and the
// client does not need to send a challenge ID back.
func (m *Module) mePasskeyOptions(w http.ResponseWriter, r *http.Request) error {
	if err := m.webAuthnReady(); err != nil {
		return err
	}
	p := httpx.PrincipalFrom(r.Context())
	u, err := m.meUser(r.Context(), p.AccountID)
	if err != nil {
		return err
	}
	opts, session, err := beginRegistration(m.wa, u)
	if err != nil {
		return err
	}
	if err := m.storeChallenge(r.Context(), p.AccountID, session); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, json.RawMessage(opts))
	return nil
}

// mePasskeyAdd answers POST /v1/me/passkeys.
func (m *Module) mePasskeyAdd(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Credential json.RawMessage `json:"credential"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if err := m.webAuthnReady(); err != nil {
		return err
	}
	ctx := r.Context()
	p := httpx.PrincipalFrom(ctx)
	session, ok, err := m.takeChallenge(ctx, p.AccountID)
	if err != nil {
		return err
	}
	if !ok || !hasCredential(in.Credential) {
		return httpx.Err("bad_request", "ask for passkey options first")
	}
	u, err := m.meUser(ctx, p.AccountID)
	if err != nil {
		return err
	}
	row, err := finishRegistration(m.wa, u, session, in.Credential)
	if err != nil {
		if errors.Is(err, errPasskey) {
			return httpx.Err("bad_request", "that passkey could not be verified")
		}
		return err
	}
	tx, err := m.d.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit
	if err := m.insertPasskey(ctx, tx, p.AccountID, row); err != nil {
		if isUnique(err, "passkeys_pkey") {
			return httpx.Err("bad_request", "that passkey is already registered")
		}
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, struct{}{})
	return nil
}
