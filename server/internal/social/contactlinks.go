package social

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"net/http"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/jackc/pgx/v5"
)

const (
	contactLinkTTL  = 7 * 24 * time.Hour
	maxLiveLinks    = 20
	contactLinkSize = 16 // random bytes in a token
)

func (m *Module) contactLinkRoutes(r *httpx.Router) {
	r.Authed("POST /v1/contact-links", m.createContactLink)
	r.Authed("POST /v1/contact-links/redeem", httpx.Limited(m.lookups, accountKey, m.redeemContactLink))
}

func hashLinkToken(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

// createContactLink answers POST /v1/contact-links with a new one-time token.
// Only its SHA-256 is stored. At most 20 unused, unexpired links per account.
func (m *Module) createContactLink(w http.ResponseWriter, r *http.Request) error {
	me := httpx.PrincipalFrom(r.Context()).AccountID
	var raw [contactLinkSize]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return err
	}
	token := base64.RawURLEncoding.EncodeToString(raw[:])
	now := m.d.Now()
	expires := now.Add(contactLinkTTL)

	tx, err := m.d.DB.Begin(r.Context())
	if err != nil {
		return err
	}
	defer tx.Rollback(r.Context()) //nolint:errcheck // no-op after commit
	// Serialise this account's creations so the cap cannot be raced past.
	if _, err := tx.Exec(r.Context(), `select pg_advisory_xact_lock(hashtextextended('contact_links:' || $1::text, 0))`, me); err != nil {
		return err
	}
	var live int
	if err := tx.QueryRow(r.Context(),
		`select count(*) from contact_links where account_id = $1 and used_at is null and expires_at > $2`,
		me, now).Scan(&live); err != nil {
		return err
	}
	if live >= maxLiveLinks {
		return httpx.Err("too_many", "too many unused links; use or wait for one to expire")
	}
	if _, err := tx.Exec(r.Context(),
		`insert into contact_links (token_hash, account_id, created_at, expires_at) values ($1, $2, $3, $4)`,
		hashLinkToken(token), me, now, expires); err != nil {
		return err
	}
	if err := tx.Commit(r.Context()); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"token": token, "expiresAt": expires})
	return nil
}

// redeemContactLink answers POST /v1/contact-links/redeem. One UPDATE both
// checks and uses the link, so of concurrent redeems exactly one wins. An
// unknown, expired, used or deleted-owner link, and a link whose owner has
// blocked the caller, all fail the UPDATE and then take the same follow-up
// query and the same expired answer.
func (m *Module) redeemContactLink(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Token string `json:"token"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	me := httpx.PrincipalFrom(r.Context()).AccountID
	hash := hashLinkToken(in.Token)
	now := m.d.Now()

	var u userInfo
	var got []byte
	err := m.d.DB.QueryRow(r.Context(),
		`update contact_links l set used_at = $2, used_by = $3
		   from accounts a
		  where l.token_hash = $1 and l.used_at is null and l.expires_at > $2
		    and l.account_id <> $3 and a.id = l.account_id
		    and not exists (select 1 from blocks b where b.blocker = l.account_id and b.blocked = $3)
		 returning a.id, a.username, a.display_name, l.token_hash`,
		hash, now, me).Scan(&u.AccountID, &u.Username, &u.DisplayName, &got)
	if err == nil {
		if subtle.ConstantTimeCompare(got, hash) != 1 {
			return httpx.Err("expired", "this link has expired")
		}
		httpx.JSON(w, http.StatusOK, u)
		return nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	// The one thing told apart from expired: the caller's own live link.
	var own bool
	if err := m.d.DB.QueryRow(r.Context(),
		`select exists (select 1 from contact_links
		                 where token_hash = $1 and account_id = $2 and used_at is null and expires_at > $3)`,
		hash, me, now).Scan(&own); err != nil {
		return err
	}
	if own {
		return httpx.Err("own_link", "this is your own link")
	}
	return httpx.Err("expired", "this link has expired")
}
