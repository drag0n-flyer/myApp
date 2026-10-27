package delivery

import (
	"context"
	"net/http"
	"regexp"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

var uuidRE = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func (m *Module) conversationRoutes(r *httpx.Router) {
	r.Authed("POST /v1/conversations", m.createConversation)
	r.Authed("GET /v1/conversations/{id}", m.getConversation)
	r.Authed("POST /v1/conversations/{id}/commits", m.postCommit)
}

type conversationResponse struct {
	ConversationID string `json:"conversationID"`
	Mode           string `json:"mode"`
	Epoch          int64  `json:"epoch"`
}

func (m *Module) createConversation(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	var in struct {
		Members []string `json:"members"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if len(in.Members) != 1 {
		return httpx.Err("bad_request", "members must hold exactly one other account")
	}
	other := strings.ToLower(in.Members[0])
	if other == strings.ToLower(p.AccountID) {
		return httpx.Err("bad_request", "members must hold exactly one other account")
	}
	ctx := r.Context()
	if !uuidRE.MatchString(other) {
		return httpx.Err("not_found", "no such account")
	}
	var exists bool
	if err := m.d.DB.QueryRow(ctx, `select exists (select 1 from accounts where id = $1)`, other).Scan(&exists); err != nil {
		return err
	}
	if !exists {
		return httpx.Err("not_found", "no such account")
	}

	id := core.NewID()
	mode := m.d.Policy.Encryption.NewConversations
	err := pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `insert into conversations (id, mode, created_at) values ($1, $2, $3)`, id, mode, m.d.Now()); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `insert into conversation_accounts (conversation_id, account_id) values ($1, $2), ($1, $3)`, id, p.AccountID, other); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `insert into conversation_devices (conversation_id, device_id) values ($1, $2)`, id, p.DeviceID)
		return err
	})
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, conversationResponse{id, mode, 0})
	return nil
}

func (m *Module) getConversation(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	id := strings.ToLower(r.PathValue("id"))
	notFound := httpx.Err("not_found", "no such conversation")
	if !uuidRE.MatchString(id) {
		return notFound
	}
	var out conversationResponse
	err := m.d.DB.QueryRow(r.Context(),
		`select c.id::text, c.mode, c.epoch from conversations c
		 join conversation_devices cd on cd.conversation_id = c.id
		 where c.id = $1 and cd.device_id = $2`, id, p.DeviceID).Scan(&out.ConversationID, &out.Mode, &out.Epoch)
	if err == pgx.ErrNoRows {
		return notFound
	}
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, out)
	return nil
}

func (m *Module) postCommit(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	id := strings.ToLower(r.PathValue("id"))
	var in struct {
		Epoch         int64    `json:"epoch"`
		Commit        string   `json:"commit"`
		Welcome       *string  `json:"welcome"`
		AddDevices    []string `json:"addDevices"`
		RemoveDevices []string `json:"removeDevices"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	notFound := httpx.Err("not_found", "no such conversation")
	if !uuidRE.MatchString(id) {
		return notFound
	}
	commit, err := b64.DecodeString(in.Commit)
	if err != nil || len(commit) == 0 || in.Epoch < 0 {
		return httpx.Err("bad_request", "epoch and a base64url commit are required")
	}
	var welcome []byte
	if in.Welcome != nil {
		if welcome, err = b64.DecodeString(*in.Welcome); err != nil || len(welcome) == 0 {
			return httpx.Err("bad_request", "welcome must be non-empty base64url")
		}
	}
	add, err1 := normalizeIDs(in.AddDevices)
	remove, err2 := normalizeIDs(in.RemoveDevices)
	if err1 != nil || err2 != nil {
		return httpx.Err("bad_request", "device IDs must be UUIDs")
	}

	var newEpoch int64
	err = m.inTx(r.Context(), func(ctx context.Context, tx pgx.Tx) error {
		// Lock the conversation row first: commits are serialised, and a
		// non-member learns nothing, not even the epoch.
		var current int64
		err := tx.QueryRow(ctx, `select epoch from conversations where id = $1 for update`, id).Scan(&current)
		if err == pgx.ErrNoRows {
			return notFound
		}
		if err != nil {
			return err
		}
		var member bool
		if err := tx.QueryRow(ctx,
			`select exists (select 1 from conversation_devices where conversation_id = $1 and device_id = $2)`,
			id, p.DeviceID).Scan(&member); err != nil {
			return err
		}
		if !member {
			return notFound
		}

		// 1. Advance the epoch, only if the caller has the current one.
		err = tx.QueryRow(ctx,
			`update conversations set epoch = epoch + 1 where id = $1 and epoch = $2 returning epoch`,
			id, in.Epoch).Scan(&newEpoch)
		if err == pgx.ErrNoRows {
			return httpx.ErrDetails("stale_epoch", "the conversation has moved on", map[string]int64{"currentEpoch": current})
		}
		if err != nil {
			return err
		}

		// 2. Snapshot the member devices present before this commit.
		snapshot, err := queryStrings(ctx, tx,
			`select device_id::text from conversation_devices where conversation_id = $1 order by device_id`, id)
		if err != nil {
			return err
		}

		// 3. Apply adds and removes. Added devices must belong to the
		// conversation's accounts.
		var added []string
		if len(add) > 0 {
			ok, err := queryStrings(ctx, tx,
				`select d.id::text from devices d
				 join conversation_accounts ca on ca.account_id = d.account_id
				 where ca.conversation_id = $1 and d.id::text = any($2)`, id, add)
			if err != nil {
				return err
			}
			if len(ok) != len(add) {
				return httpx.Err("forbidden", "a device does not belong to this conversation's accounts")
			}
			added, err = queryStrings(ctx, tx,
				`insert into conversation_devices (conversation_id, device_id)
				 select $1, unnest($2::uuid[]) on conflict do nothing returning device_id::text`, id, add)
			if err != nil {
				return err
			}
		}
		if len(remove) > 0 {
			if _, err := tx.Exec(ctx,
				`delete from conversation_devices where conversation_id = $1 and device_id::text = any($2)`, id, remove); err != nil {
				return err
			}
		}

		// 4. Deliver. The commit goes to the whole snapshot, sender included.
		// It carries the epoch it was made at; a welcome carries the new one.
		now := m.d.Now()
		if err := m.deliver(ctx, tx, snapshot, InboxItem{
			Kind: "commit", ConversationID: id, Epoch: in.Epoch,
			SenderAccountID: p.AccountID, SenderDeviceID: p.DeviceID, Payload: commit, SentAt: now,
		}); err != nil {
			return err
		}
		if welcome != nil && len(added) > 0 {
			return m.deliver(ctx, tx, added, InboxItem{
				Kind: "welcome", ConversationID: id, Epoch: newEpoch,
				SenderAccountID: p.AccountID, SenderDeviceID: p.DeviceID, Payload: welcome, SentAt: now,
			})
		}
		return nil
	})
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, struct {
		Epoch int64 `json:"epoch"`
	}{newEpoch})
	return nil
}

// normalizeIDs lowercases UUIDs and drops duplicates; anything else is an error.
func normalizeIDs(in []string) ([]string, error) {
	seen := map[string]bool{}
	out := make([]string, 0, len(in))
	for _, s := range in {
		if !uuidRE.MatchString(s) {
			return nil, httpx.Err("bad_request", "bad id")
		}
		s = strings.ToLower(s)
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out, nil
}

func queryStrings(ctx context.Context, tx pgx.Tx, sql string, args ...any) ([]string, error) {
	rows, err := tx.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}
