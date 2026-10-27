// Package attachments is lane E: encrypted blob upload, download and expiry.
package attachments

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io/fs"
	"net/http"
	"time"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

// Module is the attachments module.
type Module struct {
	d     core.Deps
	store BlobStore
}

// New returns the attachments module, storing blobs under Config.DataDir.
func New(d core.Deps) *Module {
	return &Module{d: d, store: NewDirStore(d.Config.DataDir)}
}

// Routes registers the attachment endpoints.
func (m *Module) Routes(r *httpx.Router) {
	r.Authed("POST /v1/attachments", m.upload)
	r.Authed("GET /v1/attachments/{id}", m.download)
}

func newID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

func (m *Module) upload(w http.ResponseWriter, r *http.Request) error {
	limits := m.d.Policy.Limits
	// The 1 MiB cap lives in httpx.Decode only, so enforce ours here.
	size := r.ContentLength
	if size <= 0 {
		return httpx.Err("bad_request", "Content-Length is required")
	}
	if size > limits.AttachmentBytes {
		return httpx.Err("too_large", "attachment is over the size limit")
	}
	me := httpx.PrincipalFrom(r.Context()).AccountID
	now := m.d.Now()

	var used int64
	if err := m.d.DB.QueryRow(r.Context(),
		`select coalesce(sum(size), 0) from attachments where account_id = $1 and created_at > $2`,
		me, now.Add(-24*time.Hour)).Scan(&used); err != nil {
		return err
	}
	if used+size > limits.AttachmentBytesPerDay {
		w.Header().Set("Retry-After", "3600")
		return httpx.Err("rate_limited", "daily attachment quota used up")
	}

	id, err := newID()
	if err != nil {
		return err
	}
	// MaxBytesReader stops a body longer than it declared; the body is streamed
	// to disk, never held in memory.
	n, err := m.store.Put(r.Context(), id, http.MaxBytesReader(w, r.Body, size))
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			return httpx.Err("too_large", "body is longer than Content-Length")
		}
		return httpx.Err("bad_request", "upload was interrupted")
	}
	if n != size {
		_ = m.store.Delete(r.Context(), id)
		return httpx.Err("bad_request", "body is shorter than Content-Length")
	}
	expires := now.Add(time.Duration(m.d.Policy.Retention.AttachmentDays) * 24 * time.Hour)
	if _, err := m.d.DB.Exec(r.Context(),
		`insert into attachments (id, account_id, size, created_at, expires_at) values ($1, $2, $3, $4, $5)`,
		id, me, n, now, expires); err != nil {
		_ = m.store.Delete(r.Context(), id)
		return err
	}
	httpx.JSON(w, http.StatusOK, struct {
		ID        string    `json:"id"`
		ExpiresAt time.Time `json:"expiresAt"`
	}{id, expires.UTC()})
	return nil
}

func (m *Module) download(w http.ResponseWriter, r *http.Request) error {
	id := r.PathValue("id")
	notFound := httpx.Err("not_found", "no such attachment")
	if !validID(id) {
		return notFound
	}
	var live bool
	if err := m.d.DB.QueryRow(r.Context(),
		`select exists (select 1 from attachments where id = $1 and expires_at > $2)`,
		id, m.d.Now()).Scan(&live); err != nil {
		return err
	}
	if !live {
		return notFound
	}
	f, err := m.store.Open(r.Context(), id)
	if errors.Is(err, fs.ErrNotExist) {
		return notFound
	}
	if err != nil {
		return err
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "private, no-store")
	http.ServeContent(w, r, "", time.Time{}, f)
	return nil
}

// PruneOnce deletes every expired attachment, file first, then row.
func (m *Module) PruneOnce(ctx context.Context) error {
	rows, err := m.d.DB.Query(ctx, `select id from attachments where expires_at <= $1`, m.d.Now())
	if err != nil {
		return err
	}
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, id := range ids {
		if err := m.store.Delete(ctx, id); err != nil {
			return err
		}
		if _, err := m.d.DB.Exec(ctx, `delete from attachments where id = $1`, id); err != nil {
			return err
		}
	}
	return nil
}

// Run deletes expired attachments every hour until ctx is cancelled.
func (m *Module) Run(ctx context.Context) {
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for {
		if err := m.PruneOnce(ctx); err != nil && ctx.Err() == nil {
			m.d.Log.Error("attachment prune failed")
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}
