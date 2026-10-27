package delivery

import (
	"context"
	"encoding/base64"
	"errors"
	"net/http"
	"slices"
	"strings"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/jackc/pgx/v5"
)

const (
	maxKeyPackageBytes   = 16 * 1024
	maxKeyPackagesStored = 100
)

// b64 is the wire encoding for binary fields: base64url without padding.
var b64 = base64.RawURLEncoding

func (m *Module) keyPackageRoutes(r *httpx.Router) {
	r.Authed("POST /v1/keypackages", m.uploadKeyPackages)
	r.Authed("GET /v1/keypackages/count", m.countKeyPackages)
	r.Authed("POST /v1/keypackages/claim", httpx.Limited(m.claimLimit, func(req *http.Request) string {
		return httpx.PrincipalFrom(req.Context()).AccountID
	}, m.claimKeyPackages))
}

type countResponse struct {
	Count int `json:"count"`
}

func (m *Module) uploadKeyPackages(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	var in struct {
		KeyPackages []string `json:"keyPackages"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if len(in.KeyPackages) == 0 || len(in.KeyPackages) > maxKeyPackagesStored {
		return httpx.Err("bad_request", "send between 1 and 100 KeyPackages")
	}
	blobs := make([][]byte, len(in.KeyPackages))
	for i, s := range in.KeyPackages {
		b, err := b64.DecodeString(s)
		if err != nil || len(b) == 0 {
			return httpx.Err("bad_request", "keyPackages must be non-empty base64url without padding")
		}
		if len(b) > maxKeyPackageBytes {
			return httpx.Err("too_large", "a KeyPackage is over 16 KiB")
		}
		blobs[i] = b
	}

	ctx := r.Context()
	var count int
	err := pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		// Serialise uploads for this device so the limit cannot be raced.
		if _, err := tx.Exec(ctx, `select 1 from devices where id = $1 for update`, p.DeviceID); err != nil {
			return err
		}
		if err := tx.QueryRow(ctx, `select count(*) from key_packages where device_id = $1`, p.DeviceID).Scan(&count); err != nil {
			return err
		}
		if count+len(blobs) > maxKeyPackagesStored {
			return httpx.Err("bad_request", "a device can store at most 100 KeyPackages")
		}
		now := m.d.Now()
		for _, b := range blobs {
			if _, err := tx.Exec(ctx,
				`insert into key_packages (device_id, data, created_at) values ($1, $2, $3)`,
				p.DeviceID, b, now); err != nil {
				return err
			}
		}
		count += len(blobs)
		return nil
	})
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, countResponse{count})
	return nil
}

func (m *Module) countKeyPackages(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	var n int
	if err := m.d.DB.QueryRow(r.Context(), `select count(*) from key_packages where device_id = $1`, p.DeviceID).Scan(&n); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, countResponse{n})
	return nil
}

type claimedKeyPackage struct {
	DeviceID   string `json:"deviceID"`
	KeyPackage string `json:"keyPackage"`
}

func (m *Module) claimKeyPackages(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	var in struct {
		AccountID string   `json:"accountID"`
		DeviceIDs []string `json:"deviceIDs"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	ctx := r.Context()
	// Unknown, malformed and blocking targets all give the same not_found
	// (spec section 8): same status, code, message and no side effects.
	notFound := httpx.Err("not_found", "no such account")
	if !uuidRE.MatchString(in.AccountID) {
		return notFound
	}
	target := strings.ToLower(in.AccountID)
	var exists bool
	if err := m.d.DB.QueryRow(ctx, `select exists (select 1 from accounts where id = $1)`, target).Scan(&exists); err != nil {
		return err
	}
	if !exists {
		return notFound
	}
	blocked, err := m.d.Blocks.IsBlocked(ctx, target, p.AccountID)
	if err != nil {
		return err
	}
	if blocked {
		return notFound
	}
	devices, err := m.d.Directory.DeviceIDs(ctx, target)
	if err != nil {
		return err
	}

	// An optional filter limits the claim to some devices of the account.
	// IDs that are not devices of the account are ignored, so the reply never
	// tells whether a device exists.
	if len(in.DeviceIDs) > 0 {
		devices = slices.DeleteFunc(devices, func(d string) bool { return !slices.Contains(in.DeviceIDs, d) })
	}

	// Lock devices in a fixed order so claims cannot deadlock each other.
	slices.Sort(devices)
	out := make([]claimedKeyPackage, 0, len(devices))
	err = pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		out = out[:0]
		for _, dev := range devices {
			data, ok, err := claimOne(ctx, tx, dev)
			if err != nil {
				return err
			}
			if ok {
				out = append(out, claimedKeyPackage{dev, b64.EncodeToString(data)})
			}
		}
		return nil
	})
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, struct {
		KeyPackages []claimedKeyPackage `json:"keyPackages"`
	}{out})
	return nil
}

// claimOne takes the oldest KeyPackage of a device. It deletes the package
// only when the device has another one, so the last one stays as the device's
// last resort. The device row is locked first (the same lock uploads take) so
// concurrent claims on one device run one after another and each sees the
// other's delete.
func claimOne(ctx context.Context, tx pgx.Tx, deviceID string) ([]byte, bool, error) {
	if _, err := tx.Exec(ctx, `select 1 from devices where id = $1 for update`, deviceID); err != nil {
		return nil, false, err
	}
	var id int64
	var data []byte
	err := tx.QueryRow(ctx,
		`select id, data from key_packages where device_id = $1 order by id limit 1`,
		deviceID).Scan(&id, &data)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	var n int
	if err := tx.QueryRow(ctx, `select count(*) from key_packages where device_id = $1`, deviceID).Scan(&n); err != nil {
		return nil, false, err
	}
	if n > 1 {
		if _, err := tx.Exec(ctx, `delete from key_packages where id = $1`, id); err != nil {
			return nil, false, err
		}
	}
	return data, true, nil
}
