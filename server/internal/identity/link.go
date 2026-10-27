package identity

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

const (
	linkTTL       = 5 * time.Minute
	maxSealedSize = 64 << 10 // decoded bytes
)

// randomToken returns 16 random bytes, base64url, and their SHA-256.
func randomToken() (token string, hash []byte, err error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", nil, err
	}
	token = base64.RawURLEncoding.EncodeToString(b[:])
	return token, hashToken(token), nil
}

func sameHash(a, b []byte) bool { return subtle.ConstantTimeCompare(a, b) == 1 }

// linkStart answers POST /v1/link. The new device calls it before it has any
// session. Secret and claim token are stored only as hashes.
func (m *Module) linkStart(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		DeviceName string `json:"deviceName"`
		Platform   string `json:"platform"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if err := validDevice(in.DeviceName, in.Platform); err != nil {
		return err
	}
	secret, secretHash, err := randomToken()
	if err != nil {
		return err
	}
	claim, claimHash, err := randomToken()
	if err != nil {
		return err
	}
	id := core.NewID()
	now := m.d.Now()
	expires := now.Add(linkTTL)
	// Expired requests are dropped as new ones arrive.
	if _, err := m.d.DB.Exec(r.Context(), `delete from link_requests where expires_at <= $1`, now); err != nil {
		return err
	}
	if _, err := m.d.DB.Exec(r.Context(),
		`insert into link_requests (id, secret_hash, claim_hash, device_name, platform, expires_at)
		 values ($1, $2, $3, $4, $5, $6)`,
		id, secretHash, claimHash, in.DeviceName, in.Platform, expires); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"linkID": id, "secret": secret, "claimToken": claim, "expiresAt": expires})
	return nil
}

// linkRow is a link request as read for info and approve.
type linkRow struct {
	secretHash []byte
	name       string
	platform   string
	expires    time.Time
	approved   bool
}

// loadLink reads a live link request and checks the secret. An unknown ID is
// not_found, an expired one expired, a wrong secret forbidden.
func (m *Module) loadLink(r *http.Request, id, secret string) (linkRow, error) {
	var l linkRow
	if !uuidRE.MatchString(id) {
		return l, httpx.Err("not_found", "no such link")
	}
	err := m.d.DB.QueryRow(r.Context(),
		`select secret_hash, device_name, platform, expires_at, account_id is not null from link_requests where id = $1`, id).
		Scan(&l.secretHash, &l.name, &l.platform, &l.expires, &l.approved)
	if errors.Is(err, pgx.ErrNoRows) {
		return l, httpx.Err("not_found", "no such link")
	}
	if err != nil {
		return l, err
	}
	if !sameHash(l.secretHash, hashToken(secret)) {
		return l, httpx.Err("forbidden", "wrong link secret")
	}
	if !l.expires.After(m.d.Now()) {
		return l, httpx.Err("expired", "this link has expired")
	}
	return l, nil
}

// linkInfo answers POST /v1/link/{id}/info: what the approval screen shows.
func (m *Module) linkInfo(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Secret string `json:"secret"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	l, err := m.loadLink(r, r.PathValue("id"), in.Secret)
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"deviceName": l.name, "platform": l.platform, "createdAt": l.expires.Add(-linkTTL),
	})
	return nil
}

// decodeSealed decodes the opaque base64url payload (padding is tolerated).
func decodeSealed(s string) ([]byte, error) {
	return base64.RawURLEncoding.DecodeString(strings.TrimRight(s, "="))
}

// linkApprove answers POST /v1/link/{id}/approve. The server never reads
// sealed: it is encrypted to the new device's key and only stored.
func (m *Module) linkApprove(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Secret string  `json:"secret"`
		Sealed *string `json:"sealed"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	var sealed []byte
	if in.Sealed != nil {
		// A cheap length check first, so a huge string is not decoded.
		if len(*in.Sealed) > base64.RawURLEncoding.EncodedLen(maxSealedSize)+2 {
			return httpx.Err("too_large", "sealed is over 64 KiB")
		}
		var err error
		if sealed, err = decodeSealed(*in.Sealed); err != nil {
			return httpx.Err("bad_request", "sealed is not base64url")
		}
		if len(sealed) > maxSealedSize {
			return httpx.Err("too_large", "sealed is over 64 KiB")
		}
	}
	l, err := m.loadLink(r, r.PathValue("id"), in.Secret)
	if err != nil {
		return err
	}
	if l.approved {
		return httpx.Err("forbidden", "this link was already approved")
	}
	p := httpx.PrincipalFrom(r.Context())
	// The guards make approval one-shot even if two approvers race.
	tag, err := m.d.DB.Exec(r.Context(),
		`update link_requests set account_id = $2, sealed = $3
		 where id = $1 and account_id is null and expires_at > $4`,
		r.PathValue("id"), p.AccountID, sealed, m.d.Now())
	if err != nil {
		return err
	}
	if tag.RowsAffected() != 1 {
		return httpx.Err("forbidden", "this link was already approved")
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// linkClaimHandler answers POST /v1/link/{id}/claim. An unknown, used or
// expired request is expired: a claim deletes its request, so a second claim
// finds nothing.
func (m *Module) linkClaimHandler(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		ClaimToken string `json:"claimToken"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	ctx := r.Context()
	expired := httpx.Err("expired", "this link has expired")
	id := r.PathValue("id")
	if !uuidRE.MatchString(id) {
		return expired
	}
	tx, err := m.d.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit
	var (
		claimHash      []byte
		name, platform string
		accountID      *string
		sealed         []byte
		expires        time.Time
	)
	err = tx.QueryRow(ctx,
		`select claim_hash, device_name, platform, account_id, sealed, expires_at
		 from link_requests where id = $1 for update`, id).
		Scan(&claimHash, &name, &platform, &accountID, &sealed, &expires)
	if errors.Is(err, pgx.ErrNoRows) {
		return expired
	}
	if err != nil {
		return err
	}
	if !sameHash(claimHash, hashToken(in.ClaimToken)) {
		return httpx.Err("forbidden", "wrong claim token")
	}
	if !expires.After(m.d.Now()) {
		return expired
	}
	if accountID == nil {
		httpx.JSON(w, http.StatusAccepted, struct{}{})
		return nil
	}
	if _, err := tx.Exec(ctx, `delete from link_requests where id = $1`, id); err != nil {
		return err
	}
	deviceID, token, err := m.issueSession(ctx, tx, *accountID, name, platform)
	if err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	var sealedOut *string
	if sealed != nil {
		s := base64.RawURLEncoding.EncodeToString(sealed)
		sealedOut = &s
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"accountID": *accountID, "deviceID": deviceID, "sessionToken": token, "sealed": sealedOut,
	})
	return nil
}
